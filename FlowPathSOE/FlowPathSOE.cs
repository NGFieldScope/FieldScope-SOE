using System;
using System.Collections.Specialized;
using System.Runtime.InteropServices;
using System.Text;
using ESRI.ArcGIS.DataSourcesRaster;
using ESRI.ArcGIS.esriSystem;
using ESRI.ArcGIS.Geodatabase;
using ESRI.ArcGIS.Geometry;
using ESRI.ArcGIS.SOESupport;

namespace NatGeo.FieldScope.SOE
{
    [ComVisible(true)]
    [Guid("098d638b-8841-4c4d-958f-90ad146dd6ef")]
    [ClassInterface(ClassInterfaceType.None)]
    [ServerObjectExtension("MapServer",
        AllCapabilities = "",
        DefaultCapabilities = "",
        Description = "Compute flow path downhill from pour point",
        DisplayName = "FlowPathSOE",
        Properties = "HighResolutionMaxSteps=1000;LowResolutionMaxSteps=16384",
        SupportsREST = true,
        SupportsSOAP = false)]
    public class FlowPathSOE : FieldScopeSOE
    {
        private IRaster m_LowResFlowDir = null;
        private IFeatureClass m_HighResFlowDirIndex = null;
        private IFeatureClass m_HighResFlowDirCatalog = null;
        private int m_maxHighResolutionSteps = 1000;
        private int m_maxLowResolutionSteps = 16384;

        override public void Construct (IPropertySet props) {
            base.Construct(props);
            if (props.GetProperty("HighResolutionMaxSteps") != null) {
                m_maxHighResolutionSteps = int.Parse(props.GetProperty("HighResolutionMaxSteps") as string);
            }
            if (props.GetProperty("LowResolutionMaxSteps") != null) {
                m_maxLowResolutionSteps = int.Parse(props.GetProperty("LowResolutionMaxSteps") as string);
            }
            m_LowResFlowDir = GetDataSourceByID(0) as IRaster;
            if (m_LowResFlowDir == null) {
                LogError("missing or invalid data layer: low resolution flow direction");
            }
            m_HighResFlowDirIndex = GetDataSourceByID(1) as IFeatureClass;
            if (m_HighResFlowDirIndex == null) {
                LogWarning("missing or invalid data layer: high resolution flow direction index");
            }
            m_HighResFlowDirCatalog = GetDataSourceByID(2) as IFeatureClass;
            if (m_HighResFlowDirCatalog == null) {
                LogWarning("missing or invalid data layer: high resolution flow direction catalog");
            }
        }

        override public void Shutdown () {
            base.Shutdown();
            m_LowResFlowDir = null;
            m_HighResFlowDirIndex = null;
        }

        override protected RestResource CreateRestSchema () {
            RestResource rootRes = new RestResource(Name, false, RootHandler);
            RestOperation sampleOper = new RestOperation("flowPath",
                                                         new string[] { "pourPoint", "outSR" },
                                                         new string[] { "json" },
                                                         FlowPathHandler);
            rootRes.operations.Add(sampleOper);
            return rootRes;
        }

        private byte[] RootHandler (NameValueCollection boundVariables, string outputFormat, string requestProperties, out string responseProperties) {
            responseProperties = null;
            JsonObject result = new JsonObject();
            result.AddString("service", "flowPath");
            return Encoding.UTF8.GetBytes(result.ToJson());
        }

