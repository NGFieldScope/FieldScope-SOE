using System;
using System.Collections.Generic;
using ESRI.ArcGIS.Carto;
using ESRI.ArcGIS.DataSourcesRaster;
using ESRI.ArcGIS.esriSystem;
using ESRI.ArcGIS.Geodatabase;
using ESRI.ArcGIS.Geometry;
using ESRI.ArcGIS.Server;
using ESRI.ArcGIS.SOESupport;

namespace NatGeo.FieldScope.SOE
{
    public abstract class FieldScopeSOE : IServerObjectExtension, IObjectConstruct, IRESTRequestHandler, ILogSupport
    {
        private string m_soeName;
        private ILog m_logger;
        private IServerObjectHelper m_serverObjectHelper;
        private IRESTRequestHandler m_reqHandler;

        public string Name {
            get {
                return m_soeName;
            }
        }

        public IServerObjectHelper Helper {
            get {
                return m_serverObjectHelper;
            }
        }

        public FieldScopeSOE () {
            m_soeName = this.GetType().Name;
            m_reqHandler = new SoeRestImpl(m_soeName, CreateRestSchema()) as IRESTRequestHandler;
        }

        public virtual void InitLogging (ILog logger) {
            m_logger = logger;
        }

        public virtual void Init (IServerObjectHelper pSOH) {
            m_serverObjectHelper = pSOH;
        }

        public virtual void Construct (IPropertySet props) {
            LogInfo(m_soeName + " starting up");
        }

        public virtual void Shutdown () {
            LogInfo(m_soeName + " shutting down");
            m_logger = null;
            m_soeName = null;
            m_serverObjectHelper = null;
            m_logger = null;
            m_reqHandler = null;
        }

        public string GetSchema () {
            return m_reqHandler.GetSchema();
        }

        public byte[] HandleRESTRequest (string Capabilities, string resourceName, string operationName, string operationInput, string outputFormat, string requestProperties, out string responseProperties) {
            return m_reqHandler.HandleRESTRequest(Capabilities, resourceName, operationName, operationInput, outputFormat, requestProperties, out responseProperties);
        }

        protected abstract RestResource CreateRestSchema ();

        protected static string DescribeUnits (ISpatialReference sr) {
            if (sr is IProjectedCoordinateSystem) {
                ILinearUnit unit = ((IProjectedCoordinateSystem)sr).CoordinateUnit;
                return String.Format("{0:0.########} m", unit.MetersPerUnit);
            } else if (sr is IGeographicCoordinateSystem) {
                IAngularUnit unit = ((IGeographicCoordinateSystem)sr).CoordinateUnit;
                return String.Format("{0:0.########} rad", unit.RadiansPerUnit);
            }
            return "";
        }

        protected ISpatialReference GetSpatialReferenceParam (JsonObject input, string name) {
            object outSRParam = null;
            if (input.TryGetObject(name, out outSRParam)) {
                ISpatialReferenceFactory factory = new SpatialReferenceEnvironment();
                if (outSRParam is int) {
                    return (factory as ISpatialReferenceFactory2).CreateSpatialReference((int)outSRParam);
                } else if (outSRParam is JsonObject) {
                    JsonObject jsonParam = outSRParam as JsonObject;
                    long? wkid;
                    string wkt;
                    if (jsonParam.TryGetAsLong("wkid", out wkid)) {
                        return (factory as ISpatialReferenceFactory2).CreateSpatialReference((int)wkid.Value);
                    } else if (jsonParam.TryGetString("wkt", out wkt)) {
                        ISpatialReference result;
                        int bytesRead;
                        (factory as ISpatialReferenceFactory3).CreateESRISpatialReference(wkt, out result, out bytesRead);
                        return result;
                    }
                }
            }
            return null;
        }

        protected object GetDataSourceByID (int index) {
            IMapServer3 mapServer = m_serverObjectHelper.ServerObject as IMapServer3;
            if (mapServer == null)
                throw new Exception("Unable to access the map server.");
            IMapServerDataAccess dataAccess = mapServer as IMapServerDataAccess;
            return dataAccess.GetDataSource(mapServer.DefaultMapName, index);
        }

        protected object GetDataSourceByName (string name) {
            IMapServer3 mapServer = m_serverObjectHelper.ServerObject as IMapServer3;
            if (mapServer == null)
                throw new Exception("Unable to access the map server.");
            IMapServerDataAccess dataAccess = (IMapServerDataAccess)mapServer;
            IMapLayerInfos layerInfos = mapServer.GetServerInfo(mapServer.DefaultMapName).MapLayerInfos;
            for (int i = 0; i < layerInfos.Count; i += 1) {
                IMapLayerInfo info = layerInfos.get_Element(i);
                if (info.Name.Equals(name, StringComparison.InvariantCultureIgnoreCase)) {
                    return dataAccess.GetDataSource(mapServer.DefaultMapName, i);
                }
            }
            return null;
        }

