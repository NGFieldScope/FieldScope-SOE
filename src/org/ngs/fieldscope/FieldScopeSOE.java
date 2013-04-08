package org.ngs.fieldscope;

import com.esri.arcgis.carto.IMapLayerInfo;
import com.esri.arcgis.carto.IMapLayerInfos;
import com.esri.arcgis.carto.IMapServer3;
import com.esri.arcgis.carto.IMapServerDataAccess;
import com.esri.arcgis.datasourcesraster.IRaster2;
import com.esri.arcgis.datasourcesraster.IRasterProps;
import com.esri.arcgis.datasourcesraster.Raster;
import com.esri.arcgis.geodatabase.IFeature;
import com.esri.arcgis.geodatabase.IFeatureClass;
import com.esri.arcgis.geodatabase.IFeatureCursor;
import com.esri.arcgis.geodatabase.IField;
import com.esri.arcgis.geodatabase.IPnt;
import com.esri.arcgis.geodatabase.IQueryFilter;
import com.esri.arcgis.geodatabase.IRaster;
import com.esri.arcgis.geodatabase.IRasterCatalogItem;
import com.esri.arcgis.geodatabase.QueryFilter;
import com.esri.arcgis.geodatabase.SpatialFilter;
import com.esri.arcgis.geodatabase.esriSpatialRelEnum;
import com.esri.arcgis.geometry.IAngularUnit;
import com.esri.arcgis.geometry.IGeographicCoordinateSystem;
import com.esri.arcgis.geometry.IGeometry;
import com.esri.arcgis.geometry.ILinearUnit;
import com.esri.arcgis.geometry.IPoint;
import com.esri.arcgis.geometry.IPolygon;
import com.esri.arcgis.geometry.IProjectedCoordinateSystem;
import com.esri.arcgis.geometry.ISpatialReference;
import com.esri.arcgis.geometry.ISpatialReferenceFactory;
import com.esri.arcgis.geometry.ISpatialReferenceFactory2;
import com.esri.arcgis.geometry.ISpatialReferenceFactory3;
import com.esri.arcgis.geometry.Point;
import com.esri.arcgis.geometry.Polygon;
import com.esri.arcgis.geometry.Polyline;
import com.esri.arcgis.geometry.Ring;
import com.esri.arcgis.geometry.SpatialReferenceEnvironment;
import com.esri.arcgis.geometry.esriGeometryType;
import com.esri.arcgis.interop.AutomationException;
import com.esri.arcgis.server.IServerObjectExtension;
import com.esri.arcgis.server.IServerObjectHelper;
import com.esri.arcgis.server.json.JSONObject;
import com.esri.arcgis.system.ILog;
import com.esri.arcgis.system.IObjectConstruct;
import com.esri.arcgis.system.IPropertySet;
import com.esri.arcgis.system.IRESTRequestHandler;
import com.esri.arcgis.system.ITime;
import com.esri.arcgis.system.ServerUtilities;
import com.esri.arcgis.system.Time;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public abstract class FieldScopeSOE implements IServerObjectExtension, IObjectConstruct, IRESTRequestHandler
{
    private static final long serialVersionUID = -7685924714300124813L;
    
    private String m_soeName;
    private ILog m_logger;
    private IServerObjectHelper m_serverObjectHelper;

    public String getName () {
        return m_soeName;
    }

    public IServerObjectHelper getHelper () {
        return m_serverObjectHelper;
    }

    public FieldScopeSOE () {
        m_soeName = getClass().getSimpleName();
    }
    
    public void init (IServerObjectHelper pSOH) throws IOException, AutomationException {
        m_logger = ServerUtilities.getServerLogger();
        logInfo(m_soeName + " initialized");
        m_serverObjectHelper = pSOH;
    }

    public void construct (IPropertySet propertySet) throws IOException, AutomationException {
        logInfo(m_soeName + " starting up");
    }
    
    public void shutdown() throws IOException, AutomationException {
        logInfo(m_soeName + " shutting down");
        m_soeName = null;
        m_serverObjectHelper = null;
        m_logger = null;
    }
    
    public byte[] handleRESTRequest(String capabilities, 
                                    String resourceName, 
                                    String operationName, 
                                    String operationInput, 
                                    String outputFormat,
                                    String requestProperties, 
                                    String[] responseProperties) throws IOException, AutomationException {
        try {
            // if no operationName is specified send description of specified
            // resource
            byte[] response;
            if (operationName.length() == 0) {
                response = getResource(resourceName);
            } else {
                JSONObject operationInputJSON = new JSONObject(operationInput);
                Map<String, String> responsePropertiesMap = new HashMap<String, String>();
                response = invokeRESTOperation(resourceName, operationName, operationInputJSON, outputFormat, responsePropertiesMap);
                JSONObject responsePropertiesJSON = new JSONObject(responsePropertiesMap);
                responseProperties[0] = responsePropertiesJSON.toString();
            }
            return response;
        } catch (Exception e) {
            String message = "Exception occurred while handling REST request for SOE " + this.getClass().getName() + ":" + e.getMessage();
            logError(message);
            return ServerUtilities.sendError(0, message, null).getBytes("utf-8");
        }
    }
    
    protected abstract byte[] getResource (String resourceName) throws Exception;
    
    protected abstract byte[] invokeRESTOperation(String resourceName, 
                                                  String operationName, 
                                                  JSONObject operationInput,
                                                  String outputFormat,
                                                  Map<String, String> responsePropertiesMap) throws Exception;
    
    protected static String describeUnits (ISpatialReference sr) throws IOException {
        if (sr instanceof IProjectedCoordinateSystem) {
            ILinearUnit unit = ((IProjectedCoordinateSystem)sr).getCoordinateUnit();
            return MessageFormat.format("{0,number,0.########} m", unit.getMetersPerUnit());
        } else if (sr instanceof IGeographicCoordinateSystem) {
            IAngularUnit unit = ((IGeographicCoordinateSystem)sr).getCoordinateUnit();
            return MessageFormat.format("{0,number,0.########} rad", unit.getRadiansPerUnit());
        }
        return "";
    }

    protected ISpatialReference getSpatialReferenceParam (JSONObject input, String name) throws IOException {
        Object outSRParam = input.opt(name);
        if (outSRParam != null) {
            ISpatialReferenceFactory factory = new SpatialReferenceEnvironment();
            if (outSRParam instanceof Integer) {
                return ((ISpatialReferenceFactory2)factory).createSpatialReference((Integer)outSRParam);
            } else if (outSRParam instanceof JSONObject) {
                JSONObject jsonParam = (JSONObject)outSRParam;
                int wkid = jsonParam.optInt("wkid", -1);
                String wkt = jsonParam.optString("wkt", null);
                if (wkid > 0) {
                    return ((ISpatialReferenceFactory2)factory).createSpatialReference(wkid);
                } else if (wkt != null) {
                    ISpatialReference[] result = { null };
                    int[] bytesRead = { 0 };
                    ((ISpatialReferenceFactory3)factory).createESRISpatialReference(wkt, result, bytesRead);
                    return result[0];
                }
            }
        }
        return null;
    }

    protected Object getDataSourceByID (int index) throws IOException {
        IMapServer3 mapServer = (IMapServer3)m_serverObjectHelper.getServerObject();
        if (mapServer == null) {
            throw new IOException("Unable to access the map server.");
        }
        IMapServerDataAccess dataAccess = (IMapServerDataAccess)mapServer;
        return dataAccess.getDataSource(mapServer.getDefaultMapName(), index);
    }

    protected Object getDataSourceByName (String name) throws IOException {
        IMapServer3 mapServer = (IMapServer3)m_serverObjectHelper.getServerObject();
        if (mapServer == null) {
            throw new IOException("Unable to access the map server.");
        }
        IMapServerDataAccess dataAccess = (IMapServerDataAccess)mapServer;
        IMapLayerInfos layerInfos = mapServer.getServerInfo(mapServer.getDefaultMapName()).getMapLayerInfos();
        for (int i = 0; i < layerInfos.getCount(); i += 1) {
            IMapLayerInfo info = layerInfos.getElement(i);
            if (info.getName().equalsIgnoreCase(name)) {
                return dataAccess.getDataSource(mapServer.getDefaultMapName(), i);
            }
        }
        return null;
    }

    protected IMapLayerInfo[] getMapLayerInfo () throws IOException {
        IMapServer3 mapServer = (IMapServer3)m_serverObjectHelper.getServerObject();
        if (mapServer == null) {
            throw new IOException("Unable to access the map server.");
        }
        IMapLayerInfos layerInfos = mapServer.getServerInfo(mapServer.getDefaultMapName()).getMapLayerInfos();
        IMapLayerInfo[] result = new IMapLayerInfo[layerInfos.getCount()];
        for (int i = 0; i < result.length; i++) {
            result[i] = layerInfos.getElement(i);
        }
        return result;
    }

    protected IMapLayerInfo getMapLayerInfoByID (int layerID) throws IOException {
        if (layerID < 0) {
            throw new ArrayIndexOutOfBoundsException(layerID);
        }
        IMapServer3 mapServer = (IMapServer3)m_serverObjectHelper.getServerObject();
        if (mapServer == null) {
            throw new IOException("Unable to access the map server.");
        }
        IMapLayerInfos layerInfos = mapServer.getServerInfo(mapServer.getDefaultMapName()).getMapLayerInfos();
        for (int i = 0; i < layerInfos.getCount(); i++) {
            IMapLayerInfo layerInfo = layerInfos.getElement(i);
            if (layerInfo.getID() == layerID) {
                return layerInfo;
            }
        }
        throw new ArrayIndexOutOfBoundsException(layerID);
    }

    protected void logError (String message) {
        log(1, message);
    }

    protected void logWarning (String message) {
        log(2, message);
    }

    protected void logInfo (String message) {
        log(3, message);
    }

    protected void logDebug (String message) {
        log(5, message);
    }

    private void log (int level, String message) {
        if (m_logger != null) {
            try {
                m_logger.addMessage(level, 8000, message);
            } catch (IOException e) {
                // Who the hell throws an error from a logging command? 
                // WTF am I supposed to do if I catch one? Try logging it 
                // again?
            }
        }
    }
    
    public static class Util
    {
        public static Object findValue (IRaster2 raster, int band, IPoint point) throws IOException {
            int[] col = { -1 }, row = { -1 };
            raster.mapToPixel(point.getX(), point.getY(), col, row);
            return raster.getPixelValue(band, col[0], row[0]);
        }

        public static Object findValue (IFeatureClass fc, String fieldName, IPoint point) throws IOException {
            Object result = null;
            SpatialFilter spatialFilter = new SpatialFilter();
            spatialFilter.setGeometryByRef(point);
            spatialFilter.setGeometryField(fc.getShapeFieldName());
            spatialFilter.setSpatialRel(esriSpatialRelEnum.esriSpatialRelWithin);
            spatialFilter.setSubFields(fieldName);
            IFeatureCursor cursor = fc.search(spatialFilter, false);
            IFeature feature = cursor.nextFeature();
            if (feature != null) {
                result = feature.getValue(fc.findField(fieldName)).toString();
            }
            return result;
        }
        
        @SuppressWarnings("deprecation")
        public static IRaster findRaster (IFeatureClass catalogFC, String name) throws IOException {
            IQueryFilter queryFilter = new QueryFilter();
            queryFilter.setWhereClause("Name = '" + name + "'");
            IFeatureCursor cursor = catalogFC.search(queryFilter, false);
            IRasterCatalogItem feature = (IRasterCatalogItem)cursor.nextFeature();
            if (feature != null) {
                // Raster(Object) is deprecated, but no alternative currently exists
                return new Raster(feature.getRasterDataset().createDefaultRaster());
            }
            return null;
        }

        @SuppressWarnings("deprecation")
        public static ITime parseUTCMilliseconds (long utcTime) throws IOException {
            Date dateTime = new Date(utcTime);
            ITime result = new Time();
            result.setYear((short)dateTime.getYear());
            result.setMonth((short)dateTime.getMonth());
            result.setDay((short)dateTime.getDay());
            result.setHour((short)dateTime.getHours());
            result.setMinute((short)dateTime.getMinutes());
            result.setSecond((short)dateTime.getSeconds());
            return result;
        }
    }

    public static class Feature
    {
        public Map<String, Object> attributes;
        public IGeometry geometry;

        public Feature () {
            attributes = new HashMap<String, Object>();
            geometry = null;
        }

        public JSONObject toJsonObject () throws Exception {
            JSONObject result = new JSONObject();
            if (attributes.size() > 0) {
                JSONObject attributesJson = new JSONObject();
                for (Map.Entry<String, Object> kvp : attributes.entrySet()) {
                    attributesJson.put(kvp.getKey(), kvp.getValue());
                }
                result.put("attributes", attributesJson);
            }
            if (geometry != null) {
                JSONObject geometryJson = null;
                if (geometry instanceof Polygon) {
                    geometryJson = ServerUtilities.getJSONFromPolygon((Polygon)geometry);
                } else if (geometry instanceof Polyline) {
                    geometryJson = ServerUtilities.getJSONFromPolyline((Polyline)geometry);
                } else if (geometry instanceof Point) {
                    geometryJson = ServerUtilities.getJSONFromPoint((Point)geometry);
                } else {
                    geometryJson = ServerUtilities.getJSONFromGeometry(geometry);
                }
                result.put("geometry", geometryJson);
            }
            return result;
        }
    }

    public static class FeatureSet
    {
        public static String[] FIELD_TYPE_NAMES = { 
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

        public static String[] GEOMETRY_TYPE_NAMES = { 
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

        public String displayFieldName;
        public List<IField> fields;
        public int geometryType;
        public ISpatialReference spatialReference;
        public List<Feature> features;

        public FeatureSet () {
            displayFieldName = null;
            fields = new ArrayList<IField>();
            geometryType = esriGeometryType.esriGeometryAny;
            spatialReference = null;
            features = new ArrayList<Feature>();
        }

        public JSONObject toJsonObject () throws Exception, IOException {
            JSONObject result = new JSONObject();
            if (displayFieldName != null) {
                result.put("displayFieldName", displayFieldName);
            }
            if (fields.size() > 0) {
                JSONObject[] fieldsJson = new JSONObject[fields.size()];
                for (int i = 0; i < fields.size(); i += 1) {
                    IField field = fields.get(i);
                    JSONObject fjson = new JSONObject();
                    fjson.put("name", field.getName());
                    fjson.put("alias", field.getAliasName());
                    fjson.put("type", FIELD_TYPE_NAMES[(int)field.getType()]);
                    fjson.put("length", field.getLength());
                    fieldsJson[i] = fjson;
                }
                result.put("fields", fieldsJson);
            }
            if (geometryType != esriGeometryType.esriGeometryAny) {
                result.put("geometryType", GEOMETRY_TYPE_NAMES[geometryType]);
            }
            if (features.size() > 0) {
                List<JSONObject> featuresJson = new ArrayList<JSONObject>(features.size());
                for (int i = 0; i < features.size(); i += 1) {
                    featuresJson.add(features.get(i).toJsonObject());
                }
                result.put("features", featuresJson);
            }
            return result;
        }
    }

    public static class IntPoint
    {
        public int x;
        public int y;

        public IntPoint (int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override 
        public int hashCode () {
            return x ^ y;
        }
        
        @Override
        public boolean equals (Object obj) {
            return (obj instanceof IntPoint) &&
                   ((IntPoint)obj).x == x &&
                   ((IntPoint)obj).y == y;
        }
    }

    static enum CurveDirection { RIGHT, UP, LEFT, DOWN };

    //
    // Translated from BoundingCurve.java in the My World GIS codebase
    //
    protected final class BoundingCurve
    {
        private int m_width;
        private int m_height;
        private boolean[][] m_isFilled;
        private boolean[][] m_cellVisited;

        public BoundingCurve (boolean[][] grid) {
            m_isFilled = grid;
            m_width = grid.length;
            m_height = grid[0].length;
            m_cellVisited = new boolean[m_width][m_height];
        }

        public List<List<IntPoint>> getBoundaryAsList () {
            List<List<IntPoint>> result = new ArrayList<List<IntPoint>>();
            for (int x = 0; x <= m_width; x += 1) {
                for (int y = 0; y <= m_height; y += 1) {
                    if ((!cellVisited(x, y)) && isFilled(x, y) && (!isFilled(x - 1, y))) {
                        result.add(getCurve(x, y));
                    }
                }
            }
            return result;
        }

        public IPolygon getBoundaryAsPolygon (IRasterProps props) throws IOException {
            double left = props.getExtent().getXMin();
            double top = props.getExtent().getYMax();
            IPnt cellSize = props.meanCellSize();
            Polygon result = new Polygon();
            for (int x = 0; x <= m_width; x += 1) {
                for (int y = 0; y <= m_height; y += 1) {
                    if ((!cellVisited(x, y)) && isFilled(x, y) && (!isFilled(x - 1, y))) {
                        List<IntPoint> bmRing = getCurve(x, y);
                        Ring ring = new Ring();
                        for (IntPoint bmPt : bmRing) {
                            Point point = new Point();
                            point.putCoords(left + bmPt.x * cellSize.getX(),
                                            top - bmPt.y * cellSize.getY());
                            ring.addPoint(point, null, null);
                        }
                        result.addGeometry(ring, null, null);
                    }
                }
            }
            return result;
        }

        private boolean isFilled (int x, int y) {
            if ((x < 0) || (y < 0) || (x >= m_width) || (y >= m_height)) {
                return false;
            } else {
                return m_isFilled[x][y];
            }
        }

        private boolean cellVisited (int x, int y) {
            if ((x < 0) || (y < 0) || (x >= m_width) || (y >= m_height)) {
                return false;
            } else {
                return m_cellVisited[x][y];
            }
        }

        private void markVisited (int x, int y) {
            if ((x >= 0) && (x < m_width) && (y >= 0) && (y < m_height)) {
                m_cellVisited[x][y] = true;
            }
        }

        private List<IntPoint> getCurve (int startX, int startY) {
            markVisited(startX, startY);
            List<IntPoint> result = new ArrayList<IntPoint>();
            result.add(new IntPoint(startX, startY));
            int x = startX;
            int y = startY + 1;
            CurveDirection direction = CurveDirection.UP;
            while ((x != startX) || (y != startY)) {
                CurveDirection newDirection = direction;
                switch (direction) {
                    case UP:
                        if (isFilled(x - 1, y) && isFilled(x, y)) {
                            newDirection = CurveDirection.LEFT;
                            markVisited(x - 1, y);
                        } else if (isFilled(x, y)) {
                            newDirection = CurveDirection.UP;
                            markVisited(x, y);
                        } else {
                            newDirection = CurveDirection.RIGHT;
                        }
                        break;
                    case RIGHT:
                        if (isFilled(x, y) && isFilled(x, y - 1)) {
                            newDirection = CurveDirection.UP;
                            markVisited(x, y);
                        } else if (isFilled(x, y - 1)) {
                            newDirection = CurveDirection.RIGHT;
                            markVisited(x, y - 1);
                        } else {
                            newDirection = CurveDirection.DOWN;
                        }
                        break;
                    case DOWN:
                        if (isFilled(x, y - 1) && isFilled(x - 1, y - 1)) {
                            newDirection = CurveDirection.RIGHT;
                            markVisited(x, y - 1);
                        } else if (isFilled(x - 1, y - 1)) {
                            newDirection = CurveDirection.DOWN;
                            if ((x < 2) || isFilled(x - 2, y - 1))
                                markVisited(x - 1, y - 1);
                        } else {
                            newDirection = CurveDirection.LEFT;
                        }
                        break;
                    case LEFT:
                        if (isFilled(x - 1, y - 1) && isFilled(x - 1, y)) {
                            newDirection = CurveDirection.DOWN;
                            if ((x < 2) || isFilled(x - 2, y - 1))
                                markVisited(x - 1, y - 1);
                        } else if (isFilled(x - 1, y)) {
                            newDirection = CurveDirection.LEFT;
                            markVisited(x - 1, y);
                        } else {
                            newDirection = CurveDirection.UP;
                            markVisited(x, y);
                        }
                        break;
                    default:
                        throw new RuntimeException("this shouldn't happen");
                }

                result.add(new IntPoint(x, y));

                switch (newDirection) {
                    case UP:
                        y += 1;
                        break;
                    case RIGHT:
                        x += 1;
                        break;
                    case DOWN:
                        y -= 1;
                        break;
                    case LEFT:
                        x -= 1;
                        break;
                }
                direction = newDirection;
            }

            // Be absolutely sure the curve is closed
            if (!result.get(0).equals(result.get(result.size() - 1))) {
                result.add(new IntPoint(startX, startY));
            }

            Collections.reverse(result);
            return result;
        }
    }
}
