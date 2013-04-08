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
import com.esri.arcgis.geometry.IArea;
import com.esri.arcgis.geometry.IPoint;
import com.esri.arcgis.geometry.IPolygon;
import com.esri.arcgis.geometry.IPolyline;
import com.esri.arcgis.geometry.ISpatialReference;
import com.esri.arcgis.geometry.Point;
import com.esri.arcgis.geometry.esriGeometryType;
import com.esri.arcgis.geometry.esriSegmentExtension;
import com.esri.arcgis.interop.AutomationException;
import com.esri.arcgis.interop.extn.ArcGISExtension;
import com.esri.arcgis.interop.extn.ServerObjectExtProperties;
import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONObject;
import com.esri.arcgis.system.IPropertySet;
import com.esri.arcgis.system.ServerUtilities;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@ArcGISExtension
@ServerObjectExtProperties(displayName = "UpstreamArea", 
                           description = "Compute upstream area from watershed outlet", 
                           properties = { "HighResolutionThreshold=20" })
public class UpstreamArea extends FieldScopeSOE 
{
    private static final long serialVersionUID = 134786121336177856L;

    private static IntPoint findPixel (IRaster2 raster, double x, double y) throws IOException {
        int[] col = { -1 }, row = { -1 };
        raster.mapToPixel(x, y, col, row);
        return new IntPoint(col[0], row[0]);
    }

    final int FLOW_ACCUM_BAND = 0;
    final int FLOW_DIR_BAND = 1;

    private IRaster m_lowResFlow = null;
    private IFeatureClass m_highResIndex = null;
    private IFeatureClass m_highResCatalog = null;
    private IRaster m_flowArea = null;
    private IFeatureClass m_flowLine = null;
    private double m_tolerance = 0.0;
    private int m_highResFlowAccumThreshold = 20;
    
	@Override
	@SuppressWarnings("deprecation")
    public void construct (IPropertySet propertySet) throws IOException, AutomationException {
	    super.construct(propertySet);
	    if (propertySet.getProperty("HighResolutionThreshold") != null) {
	        m_highResFlowAccumThreshold = Integer.parseInt(propertySet.getProperty("HighResolutionThreshold").toString());
        }
	    // Raster(Object) is deprecated, but no alternative currently exists
	    m_lowResFlow = new Raster(getDataSourceByID(0));
        if (m_lowResFlow == null) {
            logError("missing or invalid data layer: low resolution flow");
        }
        m_highResIndex = new FeatureClass(getDataSourceByID(1));
        if (m_highResIndex == null) {
            logError("missing or invalid data layer: high resolution flow index");
        }
        m_highResCatalog = new FeatureClass(getDataSourceByID(2));
        if (m_highResCatalog == null) {
            logError("missing or invalid data layer: high resolution flow catalog");
        }
        m_flowArea = new Raster(getDataSourceByID(3));
        if (m_flowArea == null) {
            logWarning("missing or invalid data layer: flow area");
        }
        m_flowLine = new FeatureClass(getDataSourceByID(4));
        if (m_flowArea == null) {
            logWarning("missing or invalid data layer: flow line");
        }
	}
	
	@Override
    public void shutdown() throws IOException, AutomationException {
        super.shutdown();
        m_lowResFlow = null;
        m_highResIndex = null;
        m_highResCatalog = null;
        m_flowArea = null;
        m_flowLine = null;
    }
    
    public String getSchema() throws IOException, AutomationException {
        JSONObject result = ServerUtilities.createResource("UpstreamArea", "Compute upstream area from watershed outlet", false, false);
        JSONArray operations = new JSONArray();
        operations.put(ServerUtilities.createOperation("upstreamArea", "outlet, tolerance, outSR", "json", false));
        result.put("operations", operations);
        return result.toString();
    }
	
