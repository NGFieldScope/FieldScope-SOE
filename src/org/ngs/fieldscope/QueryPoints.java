package org.ngs.fieldscope;

import com.esri.arcgis.carto.IMapLayerInfo;
import com.esri.arcgis.carto.IMapTableTimeInfo;
import com.esri.arcgis.carto.IMapTableTimeInfoProxy;
import com.esri.arcgis.carto.TimeQueryFilter;
import com.esri.arcgis.datasourcesraster.IRaster2;
import com.esri.arcgis.datasourcesraster.Raster;
import com.esri.arcgis.geodatabase.FeatureClass;
import com.esri.arcgis.geodatabase.IFeature;
import com.esri.arcgis.geodatabase.IFeatureClass;
import com.esri.arcgis.geodatabase.IFeatureCursor;
import com.esri.arcgis.geodatabase.IRaster;
import com.esri.arcgis.geodatabase.IRasterCatalogItem;
import com.esri.arcgis.geodatabase.IRasterCatalogItemProxy;
import com.esri.arcgis.geodatabase.SpatialFilter;
import com.esri.arcgis.geodatabase.esriSpatialRelEnum;
import com.esri.arcgis.geometry.IEnvelope;
import com.esri.arcgis.geometry.IPoint;
import com.esri.arcgis.geometry.Point;
import com.esri.arcgis.interop.AutomationException;
import com.esri.arcgis.interop.extn.ArcGISExtension;
import com.esri.arcgis.interop.extn.ServerObjectExtProperties;
import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONException;
import com.esri.arcgis.server.json.JSONObject;
import com.esri.arcgis.system.IPropertySet;
import com.esri.arcgis.system.ITimeExtent;
import com.esri.arcgis.system.ITimeInstant;
import com.esri.arcgis.system.ITimeValue;
import com.esri.arcgis.system.ServerUtilities;
import com.esri.arcgis.system.TimeExtent;
import com.esri.arcgis.system.TimeInstant;
import com.esri.arcgis.system.esriTimeStringFormat;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ArcGISExtension
@ServerObjectExtProperties(displayName = "QueryPoints", 
                           description = "Query map layer at multiple points")
public class QueryPoints extends FieldScopeSOE 
{
    private static final long serialVersionUID = 3407992158163980844L;
    
    private Map<Integer, QueryPointsLayer> m_layers;

    @Override
    @SuppressWarnings("deprecation")
    public void construct(IPropertySet propertySet) throws IOException, AutomationException {
        super.construct(propertySet);
        m_layers = new TreeMap<Integer, QueryPointsLayer>();
        IMapLayerInfo[] layers = getMapLayerInfo();
        for (int i = 0; i < layers.length; ) {
            if (layers[i].getType().equals("Raster Layer")) {
                m_layers.put(Integer.valueOf(layers[i].getID()), 
                             new QueryPointsRasterLayer(
                                 layers[i],
                                 new Raster(getDataSourceByID(layers[i].getID()))));
                i += 1;
            } else if (layers[i].getType().equals("Feature Layer")) {
                m_layers.put(Integer.valueOf(layers[i].getID()), 
                             new QueryPointsFeatureClassLayer(
                                 layers[i],
                                 new FeatureClass(getDataSourceByID(layers[i].getID()))));
                i += 1;
            } else if (layers[i].getType().equals("Mosaic Layer")) {
                m_layers.put(Integer.valueOf(layers[i].getID()), 
                             new QueryPointsMosaicLayer(
                                 layers[i],
                                 new FeatureClass(getDataSourceByID(layers[i].getID() + 1)),
                                 new FeatureClass(getDataSourceByID(layers[i].getID() + 2)),
                                 new Raster(getDataSourceByID(layers[i].getID() + 3))));
                i += 4;
            } else {
                i += 1;
            }
        }
    }
    
    @Override 
    public void shutdown () throws IOException, AutomationException {
        super.shutdown();
        m_layers = null;
    }
    
    public String getSchema() throws IOException, AutomationException {
        JSONObject result = ServerUtilities.createResource("QueryPoints", "Query map layer at multiple points", false, false);
        JSONArray resources = new JSONArray();
        JSONObject layers = ServerUtilities.createResource("layers", "Queryable layers in this map service", true, false);
        JSONArray operations = new JSONArray();
        operations.put(ServerUtilities.createOperation("queryPoints", "points, outField", "json", false));
        layers.put("operations", operations);
        resources.put(layers);
        result.put("resources", resources);
        return result.toString();
    }
    