        protected IMapLayerInfo[] GetMapLayerInfo () {
            IMapServer3 mapServer = m_serverObjectHelper.ServerObject as IMapServer3;
            if (mapServer == null) {
                throw new Exception("Unable to access the map server.");
            }
            IMapLayerInfos layerInfos = mapServer.GetServerInfo(mapServer.DefaultMapName).MapLayerInfos;
            IMapLayerInfo[] result = new IMapLayerInfo[layerInfos.Count];
            for (int i = 0; i < result.Length; i++) {
                result[i] = layerInfos.get_Element(i);
            }
            return result;
        }

        protected IMapLayerInfo GetMapLayerInfoByID (int layerID) {
            if (layerID < 0) {
                throw new ArgumentOutOfRangeException("layerID");
            }
            IMapServer3 mapServer = m_serverObjectHelper.ServerObject as IMapServer3;
            if (mapServer == null) {
                throw new Exception("Unable to access the map server.");
            }
            IMapLayerInfos layerInfos = mapServer.GetServerInfo(mapServer.DefaultMapName).MapLayerInfos;
            for (int i = 0; i < layerInfos.Count; i++) {
                IMapLayerInfo layerInfo = layerInfos.get_Element(i);
                if (layerInfo.ID == layerID) {
                    return layerInfo;
                }
            }
            throw new ArgumentOutOfRangeException("layerID");
        }

        protected void LogError (string message) {
            m_logger.AddMessage((int)ServerLogger.msgType.error, 8000, message);
        }

        protected void LogWarning (string message) {
            m_logger.AddMessage((int)ServerLogger.msgType.warning, 8000, message);
        }

        protected void LogInfo (string message) {
            m_logger.AddMessage((int)ServerLogger.msgType.infoStandard, 8000, message);
        }

        protected void LogDebug (string message) {
            m_logger.AddMessage((int)ServerLogger.msgType.debug, 8000, message);
        }
    }

    public class Util
    {
        public static object FindValue (IRaster2 raster, int band, IPoint point) {
            int col, row;
            raster.MapToPixel(point.X, point.Y, out col, out row);
            return raster.GetPixelValue(band, col, row);
        }

        public static object FindValue (IFeatureClass fc, string fieldName, IPoint point) {
            object result = null;
            SpatialFilter spatialFilter = new SpatialFilter();
            spatialFilter.Geometry = point;
            spatialFilter.GeometryField = fc.ShapeFieldName;
            spatialFilter.SpatialRel = esriSpatialRelEnum.esriSpatialRelWithin;
            spatialFilter.SubFields = fieldName;
            IFeatureCursor cursor = fc.Search(spatialFilter, false);
            IFeature feature = cursor.NextFeature();
            if (feature != null) {
                result = feature.get_Value(fc.FindField(fieldName)).ToString();
            }
            return result;
        }

        public static IRaster FindRaster (IFeatureClass catalogFC, string name) {
            IQueryFilter queryFilter = new QueryFilterClass();
            queryFilter.WhereClause = "Name = '" + name + "'";
            IFeatureCursor cursor = catalogFC.Search(queryFilter, false);
            IRasterCatalogItem feature = cursor.NextFeature() as IRasterCatalogItem;
            if (feature != null) {
                return feature.RasterDataset.CreateDefaultRaster();
            }
            return null;
        }

        public static ITime ParseUTCMilliseconds (long utcTime) {
            DateTime dateTime = new DateTime(1970, 1, 1, 0, 0, 0, 0);
            dateTime = dateTime.AddMilliseconds(utcTime);
            ITime result = new TimeClass();
            result.Year = (short)dateTime.Year;
            result.Month = (short)dateTime.Month;
            result.Day = (short)dateTime.Day;
            result.Hour = (short)dateTime.Hour;
            result.Minute = (short)dateTime.Minute;
            result.Second = (short)dateTime.Second;
            return result;
        }
    }

    public class Feature
    {
        public Dictionary<string, object> Attributes;
        public IGeometry Geometry;

        public Feature () {
            Attributes = new Dictionary<string, object>();
            Geometry = null;
        }

        public JsonObject ToJsonObject () {
            JsonObject result = new JsonObject();
            if (Attributes.Count > 0) {
                JsonObject attributes = new JsonObject();
                foreach (KeyValuePair<string, object> kvp in Attributes) {
                    attributes.AddObject(kvp.Key, kvp.Value);
                }
                result.AddJsonObject("attributes", attributes);
            }
            if (Geometry != null) {
                result.AddJsonObject("geometry", Conversion.ToJsonObject(Geometry));
            }
            return result;
        }
    }

