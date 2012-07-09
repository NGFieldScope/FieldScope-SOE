using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using ESRI.ArcGIS.Carto;
using ESRI.ArcGIS.DataSourcesRaster;
using ESRI.ArcGIS.esriSystem;
using ESRI.ArcGIS.Geodatabase;
using ESRI.ArcGIS.Geometry;
using ESRI.ArcGIS.SOESupport;

namespace NatGeo.FieldScope.SOE
{
    [ComVisible(true)]
    [Guid("5d2a6487-02b7-409a-af26-682aa8cee888")]
    [ClassInterface(ClassInterfaceType.None)]
    [ServerObjectExtension("MapServer",
        AllCapabilities = "GetInfo,QueryRaster",
        DefaultCapabilities = "GetInfo,QueryRaster",
        Description = "Return polygon of all raster cells that satisfy given conditions",
        DisplayName = "QueryRasterSOE",
        Properties = "",
        SupportsREST = true,
        SupportsSOAP = false)]
    public class QueryRasterSOE : FieldScopeSOE
    {
        private Dictionary<int, QueryRasterLayer> m_layers;

        override public void Construct (IPropertySet props) {
            base.Construct(props);
            m_layers = new Dictionary<int, QueryRasterLayer>();
            JsonObject result = new JsonObject();
            List<QueryRasterLayer> layerInfo = new List<QueryRasterLayer>();
            foreach (IMapLayerInfo layer in GetMapLayerInfo()) {
                IRaster raster = GetDataSourceByID(layer.ID) as IRaster;
                if (raster != null) {
                    m_layers.Add(layer.ID, new QueryRasterLayer(layer, raster));
                }
            }
        }

        override protected RestResource CreateRestSchema () {
            RestResource rootResource = new RestResource(Name, false, RootHandler, "GetInfo");
            RestResource layersResource = new RestResource("layers", true, GetLayerHandler, "GetInfo");
            RestOperation queryRasterOp = new RestOperation("queryRaster",
                                                            new string[] { "min", "max", "outSR" },
                                                            new string[] { "json" },
                                                            QueryRasterHandler,
                                                            "QueryRaster");
            layersResource.operations.Add(queryRasterOp);
            rootResource.resources.Add(layersResource);
            return rootResource;
        }

        private byte[] RootHandler (NameValueCollection boundVariables, string outputFormat, string requestProperties, out string responseProperties) {
            responseProperties = null;
            JsonObject result = new JsonObject();
            result.AddArray("layers", m_layers.Values.Select(i => i.ToJsonObject()).ToArray<JsonObject>());
            return Encoding.UTF8.GetBytes(result.ToJson());
        }

        private byte[] GetLayerHandler (NameValueCollection boundVariables, string outputFormat, string requestProperties, out string responseProperties) {
            responseProperties = null;
            int layerID = Convert.ToInt32(boundVariables["layersID"]);
            QueryRasterLayer layer;
            if (m_layers.TryGetValue(layerID, out layer)) {
                return Encoding.UTF8.GetBytes(layer.ToJsonObject().ToJson());
            } else {
                throw new ArgumentOutOfRangeException("layersID");
            }
        }

        private byte[] QueryRasterHandler (NameValueCollection boundVariables, JsonObject operationInput, string outputFormat, string requestProperties, out string responseProperties) {
            responseProperties = null;
            int layerID = Convert.ToInt32(boundVariables["layersID"]);
            double? min = null;
            double? max = null;
            operationInput.TryGetAsDouble("min", out min);
            operationInput.TryGetAsDouble("max", out max);
            if ((!min.HasValue) && (!max.HasValue)) {
                throw new ArgumentException(Name + ": must specify at least one of {min,max}");
            }
            ISpatialReference outSR = GetSpatialReferenceParam(operationInput, "outSR");

            IRaster raster = m_layers[layerID].Raster;
            IRasterBand band = (raster as IRasterBandCollection).Item(0);
            IRawPixels pixels = band as IRawPixels;
            IRasterProps properties = band as IRasterProps;
            int width = properties.Width;
            int height = properties.Height;
            double noData = Convert.ToDouble(properties.NoDataValue);

            IPnt flowDirBlockSize = new Pnt();
            flowDirBlockSize.SetCoords(width, height);
            IPixelBlock pixelBlock = raster.CreatePixelBlock(flowDirBlockSize);
            IPnt pixelOrigin = new Pnt();
            pixelOrigin.SetCoords(0, 0);
            pixels.Read(pixelOrigin, pixelBlock);
            System.Array data = (System.Array)(pixelBlock as IPixelBlock3).get_PixelDataByRef(0);

            bool[,] outData = new bool[width, height];
            for (int x = 0; x < width; x += 1) {
                for (int y = 0; y < height; y += 1) {
                    object value = data.GetValue(x, y);
                    bool cellValue = false;
                    if (value != null) {
                        double numericValue = Convert.ToDouble(value);
                        if ((numericValue != noData) &&
                            ((!min.HasValue) || (numericValue > min.Value)) &&
                            ((!max.HasValue) || (numericValue < max.Value))) {
                            cellValue = true;
                        }
                    }
                    outData.SetValue(cellValue, x, y);
                }
            }

            BoundingCurve bc = new BoundingCurve(outData);
            IPolygon resultGeom = bc.GetBoundaryAsPolygon(properties);
            resultGeom.SpatialReference = properties.SpatialReference;


            if ((outSR != null) && (outSR.FactoryCode != resultGeom.SpatialReference.FactoryCode)) {
                resultGeom.Project(outSR);
            }

            Feature resultFeature = new Feature();
            resultFeature.Geometry = resultGeom;
            resultFeature.Attributes.Add("Shape_Length", resultGeom.Length);
            resultFeature.Attributes.Add("Shape_Area", (resultGeom as IArea).Area);
            resultFeature.Attributes.Add("Shape_Units", DescribeUnits(resultGeom.SpatialReference));
            FeatureSet result = new FeatureSet();
            result.GeometryType = esriGeometryType.esriGeometryPolygon;
            result.Features.Add(resultFeature);

            return Encoding.UTF8.GetBytes(result.ToJsonObject().ToJson());
        }
    }

    public class QueryRasterLayer
    {
        public string Name;
        public int ID;
        public IEnvelope Extent;
        public IRaster Raster;

        public QueryRasterLayer (IMapLayerInfo mapLayerInfo, IRaster raster) {
            this.Name = mapLayerInfo.Name;
            this.ID = mapLayerInfo.ID;
            this.Extent = mapLayerInfo.Extent;
            this.Raster = raster;
        }

        public JsonObject ToJsonObject () {
            JsonObject result = new JsonObject();
            result.AddString("name", Name);
            result.AddLong("id", ID);
            IRasterProps props = Raster as IRasterProps;
            result.AddLong("rows", props.Width);
            result.AddLong("columns", props.Height);
            result.AddJsonObject("extent", Conversion.ToJsonObject(props.Extent));
            return result;
        }
    }
}