    protected byte[] getResource (String resourceName) throws Exception {
        if (resourceName.equalsIgnoreCase("") || resourceName.length() == 0) {
            JSONObject json = new JSONObject();
            json.put("service", "QueryPoints");
            json.put("description", "Query map layer at multiple points");
            JSONArray layers = new JSONArray();
            for (QueryPointsLayer layer : m_layers.values()) {
                layers.put(layer.toJsonObject());
            }
            json.put("layers", layers);
            return json.toString().getBytes("utf-8");
        }
        Pattern lp = Pattern.compile("layers/(\\w+)");
        Matcher m = lp.matcher(resourceName);
        if (m.matches()) {
            Integer layerId = Integer.valueOf(m.group(1).trim());
            QueryPointsLayer layer = m_layers.get(layerId);
            if (layer != null) {
                return layer.toJsonObject().toString().getBytes("utf-8");
            }
        }
        return null;
    }
    
    protected byte[] invokeRESTOperation(String resourceName, 
                                         String operationName, 
                                         JSONObject operationInput,
                                         String outputFormat,
                                         Map<String, String> responsePropertiesMap) throws Exception {
        byte[] operationOutput = null;
        Pattern lp = Pattern.compile("layers/(\\w+)");
        Matcher m = lp.matcher(resourceName);
        if (operationName.equalsIgnoreCase("queryPoints") && m.matches()) {
            Integer layerId = Integer.valueOf(m.group(1).trim());
            JSONArray pointsJson = operationInput.getJSONArray("points");
            List<PointQuery> points = new ArrayList<PointQuery>(pointsJson.length());
            for (int i = 0; i < pointsJson.length(); i += 1) {
                points.add(new PointQuery(pointsJson.getJSONObject(i)));
            }
            String outField = operationInput.optString("outField");
            JSONObject result = new JSONObject();
            result.put("results", m_layers.get(layerId).query(points, outField));
            operationOutput = result.toString().getBytes("utf-8");
        }
        return operationOutput;
    }
    
    private static class PointQuery
    {
        public final IPoint geometry;
        public final String id;
        public final ITimeValue time;
    
        public PointQuery (JSONObject json) throws IOException {
            geometry = new Point();
            geometry.setX(json.getDouble("x"));
            geometry.setY(json.getDouble("y"));
            id = json.optString("id");
            ITimeValue timeValue = null;
            try {
                long tstamp = json.getLong("t");
                ITimeInstant instant = new TimeInstant();
                instant.setTime(Util.parseUTCMilliseconds(tstamp));
                timeValue = instant;
            } catch (JSONException e) {
                String timeStr = json.optString("t");
                if ((timeStr != null) && (timeStr.length() > 0)) {
                    if (timeStr.indexOf(',') >= 0) {
                        String[] times = timeStr.split(",");
                        ITimeExtent extent = new TimeExtent();
                        extent.setStartTime(Util.parseUTCMilliseconds(Long.valueOf(times[0])));
                        extent.setEndTime(Util.parseUTCMilliseconds(Long.valueOf(times[1])));
                        timeValue = extent;
                    } else {
                        ITimeInstant instant = new TimeInstant();
                        instant.setTime(Util.parseUTCMilliseconds(Long.valueOf(timeStr)));
                        timeValue = instant;
                    }
                }
            }
            time = timeValue;
        }
    
        public String getWhereClause (IMapTableTimeInfo timeInfo) throws IOException {
            if ((time == null) || (!timeInfo.isSupportsTime())) {
                return null;
            } else if (time instanceof ITimeExtent) {
                ITimeExtent extent = (ITimeExtent)time;
                return MessageFormat.format("{0} >= date '{2}' AND {1} <= date '{3}'",
                                            timeInfo.getStartTimeFieldName(),
                                            timeInfo.getEndTimeFieldName(),
                                            extent.getStartTime().queryTimeString(esriTimeStringFormat.esriTSFYearThruSecondWithDash),
                                            extent.getEndTime().queryTimeString(esriTimeStringFormat.esriTSFYearThruSecondWithDash));
            } else if (time instanceof ITimeInstant) {
                ITimeInstant instant = (ITimeInstant)time;
                if (timeInfo.getEndTimeFieldName() != null) {
                    return MessageFormat.format("{0} <= date '{2}' AND {1} > date '{2}'",
                                                timeInfo.getStartTimeFieldName(),
                                                timeInfo.getEndTimeFieldName(),
                                                instant.getTime().queryTimeString(esriTimeStringFormat.esriTSFYearThruSecondWithDash));
                } else {
                    return MessageFormat.format("{0} = date '{1}'",
                                                timeInfo.getStartTimeFieldName(),
                                                instant.getTime().queryTimeString(esriTimeStringFormat.esriTSFYearThruSecondWithDash));
                }
            }
            return null;
        }
    
        public JSONObject toJson (Object values) {
            JSONObject result = new JSONObject();
            if (id != null) {
                result.put("id", id);
            }
            if (values instanceof List<?>) {
                List<Object> list = (List<Object>)values;
                if (list.size() == 0) {
                    result.put("result", (JSONObject)null);
                } else if (list.size() == 1) {
                    result.put("result", list.get(0));
                } else {
                    result.put("result", list.toArray());
                }
            } else {
                result.put("result", values);
            }
            return result;
        }
    }
    