    public class FeatureSet
    {
        public static string[] FIELD_TYPE_NAMES = { 
            "esriFieldTypeSmallInteger",
            "esriFieldTypeInteger",
            "esriFieldTypeSingle",
            "esriFieldTypeDouble",
            "esriFieldTypeString",
            "esriFieldTypeDate",
            "esriFieldTypeOID",
            "esriFieldTypeGeometry",
            "esriFieldTypeBlob",
            "esriFieldTypeRaster",
            "esriFieldTypeGUID",
            "esriFieldTypeGlobalID",
            "esriFieldTypeXML" 
        };

        public static string[] GEOMETRY_TYPE_NAMES = { 
            "esriGeometryNull",
            "esriGeometryPoint",
            "esriGeometryMultipoint",
            "esriGeometryPolyline",
            "esriGeometryPolygon",
            "esriGeometryEnvelope",
            "esriGeometryPath",
            "esriGeometryAny",
            "esriGeometryMultiPatch",
            "esriGeometryRing",
            "esriGeometryLine",
            "esriGeometryCircularArc",
            "esriGeometryBezier3Curve",
            "esriGeometryEllipticArc",
            "esriGeometryBag",
            "esriGeometryTriangleStrip",
            "esriGeometryTriangleFan",
            "esriGeometryRay",
            "esriGeometrySphere",
            "esriGeometryTriangles"
        };

        public string DisplayFieldName;
        public List<IField> Fields;
        public esriGeometryType GeometryType;
        public ISpatialReference SpatialReference;
        public List<Feature> Features;

        public FeatureSet () {
            DisplayFieldName = null;
            Fields = new List<IField>();
            GeometryType = esriGeometryType.esriGeometryAny;
            SpatialReference = null;
            Features = new List<Feature>();
        }

        public JsonObject ToJsonObject () {
            JsonObject result = new JsonObject();
            if (DisplayFieldName != null) {
                result.AddString("displayFieldName", DisplayFieldName);
            }
            if (Fields.Count > 0) {
                JsonObject[] fields = new JsonObject[Fields.Count];
                for (int i = 0; i < Fields.Count; i += 1) {
                    IField field = Fields[i];
                    JsonObject fjson = new JsonObject();
                    fjson.AddString("name", field.Name);
                    fjson.AddString("alias", field.AliasName);
                    fjson.AddString("type", FIELD_TYPE_NAMES[(int)field.Type]);
                    fjson.AddLong("length", field.Length);
                    fields[i] = fjson;
                }
                result.AddArray("fields", fields);
            }
            if (GeometryType != esriGeometryType.esriGeometryAny) {
                result.AddString("geometryType", GEOMETRY_TYPE_NAMES[(int)GeometryType]);
            }
            if (Features.Count > 0) {
                JsonObject[] features = new JsonObject[Features.Count];
                for (int i = 0; i < Features.Count; i += 1) {
                    features[i] = Features[i].ToJsonObject();
                }
                result.AddArray("features", features);
            }
            return result;
        }
    }

    public struct IntPoint
    {
        public Int32 X;
        public Int32 Y;

        public IntPoint (Int32 x, Int32 y) {
            this.X = x;
            this.Y = y;
        }

        override public int GetHashCode () {
            return X ^ Y;
        }

        public override bool Equals (object obj) {
            return (obj is IntPoint) &&
                   ((IntPoint)obj).X == X &&
                   ((IntPoint)obj).Y == Y;
        }
    }

    internal enum CurveDirection { RIGHT, UP, LEFT, DOWN };

    //
    // Translated from BoundingCurve.java in the My World GIS codebase
    //
    internal sealed class BoundingCurve
    {
        private int m_Width;
        private int m_Height;
        private bool[,] m_IsFilled;
        private bool[,] m_CellVisited;

        public BoundingCurve (bool[,] grid) {
            m_IsFilled = grid;
            m_Width = grid.GetLength(0);
            m_Height = grid.GetLength(1);
            m_CellVisited = new bool[m_Width, m_Height];
            m_CellVisited.Initialize();
        }

        public List<List<IntPoint>> GetBoundaryAsList () {
            List<List<IntPoint>> result = new List<List<IntPoint>>();
            for (int x = 0; x <= m_Width; x += 1) {
                for (int y = 0; y <= m_Height; y += 1) {
                    if ((!CellVisited(x, y)) && IsFilled(x, y) && (!IsFilled(x - 1, y))) {
                        result.Add(GetCurve(x, y));
                    }
                }
            }
            return result;
        }