        private byte[] FlowPathHandler (NameValueCollection boundVariables, JsonObject operationInput, string outputFormat, string requestProperties, out string responseProperties) {
            responseProperties = null;
            JsonObject pointJson;
            if (!operationInput.TryGetJsonObject("pourPoint", out pointJson)) {
                throw new ArgumentNullException("pourPoint");
            }
            IPoint point = Conversion.ToGeometry(pointJson, esriGeometryType.esriGeometryPoint) as IPoint;
            if (point == null) {
                throw new ArgumentException(Name + ": invalid pourPoint", "pourPoint");
            }
            ISpatialReference outSR = GetSpatialReferenceParam(operationInput, "outSR");
            ISpatialReference workSR = (m_LowResFlowDir as IRasterProps).SpatialReference;
            if ((point.SpatialReference != null) && (point.SpatialReference.FactoryCode != workSR.FactoryCode)) {
                point.Project(workSR);
            }

            PathClass path = new PathClass();
            path.AddPoint(point);
            IRaster highResFlowDir = null;
            if ((m_HighResFlowDirIndex != null) && (m_HighResFlowDirCatalog != null)) {
                object hiResDS = Util.FindValue(m_HighResFlowDirIndex, "VALUE", point);
                if (hiResDS != null) {
                    highResFlowDir = Util.FindRaster(m_HighResFlowDirCatalog, hiResDS.ToString());
                }
            }
            if (highResFlowDir != null) {
                TracePath(point, highResFlowDir, m_maxHighResolutionSteps, path);
            }
            TracePath(point, m_LowResFlowDir, m_maxLowResolutionSteps, path);

            PolylineClass poly = new PolylineClass();
            poly.AddGeometry(path);
            poly.SpatialReference = workSR;

            if ((outSR != null) && (outSR.FactoryCode != poly.SpatialReference.FactoryCode)) {
                poly.Project(outSR);
            }

            Feature resultFeature = new Feature();
            resultFeature.Geometry = poly;
            resultFeature.Attributes.Add("Shape_Length", path.Length);
            resultFeature.Attributes.Add("Shape_Units", DescribeUnits(path.SpatialReference));
            FeatureSet result = new FeatureSet();
            result.GeometryType = esriGeometryType.esriGeometryPolyline;
            result.Features.Add(resultFeature);

            return Encoding.UTF8.GetBytes(result.ToJsonObject().ToJson());
        }

        private void TracePath (IPoint pourPoint, IRaster raster, int maxSteps, IPointCollection path) {
            PointClass point = new PointClass();
            point.X = pourPoint.X;
            point.Y = pourPoint.Y;
            IRaster2 raster2 = raster as IRaster2;
            IRasterBand rasterBand = (raster2 as IRasterBandCollection).Item(0);
            IRawPixels rawPixels = rasterBand as IRawPixels;
            IRasterProps rasterProperties = rasterBand as IRasterProps;
            int rasterWidth = rasterProperties.Width;
            int rasterHeight = rasterProperties.Height;
            Int32 noData = Convert.ToInt32(rasterProperties.NoDataValue);
            IPnt cellSize = rasterProperties.MeanCellSize();
            IPnt blockSize = new Pnt();
            blockSize.SetCoords(rasterProperties.Width, rasterProperties.Height);
            IPixelBlock pixelBlock = raster.CreatePixelBlock(blockSize);
            IPnt origin = new Pnt();
            origin.SetCoords(0, 0);
            rawPixels.Read(origin, pixelBlock);
            System.Array data = (System.Array)(pixelBlock as IPixelBlock3).get_PixelDataByRef(0);
            double dx = 0;
            double dy = 0;
            double lastDx;
            double lastDy;
            int row;
            int col;
            int steps = 0;
            while (true) {
                steps += 1;
                raster2.MapToPixel(point.X, point.Y, out col, out row);
                if ((col < 0) || (col >= rasterWidth) || (row < 0) || (row >= rasterHeight)) {
                    break;
                }
                object value = data.GetValue(col, row);
                if ((value == null) || (Convert.ToInt32(value) == noData)) {
                    break;
                }
                Int32 flowDir = Convert.ToInt32(value);
                lastDx = dx;
                lastDy = dy;
                switch (flowDir) {
                    case 1:
                        dx = cellSize.X;
                        dy = 0;
                        break;
                    case 2:
                        dx = cellSize.X;
                        dy = -cellSize.Y;
                        break;
                    case 4:
                        dx = 0;
                        dy = -cellSize.Y;
                        break;
                    case 8:
                        dx = -cellSize.X;
                        dy = -cellSize.Y;
                        break;
                    case 16:
                        dx = -cellSize.X;
                        dy = 0;
                        break;
                    case 32:
                        dx = -cellSize.X;
                        dy = cellSize.Y;
                        break;
                    case 64:
                        dx = 0;
                        dy = cellSize.Y;
                        break;
                    case 128:
                        dx = cellSize.X;
                        dy = cellSize.Y;
                        break;
                    default:
                        dx = dy = 0;
                        break;
                }
                if ((dx != lastDx) || (dy != lastDy)) {
                    path.AddPoint(point.Clone() as IPoint);
                }
                if (((dx == 0) && (dy == 0)) || (steps > maxSteps)) {
                    break;
                }
                point.X += dx;
                point.Y += dy;
            }
            path.AddPoint(point.Clone() as IPoint);
            pourPoint.X = point.X;
            pourPoint.Y = point.Y;
        }
    }
}