    private static abstract class QueryPointsLayer
    {
        public String name;
        public int id;
        public IEnvelope extent;
    
        public QueryPointsLayer (IMapLayerInfo layerInfo) throws IOException {
            this.name = layerInfo.getName();
            this.id= layerInfo.getID();
            this.extent = layerInfo.getExtent();
        }
    
        public JSONObject toJsonObject () {
            JSONObject result = new JSONObject();
            result.put("name", name);
            result.put("id", id);
            return result;
        }
        
        public abstract Iterable<JSONObject> query (Iterable<PointQuery> points, String field) throws IOException;
    }
    
    private static class QueryPointsMosaicLayer extends QueryPointsLayer
    {
        IFeatureClass m_catalog;
        IMapTableTimeInfo m_timeInfo;
        Map<Integer, IRaster2> m_cache;
    
        public QueryPointsMosaicLayer (IMapLayerInfo layerInfo,
                                       IFeatureClass boundary,
                                       IFeatureClass footprint,
                                       IRaster mosaic) throws IOException {
            super(layerInfo);
            m_catalog = footprint;
            m_timeInfo = new IMapTableTimeInfoProxy(layerInfo);
            m_cache = new HashMap<Integer, IRaster2>();
        }
    
        @Override 
        public Iterable<JSONObject> query (Iterable<PointQuery> points, String field) throws IOException {
            LinkedList<JSONObject> results = new LinkedList<JSONObject>();
            List<Object> pointResults = new ArrayList<Object>();
            for (PointQuery point : points) {
                pointResults.clear();
                TimeQueryFilter filter = new TimeQueryFilter();
                filter.setGeometryByRef(point.geometry);
                filter.setSpatialRel(esriSpatialRelEnum.esriSpatialRelIntersects);
                filter.setGeometryField(m_catalog.getShapeFieldName());
                filter.setWhereClause(point.getWhereClause(m_timeInfo));
                IFeatureCursor cursor = m_catalog.search(filter, false);
                IFeature feature;
                while ((feature = cursor.nextFeature()) != null) {
                    IRaster2 raster = m_cache.get(Integer.valueOf(feature.getOID()));
                    if (raster == null) {
                        IRasterCatalogItem item = new IRasterCatalogItemProxy(feature);
                        raster = (IRaster2)item.getRasterDataset().createDefaultRaster();
                        m_cache.put(Integer.valueOf(feature.getOID()), raster);
                    }
                    pointResults.add(Util.findValue(raster, 0, point.geometry));
                }
                results.addLast(point.toJson(pointResults));
            }
            return results;
        }
    }
    
    private static class QueryPointsRasterLayer extends QueryPointsLayer
    {
        private IRaster2 m_raster;
    
        public QueryPointsRasterLayer (IMapLayerInfo layerInfo, IRaster raster) throws IOException {
            super(layerInfo);
            m_raster = (IRaster2)raster;
        }

        @Override 
        public Iterable<JSONObject> query (Iterable<PointQuery> points, String field) throws IOException {
            LinkedList<JSONObject> results = new LinkedList<JSONObject>();
            int fieldIndex = -1;
            if ((m_raster.getAttributeTable() != null) && (field != null)) {
                fieldIndex = m_raster.getAttributeTable().findField(field);
            }
            for (PointQuery point : points) {
                Object value = Util.findValue(m_raster, 0, point.geometry);
                if (fieldIndex >= 0) {
                    value = m_raster.getAttributeTable().getRow(((Number)value).intValue()).getValue(fieldIndex);
                }
                results.addLast(point.toJson(value));
            }
            return results;
        }
    }
    
    private static class QueryPointsFeatureClassLayer extends QueryPointsLayer
    {
        private IFeatureClass m_fc;
    
        public QueryPointsFeatureClassLayer (IMapLayerInfo layerInfo, IFeatureClass fc) throws IOException { 
            super(layerInfo);
            m_fc = fc;
        }

        @Override 
        public Iterable<JSONObject> query (Iterable<PointQuery> points, String field) throws IOException {
            LinkedList<JSONObject> results = new LinkedList<JSONObject>();
            SpatialFilter filter = new SpatialFilter();
            filter.setSubFields(field);
            filter.setSpatialRel(esriSpatialRelEnum.esriSpatialRelIntersects);
            for (PointQuery point : points) {
                filter.setGeometryByRef(point.geometry);
                IFeatureCursor cursor = m_fc.search(filter, false);
                IFeature feature = cursor.nextFeature();
                if (feature != null) {
                    Object value = feature.getValue(cursor.findField(field));
                    results.addLast(point.toJson(value));
                } else {
                    results.addLast(point.toJson(null));
                }
            }
            return results;
        }
    }
}