        public IPolygon GetBoundaryAsPolygon (IRasterProps props) {
            double left = props.Extent.XMin;
            double top = props.Extent.YMax;
            IPnt cellSize = props.MeanCellSize();
            PolygonClass result = new PolygonClass();
            object missing = Type.Missing;
            for (int x = 0; x <= m_Width; x += 1) {
                for (int y = 0; y <= m_Height; y += 1) {
                    if ((!CellVisited(x, y)) && IsFilled(x, y) && (!IsFilled(x - 1, y))) {
                        List<IntPoint> bmRing = GetCurve(x, y);
                        RingClass ring = new RingClass();
                        foreach (IntPoint bmPt in bmRing) {
                            PointClass point = new PointClass();
                            point.PutCoords(left + bmPt.X * cellSize.X,
                                            top - bmPt.Y * cellSize.Y);
                            ring.AddPoint(point, ref missing, ref missing);
                        }
                        result.AddGeometry(ring, ref missing, ref missing);
                    }
                }
            }
            return result;
        }

        private bool IsFilled (int x, int y) {
            if ((x < 0) || (y < 0) || (x >= m_Width) || (y >= m_Height)) {
                return false;
            } else {
                return m_IsFilled[x, y];
            }
        }

        private bool CellVisited (int x, int y) {
            if ((x < 0) || (y < 0) || (x >= m_Width) || (y >= m_Height)) {
                return false;
            } else {
                return m_CellVisited[x, y];
            }
        }

        private void MarkVisited (int x, int y) {
            if ((x >= 0) && (x < m_Width) && (y >= 0) && (y < m_Height)) {
                m_CellVisited[x, y] = true;
            }
        }

        private List<IntPoint> GetCurve (int startX, int startY) {
            MarkVisited(startX, startY);
            List<IntPoint> result = new List<IntPoint>();
            result.Add(new IntPoint(startX, startY));
            int x = startX;
            int y = startY + 1;
            CurveDirection direction = CurveDirection.UP;
            while ((x != startX) || (y != startY)) {
                CurveDirection newDirection = direction;
                switch (direction) {
                    case CurveDirection.UP:
                        if (IsFilled(x - 1, y) && IsFilled(x, y)) {
                            newDirection = CurveDirection.LEFT;
                            MarkVisited(x - 1, y);
                        } else if (IsFilled(x, y)) {
                            newDirection = CurveDirection.UP;
                            MarkVisited(x, y);
                        } else {
                            newDirection = CurveDirection.RIGHT;
                        }
                        break;
                    case CurveDirection.RIGHT:
                        if (IsFilled(x, y) && IsFilled(x, y - 1)) {
                            newDirection = CurveDirection.UP;
                            MarkVisited(x, y);
                        } else if (IsFilled(x, y - 1)) {
                            newDirection = CurveDirection.RIGHT;
                            MarkVisited(x, y - 1);
                        } else {
                            newDirection = CurveDirection.DOWN;
                        }
                        break;
                    case CurveDirection.DOWN:
                        if (IsFilled(x, y - 1) && IsFilled(x - 1, y - 1)) {
                            newDirection = CurveDirection.RIGHT;
                            MarkVisited(x, y - 1);
                        } else if (IsFilled(x - 1, y - 1)) {
                            newDirection = CurveDirection.DOWN;
                            if ((x < 2) || IsFilled(x - 2, y - 1))
                                MarkVisited(x - 1, y - 1);
                        } else {
                            newDirection = CurveDirection.LEFT;
                        }
                        break;
                    case CurveDirection.LEFT:
                        if (IsFilled(x - 1, y - 1) && IsFilled(x - 1, y)) {
                            newDirection = CurveDirection.DOWN;
                            if ((x < 2) || IsFilled(x - 2, y - 1))
                                MarkVisited(x - 1, y - 1);
                        } else if (IsFilled(x - 1, y)) {
                            newDirection = CurveDirection.LEFT;
                            MarkVisited(x - 1, y);
                        } else {
                            newDirection = CurveDirection.UP;
                            MarkVisited(x, y);
                        }
                        break;
                    default:
                        throw (new Exception("this shouldn't happen"));
                }

                result.Add(new IntPoint(x, y));

                switch (newDirection) {
                    case CurveDirection.UP:
                        y += 1;
                        break;
                    case CurveDirection.RIGHT:
                        x += 1;
                        break;
                    case CurveDirection.DOWN:
                        y -= 1;
                        break;
                    case CurveDirection.LEFT:
                        x -= 1;
                        break;
                }
                direction = newDirection;
            }

            // Be absolutely sure the curve is closed
            if (!result[0].Equals(result[result.Count - 1])) {
                result.Add(new IntPoint(startX, startY));
            }

            result.Reverse();
            return result;
        }
    }
}