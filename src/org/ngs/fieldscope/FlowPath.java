package org.ngs.fieldscope;

import com.esri.arcgis.datasourcesraster.IPixelBlock3;
import com.esri.arcgis.datasourcesraster.IRaster2;
import com.esri.arcgis.datasourcesraster.IRasterBand;
import com.esri.arcgis.datasourcesraster.IRasterBandCollection;
import com.esri.arcgis.datasourcesraster.IRasterProps;
import com.esri.arcgis.datasourcesraster.IRasterPropsProxy;
import com.esri.arcgis.datasourcesraster.IRawPixels;
import com.esri.arcgis.datasourcesraster.IRawPixelsProxy;
import com.esri.arcgis.datasourcesraster.Raster;
import com.esri.arcgis.geodatabase.FeatureClass;
import com.esri.arcgis.geodatabase.IFeatureClass;
import com.esri.arcgis.geodatabase.IPixelBlock;
import com.esri.arcgis.geodatabase.IPnt;
import com.esri.arcgis.geodatabase.IRaster;
import com.esri.arcgis.geodatabase.Pnt;
import com.esri.arcgis.geometry.IPoint;
import com.esri.arcgis.geometry.IPointCollection;
import com.esri.arcgis.geometry.ISpatialReference;
import com.esri.arcgis.geometry.Path;
import com.esri.arcgis.geometry.Point;
import com.esri.arcgis.geometry.Polyline;
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

@ArcGISExtension
@ServerObjectExtProperties(displayName = "FlowPath", 
                           description = "Compute flow path downhill from pour point", 
                           properties = { "HighResolutionMaxSteps=1000", "LowResolutionMaxSteps=16384" })
public class FlowPath extends FieldScopeSOE 
{    
    private static final long serialVersionUID = -6325491414063347294L;
    
    private IRaster m_LowResFlowDir = null;
    private IFeatureClass m_HighResFlowDirIndex = null;
    private IFeatureClass m_HighResFlowDirCatalog = null;
    private int m_maxHighResolutionSteps = 1000;
    private int m_maxLowResolutionSteps = 16384;
    
    @Override
    @SuppressWarnings("deprecation")
    public void construct(IPropertySet propertySet) throws IOException, AutomationException {
        super.construct(propertySet);
        if (propertySet.getProperty("HighResolutionMaxSteps") != null) {
            m_maxHighResolutionSteps = Integer.parseInt(propertySet.getProperty("HighResolutionMaxSteps").toString());
        }
        if (propertySet.getProperty("LowResolutionMaxSteps") != null) {
            m_maxLowResolutionSteps = Integer.parseInt(propertySet.getProperty("LowResolutionMaxSteps").toString());
        }
        // Raster(Object) is deprecated, but no alternative currently exists
        m_LowResFlowDir = new Raster(getDataSourceByID(0));
        if (m_LowResFlowDir == null) {
            logError("missing or invalid data layer: low resolution flow direction");
        }
        m_HighResFlowDirIndex = new FeatureClass(getDataSourceByID(1));
        if (m_HighResFlowDirIndex == null) {
            logWarning("missing or invalid data layer: high resolution flow direction index");
        }
        m_HighResFlowDirCatalog = new FeatureClass(getDataSourceByID(2));
        if (m_HighResFlowDirCatalog == null) {
            logWarning("missing or invalid data layer: high resolution flow direction catalog");
        }
    }
    
    @Override 
    public void shutdown () throws IOException, AutomationException {
        super.shutdown();
        m_LowResFlowDir = null;
        m_HighResFlowDirIndex = null;
    }
    
    public String getSchema() throws IOException, AutomationException {
        JSONObject result = ServerUtilities.createResource("FlowPath", "Compute flow path downhill from pour point", false, false);
        JSONArray operations = new JSONArray();
        operations.put(ServerUtilities.createOperation("flowPath", "pourPoint, outSR", "json", false));
        result.put("operations", operations);
        return result.toString();
    }
    
    protected byte[] getResource (String resourceName) throws Exception {
        if (resourceName.equalsIgnoreCase("") || resourceName.length() == 0) {
            JSONObject json = new JSONObject();
            json.put("service", "FlowPath");
            json.put("description", "Compute flow path downhill from pour point");
            return json.toString().getBytes("utf-8");
        }
        return null;
    }
    