	protected byte[] getResource (String resourceName) throws Exception {
	    if (resourceName.equalsIgnoreCase("") || resourceName.length() == 0) {
		    JSONObject json = new JSONObject();
	        json.put("service", "UpstreamArea");
            json.put("description", "Compute upstream area from watershed outlet");
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
		if (operationName.equalsIgnoreCase("upstreamArea")) {
            JSONObject pointJson = operationInput.getJSONObject("outlet");
            IPoint point = ServerUtilities.getPointFromJSON(pointJson);
            m_tolerance = operationInput.optDouble("tolerance", 0.0);
            
            ISpatialReference outSR = getSpatialReferenceParam(operationInput, "outSR");
            ISpatialReference workSR = new IRasterPropsProxy(m_lowResFlow).getSpatialReference();
            if ((point.getSpatialReference() != null) && (point.getSpatialReference().getFactoryCode() != workSR.getFactoryCode())) {
                point.project(workSR);
            }

            IRaster flowRaster = null;

            // First, open the high resolution dataset, if we can find one
            Object hiResDS = Util.findValue(m_highResIndex, "VALUE", point);
            if (hiResDS != null) {
                flowRaster = Util.findRaster(m_highResCatalog, hiResDS.toString());
            }

            // Next snap the pour point either to a flow line (if we're in a blue
            // area on the map), or to the cell with the highest flow accumulation
            // (if a high resolution dataset is available).
            snapPourPoint(point, flowRaster);

            // Next, check the pour point for its low-resolution flow accumulation, to
            // see if we need to use the low-resolution flow direction dataset instead
            // of the high-resolution data.
            int flowAccum = ((Number)Util.findValue(((IRaster2)m_lowResFlow), FLOW_ACCUM_BAND, point)).intValue();
            if ((flowRaster == null) || (flowAccum >= m_highResFlowAccumThreshold)) {
                flowRaster = m_lowResFlow;
            }

            // Finally, compute the upstream area
            boolean[][] data = computeUpstreamArea(point, flowRaster);
            BoundingCurve bc = new BoundingCurve(data);
            IPolygon resultGeom = bc.getBoundaryAsPolygon(new IRasterPropsProxy(flowRaster));
            resultGeom.setSpatialReferenceByRef(workSR);

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
	
    private void snapPourPoint (IPoint point, IRaster flowRaster) throws IOException {
        // If the start point lies in a flow area, snap it to the nearest flow line
        if (isStartPointInFlowArea(point)) {
            snapToFlowLines(point);
        } else if (flowRaster != null) {
            snapToMaxFlowAccum(point, flowRaster);
        }
    }
    
    private boolean isStartPointInFlowArea (IPoint point) throws IOException {
        Object flowAreaValue = Util.findValue((IRaster2)m_flowArea, 0, point);
        IRasterProps flowAreaProps = new IRasterPropsProxy(((IRasterBandCollection)m_flowArea).item(0));
        return (flowAreaValue != null) && (!flowAreaValue.equals(flowAreaProps.getNoDataValue()));
    }
    
    private void snapToFlowLines (IPoint point) throws IOException {
        IPolyline polyline = (IPolyline)m_flowLine.getFeature(1).getShape();
        IPoint outPoint = new Point();
        double[] distanceAlongCurve =  { 0 };
        double[] distanceFromCurve = { 0 };
        boolean[] rightSide = { false };
        polyline.queryPointAndDistance(esriSegmentExtension.esriNoExtension,
                                       point,
                                       false,
                                       outPoint,
                                       distanceAlongCurve,
                                       distanceFromCurve,
                                       rightSide);
        point.setX(outPoint.getX());
        point.setY(outPoint.getY());
    }

    private void snapToMaxFlowAccum (IPoint point, IRaster flowRaster) throws IOException {
        IRaster2 flowRaster2 = (IRaster2)flowRaster;
        IntPoint minCell = findPixel(flowRaster2, point.getX() - m_tolerance, point.getY() + m_tolerance);
        IntPoint maxCell = findPixel(flowRaster2, point.getX() + m_tolerance, point.getY() - m_tolerance);
        if ((minCell.x != maxCell.x) || (minCell.y != maxCell.y)) {
            IRasterBand flowAccumBand = ((IRasterBandCollection)flowRaster).item(FLOW_ACCUM_BAND);
            IRawPixels flowAccumPixels = new IRawPixelsProxy(flowAccumBand);
            IRasterProps flowAccumProperties = new IRasterPropsProxy(flowAccumBand);
            IPnt blockSize = new Pnt();
            blockSize.setCoords(flowAccumProperties.getWidth(), flowAccumProperties.getHeight());
            IPixelBlock pixelBlock = flowRaster.createPixelBlock(blockSize);
            IPnt pixelOrigin = new Pnt();
            pixelOrigin.setCoords(0, 0);
            flowAccumPixels.read(pixelOrigin, pixelBlock);
            Object flowAccum = (Object)((IPixelBlock3)pixelBlock).getPixelDataByRef(0);
            int maxAccum = 0;
            int maxCol = 0;
            int maxRow = 0;
            for (int col = minCell.x; col <= maxCell.x; col += 1) {
                for (int row = minCell.y; row <= maxCell.y; row += 1) {
                    Number value = (Number)Array.get(Array.get(flowAccum, col), row);
                    if ((value != null) && (value.intValue() > maxAccum)) {
                        maxAccum = value.intValue();
                        maxCol = col;
                        maxRow = row;
                    }
                }
            }
            double[] maxX = { 0 }, maxY = { 0 };
            ((IRaster2)flowRaster).pixelToMap(maxCol, maxRow, maxX, maxY);
            point.setX(maxX[0]);
            point.setY(maxY[0]);
        }
    }

    private boolean[][] computeUpstreamArea (IPoint point, IRaster flowRaster) throws IOException {
        IRasterBand flowDirectionBand = ((IRasterBandCollection)flowRaster).item(FLOW_DIR_BAND);
        IRawPixels flowDirectionPixels = new IRawPixelsProxy(flowDirectionBand);
        IRasterProps flowDirProperties = new IRasterPropsProxy(flowDirectionBand);

        IPnt flowDirBlockSize = new Pnt();
        flowDirBlockSize.setCoords(flowDirProperties.getWidth(), flowDirProperties.getHeight());
        IPixelBlock pixelBlock = flowRaster.createPixelBlock(flowDirBlockSize);
        IPnt pixelOrigin = new Pnt();
        pixelOrigin.setCoords(0, 0);
        flowDirectionPixels.read(pixelOrigin, pixelBlock);
        Object flowDir = ((IPixelBlock3)pixelBlock).getPixelDataByRef(0);

        boolean[][] outData = new boolean[flowDirProperties.getWidth()][flowDirProperties.getHeight()];
        PointQueue queue = new PointQueue();
        queue.visit(findPixel((IRaster2)flowRaster, point.getX(), point.getY()));

        // Compute upstream area
        while (!queue.isEmpty()) {
            IntPoint pt = queue.dequeue();
            outData[pt.x][pt.x] = true;
            // right
            testPoint(flowDir, new IntPoint(pt.x + 1, pt.y), 16, queue);
            // lower right
            testPoint(flowDir, new IntPoint(pt.x + 1, pt.y + 1), 32, queue);
            // down
            testPoint(flowDir, new IntPoint(pt.x, pt.y + 1), 64, queue);
            // lower left
            testPoint(flowDir, new IntPoint(pt.x - 1, pt.y + 1), 128, queue);
            // left
            testPoint(flowDir, new IntPoint(pt.x - 1, pt.y), 1, queue);
            // upper left
            testPoint(flowDir, new IntPoint(pt.x - 1, pt.y - 1), 2, queue);
            // up
            testPoint(flowDir, new IntPoint(pt.x, pt.y - 1), 4, queue);
            // upper right
            testPoint(flowDir, new IntPoint(pt.x + 1, pt.y - 1), 8, queue);
        }
        return outData;
    }

    private void testPoint (Object flowDir, IntPoint pt, int inflowDirection, PointQueue queue) {
        if ((!queue.visited(pt)) &&
            (pt.x >= 0) && (pt.x < Array.getLength(flowDir)) &&
            (pt.y >= 0) && (pt.y < Array.getLength(Array.get(flowDir, pt.x)))) {
            Number value = (Number)Array.get(Array.get(flowDir, pt.x), pt.y);
            if ((value != null) && (value.intValue() == inflowDirection)) {
                queue.visit(pt);
            }
        }
    }
    
    static class PointQueue
    {
        private Queue<IntPoint> m_Queue = new LinkedList<IntPoint>();
        private Set<IntPoint> m_Visited = new HashSet<IntPoint>();

        public boolean isEmpty () {
            return m_Queue.size() == 0;
        }

        public IntPoint dequeue () {
            return m_Queue.remove();
        }

        public void visit (IntPoint pt) {
            m_Queue.offer(pt);
            m_Visited.add(pt);
        }

        public boolean visited (IntPoint pt) {
            return m_Visited.contains(pt);
        }
    }
}