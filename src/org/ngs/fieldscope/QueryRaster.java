package org.ngs.fieldscope;

import com.esri.arcgis.carto.IMapLayerInfo;
import com.esri.arcgis.datasourcesraster.IPixelBlock3;
import com.esri.arcgis.datasourcesraster.IRasterBand;
import com.esri.arcgis.datasourcesraster.IRasterBandCollection;
import com.esri.arcgis.datasourcesraster.IRasterProps;
import com.esri.arcgis.datasourcesraster.IRasterPropsProxy;
import com.esri.arcgis.datasourcesraster.IRawPixels;
import com.esri.arcgis.datasourcesraster.IRawPixelsProxy;
import com.esri.arcgis.datasourcesraster.Raster;
import com.esri.arcgis.geodatabase.IPixelBlock;
import com.esri.arcgis.geodatabase.IPnt;
import com.esri.arcgis.geodatabase.IRaster;
import com.esri.arcgis.geodatabase.Pnt;
import com.esri.arcgis.geometry.Envelope;
import com.esri.arcgis.geometry.IArea;
import com.esri.arcgis.geometry.IEnvelope;
import com.esri.arcgis.geometry.IPolygon;
import com.esri.arcgis.geometry.ISpatialReference;
import com.esri.arcgis.geometry.esriGeometryType;
import com.esri.arcgis.interop.AutomationException;
import com.esri.arcgis.interop.extn.ArcGISExtension;
import com.esri.arcgis.interop.extn.ServerObjectExtProperties;
import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONObject;
import com.esri.arcgis.system.IPropertySet;
import com.esri.arcgis.system.ServerUtilities;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ArcGISExtension
@ServerObjectExtProperties(displayName = "QueryRaster", 
                           description = "Return polygon of all raster cells that satisfy given conditions")
public class QueryRaster extends FieldScopeSOE 
{
    private static final long serialVersionUID = -6524431909300303670L;
    
    private Map<Integer, QueryRasterLayer> m_layers;
    
    @Override
    @SuppressWarnings("deprecation")
    public void construct(IPropertySet propertySet) throws IOException, AutomationException {
        super.construct(propertySet);
        m_layers = new TreeMap<Integer, QueryRasterLayer>();
        for (IMapLayerInfo layer : getMapLayerInfo()) {
            try {
                IRaster raster = new Raster(getDataSourceByID(layer.getID()));
                m_layers.put(Integer.valueOf(layer.getID()), new QueryRasterLayer(layer, raster));
            } catch (IOException e) {
                logWarning("Layer " + layer.getName() + " is not a raster");
            }
        }
    }
    
    @Override 
    public void shutdown () throws IOException, AutomationException {
        super.shutdown();
        m_layers = null;
    }
    
    public String getSchema() throws IOException, AutomationException {
        JSONObject result = ServerUtilities.createResource("QueryRaster", "Return polygon of all raster cells that satisfy given conditions", false, false);
        JSONArray resources = new JSONArray();
        JSONObject layers = ServerUtilities.createResource("layers", "Queryable layers in this map service", true, false);
        JSONArray operations = new JSONArray();
        operations.put(ServerUtilities.createOperation("queryRaster", "min, max, outSR", "json", false));
        layers.put("operations", operations);
        resources.put(layers);
        result.put("resources", resources);
        return result.toString();
    }
    