    protected byte[] invokeRESTOperation(String resourceName, 
                                         String operationName, 
                                         JSONObject operationInput,
                                         String outputFormat,
                                         Map<String, String> responsePropertiesMap) throws Exception {
        byte[] operationOutput = null;
        if (operationName.equalsIgnoreCase("flowPath")) {
            JSONObject pointJson = operationInput.getJSONObject("pourPoint");
            IPoint point = ServerUtilities.getPointFromJSON(pointJson);
            ISpatialReference outSR = getSpatialReferenceParam(operationInput, "outSR");
            ISpatialReference workSR = ((IRasterProps)m_LowResFlowDir).getSpatialReference();
            if ((point.getSpatialReference() != null) && (point.getSpatialReference().getFactoryCode() != workSR.getFactoryCode())) {
                point.project(workSR);
            }

            Path path = new Path();
            path.addPoint(point, null, null);
            IRaster highResFlowDir = null;
            if ((m_HighResFlowDirIndex != null) && (m_HighResFlowDirCatalog != null)) {
                Object hiResDS = Util.findValue(m_HighResFlowDirIndex, "VALUE", point);
                if (hiResDS != null) {
                    highResFlowDir = Util.findRaster(m_HighResFlowDirCatalog, hiResDS.toString());
                }
            }
            if (highResFlowDir != null) {
                tracePath(point, highResFlowDir, m_maxHighResolutionSteps, path);
            }
            tracePath(point, m_LowResFlowDir, m_maxLowResolutionSteps, path);

            Polyline poly = new Polyline();
            poly.addGeometry(path, null, null);
            poly.setSpatialReferenceByRef(workSR);

            if ((outSR != null) && (outSR.getFactoryCode() != poly.getSpatialReference().getFactoryCode())) {
                poly.project(outSR);
            }

            Feature resultFeature = new Feature();
            resultFeature.geometry = poly;
            resultFeature.attributes.put("Shape_Length", path.getLength());
            resultFeature.attributes.put("Shape_Units", describeUnits(path.getSpatialReference()));
            FeatureSet result = new FeatureSet();
            result.geometryType = esriGeometryType.esriGeometryPolyline;
            result.features.add(resultFeature);
            
            operationOutput = result.toJsonObject().toString().getBytes("utf-8");
        }
        return operationOutput;
    }
    
    private void tracePath (IPoint pourPoint, IRaster raster, int maxSteps, IPointCollection path) throws IOException {
        Point point = new Point();
        point.setX(pourPoint.getX());
        point.setY(pourPoint.getY());
        IRaster2 raster2 = (IRaster2)raster;
        IRasterBand rasterBand = ((IRasterBandCollection)raster2).item(0);
        IRawPixels rawPixels = new IRawPixelsProxy(rasterBand);
        IRasterProps rasterProperties = new IRasterPropsProxy(rasterBand);
        int rasterWidth = rasterProperties.getWidth();
        int rasterHeight = rasterProperties.getHeight();
        Object noDataValue = rasterProperties.getNoDataValue();
        int noData = (noDataValue instanceof Number) ? ((Number)noDataValue).intValue() : -1;
        IPnt cellSize = rasterProperties.meanCellSize();
        IPnt blockSize = new Pnt();
        blockSize.setCoords(rasterProperties.getWidth(), rasterProperties.getHeight());
        IPixelBlock pixelBlock = raster.createPixelBlock(blockSize);
        IPnt origin = new Pnt();
        origin.setCoords(0, 0);
        rawPixels.read(origin, pixelBlock);
        Object data = ((IPixelBlock3)pixelBlock).getPixelDataByRef(0);
        double dx = 0;
        double dy = 0;
        double lastDx;
        double lastDy;
        int[] row = { -1 };
        int[] col = { -1 };
        int steps = 0;
        while (true) {
            steps += 1;
            raster2.mapToPixel(point.getX(), point.getY(), col, row);
            if ((col[0] < 0) || (col[0] >= rasterWidth) || (row[0] < 0) || (row[0] >= rasterHeight)) {
                break;
            }
            Number value = (Number)Array.get(Array.get(data, col[0]), row[0]);
            if ((value == null) || (value.intValue() == noData)) {
                break;
            }
            int flowDir = value.intValue();
            lastDx = dx;
            lastDy = dy;
            switch (flowDir) {
                case 1:
                    dx = cellSize.getX();
                    dy = 0;
                    break;
                case 2:
                    dx = cellSize.getX();
                    dy = -cellSize.getY();
                    break;
                case 4:
                    dx = 0;
                    dy = -cellSize.getY();
                    break;
                case 8:
                    dx = -cellSize.getX();
                    dy = -cellSize.getY();
                    break;
                case 16:
                    dx = -cellSize.getX();
                    dy = 0;
                    break;
                case 32:
                    dx = -cellSize.getX();
                    dy = cellSize.getY();
                    break;
                case 64:
                    dx = 0;
                    dy = cellSize.getY();
                    break;
                case 128:
                    dx = cellSize.getX();
                    dy = cellSize.getY();
                    break;
                default:
                    dx = dy = 0;
                    break;
            }
            if ((dx != lastDx) || (dy != lastDy)) {
                path.addPoint((IPoint)point.esri_clone(), null, null);
            }
            if (((dx == 0) && (dy == 0)) || (steps > maxSteps)) {
                break;
            }
            point.setX(point.getX() + dx);
            point.setY(point.getY() + dy);
        }
        path.addPoint((IPoint)point.esri_clone(), null, null);
        pourPoint.setX(point.getX());
        pourPoint.setY(point.getY());
    }
}