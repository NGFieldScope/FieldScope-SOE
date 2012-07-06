using System;
using System.Collections.Generic;
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
    [Guid("6dab50f9-7e0c-4944-9840-c95b8d461973")]
    [ClassInterface(ClassInterfaceType.None)]
    [ServerObjectExtension("MapServer",
        AllCapabilities = "",
        DefaultCapabilities = "",
        Description = "Compute upstream area from watershed outlet",
        DisplayName = "UpstreamAreaSOE",
        Properties = "",
        SupportsREST = true,
        SupportsSOAP = false)]
    public class UpstreamAreaSOE : FieldScopeSOE
    {
        private static IntPoint FindPixel (IRaster2 raster, double x, double y) {
            int col, row;
            raster.MapToPixel(x, y, out col, out row);
            return new IntPoint(col, row);
        }

        const int FLOW_ACCUM_BAND = 0;
        const int FLOW_DIR_BAND = 1;

        private IRaster m_lowResFlow = null;
        private IFeatureClass m_highResIndex = null;
        private IFeatureClass m_highResCatalog = null;
        private IRaster m_flowArea = null;
        private IFeatureClass m_flowLine = null;
        private double m_tolerance = 0.0;
        private int m_highResFlowAccumThreshold = 20;

        override public void Construct (IPropertySet props) {
            base.Construct(props);
            m_lowResFlow = GetDataSourceByID(0) as IRaster;
            if (m_lowResFlow == null) {
                LogError("missing or invalid data layer: low resolution flow");
            }
            m_highResIndex = GetDataSourceByID(1) as IFeatureClass;
            if (m_highResIndex == null) {
                LogError("missing or invalid data layer: high resolution flow index");
            }
            m_highResCatalog = GetDataSourceByID(2) as IFeatureClass;
            if (m_highResCatalog == null) {
                LogError("missing or invalid data layer: high resolution flow catalog");
            }
            m_flowArea = GetDataSourceByID(3) as IRaster;
            if (m_flowArea == null) {
                LogWarning("missing or invalid data layer: flow area");
            }
            m_flowLine = GetDataSourceByID(4) as IFeatureClass;
            if (m_flowArea == null) {
                LogWarning("missing or invalid data layer: flow line");
            }

        }

        override public void Shutdown () {
            base.Shutdown();
            m_lowResFlow = null;
            m_highResIndex = null;
            m_highResCatalog = null;
            m_flowArea = null;
            m_flowLine = null;
        }

        override protected RestResource CreateRestSchema () {
            RestResource rootRes = new RestResource(Name, false, RootHandler);
            RestOperation sampleOper = new RestOperation("upstreamArea",
                                                         new string[] { "outlet", "tolerance", "outSR" },
                                                         new string[] { "json" },
                                                         FlowPathHandler);
            rootRes.operations.Add(sampleOper);
            return rootRes;
        }

        private byte[] RootHandler (NameValueCollection boundVariables, string outputFormat, string requestProperties, out string responseProperties) {
            responseProperties = null;
            JsonObject result = new JsonObject();
            result.AddString("service", "upstreamArea");
            return Encoding.UTF8.GetBytes(result.ToJson());
        }

        private byte[] FlowPathHandler (NameValueCollection boundVariables, JsonObject operationInput, string outputFormat, string requestProperties, out string responseProperties) {
            responseProperties = null;
            JsonObject pointJson;
            if (!operationInput.TryGetJsonObject("outlet", out pointJson)) {
                throw new ArgumentNullException("outlet");
            }
            IPoint point = Conversion.ToGeometry(pointJson, esriGeometryType.esriGeometryPoint) as IPoint;
            if (point == null) {
                throw new ArgumentException(Name + ": invalid outlet", "outlet");
            }
            double? tolerance;
            if (operationInput.TryGetAsDouble("tolerance", out tolerance) && tolerance.HasValue) {
                m_tolerance = tolerance.Value;
            } else {
                m_tolerance = 0.0;
            }
            ISpatialReference outSR = GetSpatialReferenceParam(operationInput, "outSR");
            ISpatialReference workSR = (m_lowResFlow as IRasterProps).SpatialReference;
            if ((point.SpatialReference != null) && (point.SpatialReference.FactoryCode != workSR.FactoryCode)) {
                point.Project(workSR);
            }

            IRaster flowRaster = null;

            // First, open the high resolution dataset, if we can find one
            object hiResDS = Util.FindValue(m_highResIndex, "VALUE", point);
            if (hiResDS != null) {
                flowRaster = Util.FindRaster(m_highResCatalog, hiResDS.ToString());
            }

            // Next snap the pour point either to a flow line (if we're in a blue
            // area on the map), or to the cell with the highest flow accumulation
            // (if a high resolution dataset is available).
            SnapPourPoint(point, flowRaster);

            // Next, check the pour point for its low-resolution flow accumulation, to
            // see if we need to use the low-resolution flow direction dataset instead
            // of the high-resolution data.
            int flowAccum = Convert.ToInt32(Util.FindValue(m_lowResFlow as IRaster2, FLOW_ACCUM_BAND, point));
            if ((flowRaster == null) || (flowAccum >= m_highResFlowAccumThreshold)) {
                flowRaster = m_lowResFlow;
            }

            // Finally, compute the upstream area
            bool[,] data = ComputeUpstreamArea(point, flowRaster);
            BoundingCurve bc = new BoundingCurve(data);
            IPolygon resultGeom = bc.GetBoundaryAsPolygon(flowRaster as IRasterProps);
            resultGeom.SpatialReference = workSR;

            if ((outSR != null) && (outSR.FactoryCode != resultGeom.SpatialReference.FactoryCode)) {
                resultGeom.Project(outSR);
            }

            Feature resultFeature = new Feature();
            resultFeature.Geometry = resultGeom;
            resultFeature.Attributes.Add("Shape_Length", resultGeom.Length);
            resultFeature.Attributes.Add("Shape_Area", (resultGeom as IArea).Area);
            resultFeature.Attributes.Add("Shape_Units", DescribeUnits(resultGeom.SpatialReference));
            FeatureSet result = new FeatureSet();
            result.Features.Add(resultFeature);

            return Encoding.UTF8.GetBytes(result.ToJsonObject().ToJson());
        }

        private void SnapPourPoint (IPoint point, IRaster flowRaster) {
            // If the start point lies in a flow area, snap it to the nearest flow line
            if (IsStartPointInFlowArea(point)) {
                SnapToFlowLines(point);
            } else if (flowRaster != null) {
                SnapToMaxFlowAccum(point, flowRaster);
            }
        }

        private bool IsStartPointInFlowArea (IPoint point) {
            object flowAreaValue = Util.FindValue(m_flowArea as IRaster2, 0, point);
            IRasterProps flowAreaProps = (m_flowArea as IRasterBandCollection).Item(0) as IRasterProps;
            return (flowAreaValue != null) && (!flowAreaValue.Equals(flowAreaProps.NoDataValue));
        }

        private void SnapToFlowLines (IPoint point) {
            IPolyline polyline = (IPolyline)m_flowLine.GetFeature(1).Shape;
            IPoint outPoint = new PointClass();
            double distanceAlongCurve = 0;
            double distanceFromCurve = 0;
            bool rightSide = false;
            polyline.QueryPointAndDistance(esriSegmentExtension.esriNoExtension,
                                           point,
                                           false,
                                           outPoint,
                                           ref distanceAlongCurve,
                                           ref distanceFromCurve,
                                           ref rightSide);
            point.X = outPoint.X;
            point.Y = outPoint.Y;
        }

        private void SnapToMaxFlowAccum (IPoint point, IRaster flowRaster) {
            IRaster2 flowRaster2 = flowRaster as IRaster2;
            IntPoint minCell = FindPixel(flowRaster2, point.X - m_tolerance, point.Y + m_tolerance);
            IntPoint maxCell = FindPixel(flowRaster2, point.X + m_tolerance, point.Y - m_tolerance);
            if ((minCell.X != maxCell.X) || (minCell.Y != maxCell.Y)) {
                IRasterBand flowAccumBand = (flowRaster as IRasterBandCollection).Item(FLOW_ACCUM_BAND);
                IRawPixels flowAccumPixels = flowAccumBand as IRawPixels;
                IRasterProps flowAccumProperties = flowAccumBand as IRasterProps;
                IPnt cellSize = flowAccumProperties.MeanCellSize();
                IPnt blockSize = new Pnt();
                blockSize.SetCoords(flowAccumProperties.Width, flowAccumProperties.Height);
                IPixelBlock pixelBlock = flowRaster.CreatePixelBlock(blockSize);
                IPnt pixelOrigin = new Pnt();
                pixelOrigin.SetCoords(0, 0);
                flowAccumPixels.Read(pixelOrigin, pixelBlock);
                System.Array flowAccum = (System.Array)(pixelBlock as IPixelBlock3).get_PixelDataByRef(0);
                int maxAccum = 0;
                int maxCol = 0;
                int maxRow = 0;
                for (int col = minCell.X; col <= maxCell.X; col += 1) {
                    for (int row = minCell.Y; row <= maxCell.Y; row += 1) {
                        object value = flowAccum.GetValue(col, row);
                        if ((value != null) && (Convert.ToInt32(value) > maxAccum)) {
                            maxAccum = Convert.ToInt32(value);
                            maxCol = col;
                            maxRow = row;
                        }
                    }
                }
                double maxX, maxY;
                (flowRaster as IRaster2).PixelToMap(maxCol, maxRow, out maxX, out maxY);
                point.X = maxX;
                point.Y = maxY;
            }
        }

        private bool[,] ComputeUpstreamArea (IPoint point, IRaster flowRaster) {
            IRasterBand flowDirectionBand = (flowRaster as IRasterBandCollection).Item(FLOW_DIR_BAND);
            IRawPixels flowDirectionPixels = flowDirectionBand as IRawPixels;
            IRasterProps flowDirProperties = flowDirectionBand as IRasterProps;

            IPnt flowDirBlockSize = new Pnt();
            flowDirBlockSize.SetCoords(flowDirProperties.Width, flowDirProperties.Height);
            IPixelBlock pixelBlock = flowRaster.CreatePixelBlock(flowDirBlockSize);
            IPnt pixelOrigin = new Pnt();
            pixelOrigin.SetCoords(0, 0);
            flowDirectionPixels.Read(pixelOrigin, pixelBlock);
            System.Array flowDir = (System.Array)(pixelBlock as IPixelBlock3).get_PixelDataByRef(0);

            bool[,] outData = new bool[flowDirProperties.Width, flowDirProperties.Height];
            outData.Initialize();
            PointQueue queue = new PointQueue();
            queue.Visit(FindPixel(flowRaster as IRaster2, point.X, point.Y));

            // Compute upstream area
            while (!queue.Empty()) {
                IntPoint pt = queue.Dequeue();
                outData[pt.X, pt.Y] = true;
                // right
                TestPoint(flowDir, new IntPoint(pt.X + 1, pt.Y), 16, queue);
                // lower right
                TestPoint(flowDir, new IntPoint(pt.X + 1, pt.Y + 1), 32, queue);
                // down
                TestPoint(flowDir, new IntPoint(pt.X, pt.Y + 1), 64, queue);
                // lower left
                TestPoint(flowDir, new IntPoint(pt.X - 1, pt.Y + 1), 128, queue);
                // left
                TestPoint(flowDir, new IntPoint(pt.X - 1, pt.Y), 1, queue);
                // upper left
                TestPoint(flowDir, new IntPoint(pt.X - 1, pt.Y - 1), 2, queue);
                // up
                TestPoint(flowDir, new IntPoint(pt.X, pt.Y - 1), 4, queue);
                // upper right
                TestPoint(flowDir, new IntPoint(pt.X + 1, pt.Y - 1), 8, queue);
            }
            return outData;
        }

        private void TestPoint (System.Array flowDir, IntPoint pt, Int32 inflowDirection, PointQueue queue) {
            if ((!queue.Visited(pt)) &&
                (pt.X >= 0) && (pt.X < flowDir.GetLength(0)) &&
                (pt.Y >= 0) && (pt.Y < flowDir.GetLength(1))) {
                object value = flowDir.GetValue(pt.X, pt.Y);
                if ((value != null) && (Convert.ToInt32(value) == inflowDirection)) {
                    queue.Visit(pt);
                }
            }
        }
    }

    internal class PointQueue
    {
        private Queue<IntPoint> m_Queue = new Queue<IntPoint>();
        private HashSet<IntPoint> m_Visited = new HashSet<IntPoint>();

        public bool Empty () {
            return m_Queue.Count == 0;
        }

        public IntPoint Dequeue () {
            return m_Queue.Dequeue();
        }

        public void Visit (IntPoint pt) {
            m_Queue.Enqueue(pt);
            m_Visited.Add(pt);
        }

        public bool Visited (IntPoint pt) {
            return m_Visited.Contains(pt);
        }
    }
}