    protected byte[] getResource (String resourceName) throws Exception {
        if (resourceName.equalsIgnoreCase("") || resourceName.length() == 0) {
            JSONObject json = new JSONObject();
            json.put("service", "QueryRaster");
            json.put("description", "Return polygon of all raster cells that satisfy given conditions");
            JSONArray layers = new JSONArray();
            for (QueryRasterLayer layer : m_layers.values()) {
                layers.put(layer.toJsonObject());
            }
            json.put("layers", layers);
            return json.toString().getBytes("utf-8");
        }
        Pattern lp = Pattern.compile("layers/(\\w+)");
        Matcher m = lp.matcher(resourceName);
        if (m.matches()) {
            Integer layerId = Integer.valueOf(m.group(1).trim());
            QueryRasterLayer layer = m_layers.get(layerId);
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
        if (operationName.equalsIgnoreCase("queryRaster") && m.matches()) {
            Integer layerId = Integer.valueOf(m.group(1).trim());
            QueryRasterLayer layer = m_layers.get(layerId);
            
            double min = operationInput.optDouble("min");
            double max = operationInput.optDouble("max");
            if (Double.isNaN(min) && Double.isNaN(max)) {
                throw new IllegalArgumentException(getName() + ": must specify at least one of {min,max}");
            }
            ISpatialReference outSR = getSpatialReferenceParam(operationInput, "outSR");

            IRaster raster = layer.raster;
            IRasterBand band = ((IRasterBandCollection)raster).item(0);
            IRawPixels pixels = new IRawPixelsProxy(band);
            IRasterProps properties = new IRasterPropsProxy(band);
            int width = properties.getWidth();
            int height = properties.getHeight();
            Object noData = properties.getNoDataValue();
            IPnt flowDirBlockSize = new Pnt();
            flowDirBlockSize.setCoords(width, height);
            IPixelBlock pixelBlock = raster.createPixelBlock(flowDirBlockSize);
            IPnt pixelOrigin = new Pnt();
            pixelOrigin.setCoords(0, 0);
            pixels.read(pixelOrigin, pixelBlock);
            Object data = ((IPixelBlock3)pixelBlock).getPixelDataByRef(0);
            boolean[][] outData = new boolean[width][height];
            
            
            for (int x = 0; x < width; x += 1) {
                for (int y = 0; y < height; y += 1) {
                    Object value = Array.get(Array.get(data, x), y);
                    boolean cellValue = false;
                    if ((value != null) && (!value.equals(noData)) && (value instanceof Number)) {
                        double numericValue = ((Number)value).doubleValue();
                        if ((Double.isNaN(min) || (numericValue >= min)) &&
                            (Double.isNaN(max) || (numericValue <= max))) {
                            cellValue = true;
                        }
                    }
                    outData[x][y] = cellValue;
                }
            }

            BoundingCurve bc = new BoundingCurve(outData);
            IPolygon resultGeom = bc.getBoundaryAsPolygon(properties);
            resultGeom.setSpatialReferenceByRef(properties.getSpatialReference());
            
            if ((outSR != null) && (outSR.getFactoryCode() != resultGeom.getSpatialReference().getFactoryCode())) {
                resultGeom.project(outSR);
            }

            Feature resultFeature = new Feature();
            resultFeature.geometry = resultGeom;
            resultFeature.attributes.put("Shape_Length", resultGeom.getLength());
            resultFeature.attributes.put("Shape_Area", ((IArea)resultGeom).getArea());
            resultFeature.attributes.put("Shape_Units", describeUnits(resultGeom.getSpatialReference()));
            FeatureSet result = new FeatureSet();
            result.geometryType = esriGeometryType.esriGeometryPolygon;
            result.features.add(resultFeature);

            operationOutput = result.toJsonObject().toString().getBytes("utf-8");
        }
        return operationOutput;
    }
    
    public static class QueryRasterLayer implements Comparable<QueryRasterLayer>
    {
        public String name;
        public int id;
        public IEnvelope extent;
        public IRaster raster;

        public QueryRasterLayer (IMapLayerInfo mapLayerInfo, IRaster raster) throws IOException {
            this.name = mapLayerInfo.getName();
            this.id = mapLayerInfo.getID();
            this.extent = mapLayerInfo.getExtent();
            this.raster = raster;
        }

        public JSONObject toJsonObject () throws IOException {
            JSONObject result = new JSONObject();
            result.put("name", name);
            result.put("id", id);
            IRasterProps props = new IRasterPropsProxy(raster);
            result.put("rows", props.getWidth());
            result.put("columns", props.getHeight());
            result.put("extent", ServerUtilities.getJSONFromEnvelope((Envelope)props.getExtent()));
            return result;
        }

        @Override
        public int compareTo(QueryRasterLayer o) {
            return id - o.id;
        }
    }
}