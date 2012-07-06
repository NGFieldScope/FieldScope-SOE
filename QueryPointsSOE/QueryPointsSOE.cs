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
    [Guid("098d638b-8841-4c4d-958f-90ad146dd6ef")]
    [ClassInterface(ClassInterfaceType.None)]
    [ServerObjectExtension("MapServer",
        AllCapabilities = "GetInfo;QueryPoints",
        DefaultCapabilities = "GetInfo;QueryPoints",
        Description = "Query map layer at multiple points",
        DisplayName = "QueryPointsSOE",
        Properties = "",
        SupportsREST = true,
        SupportsSOAP = false)]
    public class QueryPointsSOE : FieldScopeSOE
    {
        private Dictionary<int, QueryPointsLayer> m_layers;

        override public void Construct (IPropertySet props) {
            base.Construct(props);
            m_layers = new Dictionary<int, QueryPointsLayer>();
            JsonObject result = new JsonObject();
            List<QueryPointsLayer> layerInfo = new List<QueryPointsLayer>();
            IMapLayerInfo[] layers = GetMapLayerInfo();
            for (int i = 0; i < layers.Length; ) {
                if (layers[i].Type.Equals("Raster Layer")) {
                    m_layers.Add(layers[i].ID, new QueryPointsRasterLayer(
                            layers[i],
                            GetDataSourceByID(layers[i].ID) as IRaster));
                    i += 1;
                } else if (layers[i].Type.Equals("Feature Layer")) {
                    m_layers.Add(layers[i].ID, new QueryPointsFeatureClassLayer(
                        layers[i],
                        GetDataSourceByID(layers[i].ID) as IFeatureClass));
                    i += 1;
                } else if (layers[i].Type.Equals("Mosaic Layer")) {
                    m_layers.Add(layers[i].ID, new QueryPointsMosaicLayer(
                            layers[i],
                            GetDataSourceByID(layers[i].ID + 1) as IFeatureClass,
                            GetDataSourceByID(layers[i].ID + 2) as IFeatureClass,
                            GetDataSourceByID(layers[i].ID + 3) as IRaster));
                    i += 4;
                } else {
                    i += 1;
                }
            }
        }

        override protected RestResource CreateRestSchema () {
            RestResource rootResource = new RestResource(Name, false, RootHandler, "GetInfo");
            RestResource layersResource = new RestResource("layers", true, GetLayerHandler, "GetInfo");
            RestOperation queryPointsOp = new RestOperation("queryPoints",
                                                            new string[] { "points", "outField" },
                                                            new string[] { "json" },
                                                            QueryPointsHandler,
                                                            "QueryPoints");
            layersResource.operations.Add(queryPointsOp);
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
            QueryPointsLayer layer;
            if (m_layers.TryGetValue(layerID, out layer)) {
                return Encoding.UTF8.GetBytes(layer.ToJsonObject().ToJson());
            } else {
                throw new ArgumentOutOfRangeException("layersID");
            }
        }

        private byte[] QueryPointsHandler (NameValueCollection boundVariables, JsonObject operationInput, string outputFormat, string requestProperties, out string responseProperties) {
            responseProperties = null;
            int layerID = Convert.ToInt32(boundVariables["layersID"]);
            object[] pointsJson;
            if (!operationInput.TryGetArray("points", out pointsJson)) {
                throw new ArgumentNullException("points");
            }
            PointQuery[] points = new PointQuery[pointsJson.Length];
            for (int i = 0; i < pointsJson.Length; i += 1) {
                points[i] = new PointQuery(pointsJson[i] as JsonObject);
            }
            string outField = null;
            operationInput.TryGetString("outField", out outField);
            JsonObject result = new JsonObject();
            result.AddObject("results", m_layers[layerID].Query(points, outField).ToArray());
            return Encoding.UTF8.GetBytes(result.ToJson());
        }
    }

    internal class PointQuery
    {
        public readonly IPoint Geometry;
        public readonly string ID;
        public readonly ITimeValue Time;

        public PointQuery (JsonObject json) {
            Geometry = new PointClass();
            double? x, y;
            if (json.TryGetAsDouble("x", out x)) {
                Geometry.X = x.Value;
            } else {
                throw new ArgumentNullException("x");
            }
            if (json.TryGetAsDouble("y", out y)) {
                Geometry.Y = y.Value;
            } else {
                throw new ArgumentNullException("y");
            }
            long? longId;
            string stringId;
            if (json.TryGetAsLong("id", out longId)) {
                ID = longId.Value.ToString();
            } else if (json.TryGetString("id", out stringId)) {
                ID = stringId;
            } else {
                ID = null;
            }
            long? timeLong;
            string timeStr;
            if (json.TryGetAsLong("t", out timeLong)) {
                ITimeInstant instant = new TimeInstantClass();
                instant.Time = Util.ParseUTCMilliseconds(timeLong.Value);
                Time = instant;
            } else if (json.TryGetString("t", out timeStr)) {
                if ((timeStr != null) && (timeStr.Length > 0)) {
                    if (timeStr.IndexOf(',') >= 0) {
                        long[] times = timeStr.Split(',').Select(s => long.Parse(s)).ToArray();
                        ITimeExtent extent = new TimeExtentClass();
                        extent.StartTime = Util.ParseUTCMilliseconds(times[0]);
                        extent.EndTime = Util.ParseUTCMilliseconds(times[1]);
                        Time = extent;
                    } else {
                        ITimeInstant instant = new TimeInstantClass();
                        instant.Time = Util.ParseUTCMilliseconds(long.Parse(timeStr));
                        Time = instant;
                    }
                }
            }
        }

        public string GetWhereClause (IMapTableTimeInfo timeInfo) {
            if ((Time == null) || (!timeInfo.SupportsTime)) {
                return null;
            } else if (Time is ITimeExtent) {
                ITimeExtent extent = Time as ITimeExtent;
                return String.Format("{0} >= date '{2}' AND {1} <= date '{3}'",
                                     timeInfo.StartTimeFieldName,
                                     timeInfo.EndTimeFieldName,
                                     extent.StartTime.QueryTimeString(esriTimeStringFormat.esriTSFYearThruSecondWithDash),
                                     extent.EndTime.QueryTimeString(esriTimeStringFormat.esriTSFYearThruSecondWithDash));
            } else if (Time is ITimeInstant) {
                ITimeInstant instant = Time as ITimeInstant;
                if (timeInfo.EndTimeFieldName != null) {
                    ITimeExtent extent = Time as ITimeExtent;
                    return String.Format("{0} <= date '{2}' AND {1} > date '{2}'",
                                         timeInfo.StartTimeFieldName,
                                         timeInfo.EndTimeFieldName,
                                         instant.Time.QueryTimeString(esriTimeStringFormat.esriTSFYearThruSecondWithDash));
                } else {
                    return String.Format("{0} = date '{1}'",
                                         timeInfo.StartTimeFieldName,
                                         instant.Time.QueryTimeString(esriTimeStringFormat.esriTSFYearThruSecondWithDash));
                }
            }
            return null;
        }

        public JsonObject ToJson (IList<object> values) {
            JsonObject result = new JsonObject();
            if (ID != null) {
                result.AddString("id", ID);
            }
            if (values.Count == 0) {
                result.AddObject("result", null);
            } else if (values.Count == 1) {
                result.AddObject("result", values[0]);
            } else {
                result.AddObject("result", values.ToArray());
            }
            return result;
        }
    }

    internal abstract class QueryPointsLayer
    {
        public string Name;
        public int ID;
        public IEnvelope Extent;

        public QueryPointsLayer (IMapLayerInfo layerInfo) {
            this.Name = layerInfo.Name;
            this.ID = layerInfo.ID;
            this.Extent = layerInfo.Extent;
        }

        public JsonObject ToJsonObject () {
            JsonObject result = new JsonObject();
            result.AddString("name", Name);
            result.AddLong("id", ID);
            return result;
        }

        public abstract IEnumerable<JsonObject> Query (IEnumerable<PointQuery> points, string field);
    }

    internal class QueryPointsMosaicLayer : QueryPointsLayer
    {
        IFeatureClass m_catalog;
        IMapTableTimeInfo m_timeInfo;
        Dictionary<int, IRaster2> m_cache;

        public QueryPointsMosaicLayer (IMapLayerInfo layerInfo,
                                       IFeatureClass boundary,
                                       IFeatureClass footprint,
                                       IRaster mosaic)
            : base(layerInfo) {
            m_catalog = footprint;
            m_timeInfo = layerInfo as IMapTableTimeInfo;
            m_cache = new Dictionary<int, IRaster2>();
        }

        override public IEnumerable<JsonObject> Query (IEnumerable<PointQuery> points, string field) {
            LinkedList<JsonObject> results = new LinkedList<JsonObject>();
            List<object> pointResults = new List<object>();
            foreach (PointQuery point in points) {
                pointResults.Clear();
                TimeQueryFilterClass filter = new TimeQueryFilterClass();
                filter.Geometry = point.Geometry;
                filter.SpatialRel = esriSpatialRelEnum.esriSpatialRelIntersects;
                filter.GeometryField = m_catalog.ShapeFieldName;
                filter.WhereClause = point.GetWhereClause(m_timeInfo);
                IFeatureCursor cursor = m_catalog.Search(filter, false);
                IFeature feature;
                while ((feature = cursor.NextFeature()) != null) {
                    IRaster2 raster;
                    if (!m_cache.TryGetValue(feature.OID, out raster)) {
                        IRasterCatalogItem item = feature as IRasterCatalogItem;
                        raster = item.RasterDataset.CreateDefaultRaster() as IRaster2;
                        m_cache.Add(feature.OID, raster);
                    }
                    pointResults.Add(Util.FindValue(raster, 0, point.Geometry));
                }
                results.AddLast(point.ToJson(pointResults));
            }
            return results;
        }
    }

    internal class QueryPointsRasterLayer : QueryPointsLayer
    {
        public QueryPointsRasterLayer (IMapLayerInfo layerInfo, IRaster raster)
            : base(layerInfo) {

        }

        override public IEnumerable<JsonObject> Query (IEnumerable<PointQuery> points, string field) {
            return new JsonObject[] { };
        }
    }

    internal class QueryPointsFeatureClassLayer : QueryPointsLayer
    {
        public QueryPointsFeatureClassLayer (IMapLayerInfo layerInfo, IFeatureClass fc)
            : base(layerInfo) {

        }

        override public IEnumerable<JsonObject> Query (IEnumerable<PointQuery> points, string field) {
            return new JsonObject[] { };
        }
    }
}
