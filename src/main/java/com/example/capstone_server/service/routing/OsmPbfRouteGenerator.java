package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RoutePoint;
import com.example.capstone_server.dto.RouteRequest;
import com.example.capstone_server.service.ShadowService;
import com.example.capstone_server.service.geo.LocalGeoDatabase;
import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.pbf.seq.PbfIterator;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class OsmPbfRouteGenerator {
    private static final double DEFAULT_PADDING_DEGREES = 0.006;
    private static final int MAX_RETURNED_CANDIDATES = 3;
    private static final double SPATIAL_INDEX_CELL_DEGREES = 0.01;

    private final boolean enabled;
    private final Path pbfPath;
    private final double maxBboxSpan;
    private final boolean osmDbImportEnabled;
    private final boolean regionWarmupEnabled;
    private final String regionWarmupRegions;
    private final String regionWarmupDepartureTimes;
    private final int shadeSlotMinutes;
    private final ShadowService shadowService;
    private final LocalGeoDatabase localGeoDatabase;
    private final Map<String, Graph> graphCache = new ConcurrentHashMap<>();
    private final List<WarmRegionGraph> warmRegionGraphs = new CopyOnWriteArrayList<>();
    private volatile Graph preloadedGraph;

    public OsmPbfRouteGenerator(@Value("${capstone.routing.osm.enabled:false}") boolean enabled,
                                @Value("${capstone.routing.osm.pbf-path:}") String pbfPath,
                                @Value("${capstone.routing.osm.max-bbox-span:0.04}") double maxBboxSpan,
                                @Value("${capstone.local-db.osm-import-enabled:false}") boolean osmDbImportEnabled,
                                @Value("${capstone.routing.region-warmup.enabled:false}") boolean regionWarmupEnabled,
                                @Value("${capstone.routing.region-warmup.regions:}") String regionWarmupRegions,
                                @Value("${capstone.routing.region-warmup.departure-times:}") String regionWarmupDepartureTimes,
                                @Value("${capstone.routing.region-warmup.shade-slot-minutes:30}") int shadeSlotMinutes,
                                ShadowService shadowService,
                                LocalGeoDatabase localGeoDatabase) {
        this.enabled = enabled;
        this.pbfPath = pbfPath == null || pbfPath.isBlank() ? null : Path.of(pbfPath);
        this.maxBboxSpan = maxBboxSpan;
        this.osmDbImportEnabled = osmDbImportEnabled;
        this.regionWarmupEnabled = regionWarmupEnabled;
        this.regionWarmupRegions = regionWarmupRegions == null ? "" : regionWarmupRegions;
        this.regionWarmupDepartureTimes = regionWarmupDepartureTimes == null ? "" : regionWarmupDepartureTimes;
        this.shadeSlotMinutes = Math.max(1, shadeSlotMinutes);
        this.shadowService = shadowService;
        this.localGeoDatabase = localGeoDatabase;
    }

    @PostConstruct
    public void preloadGraph() {
        if (!enabled || pbfPath == null || !Files.isRegularFile(pbfPath)) {
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            preloadedGraph = loadGraph(null);
            System.out.printf(
                    "OSM PBF graph preloaded in %d ms: %,d nodes, %,d directed edges%n",
                    System.currentTimeMillis() - startedAt,
                    preloadedGraph.points().size(),
                    preloadedGraph.edgeCount()
            );
            persistOsmGraphIfEnabled(preloadedGraph);
        } catch (Exception exception) {
            preloadedGraph = null;
            System.out.printf("OSM PBF preload failed: %s%n", exception.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpConfiguredRegions() {
        if (!enabled || !regionWarmupEnabled || preloadedGraph == null || regionWarmupRegions.isBlank()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            long startedAt = System.currentTimeMillis();
            int warmedRegions = 0;
            for (ConfiguredRegion region : parseConfiguredRegions(regionWarmupRegions)) {
                try {
                    warmUpRegion(region);
                    warmedRegions++;
                } catch (Exception exception) {
                    System.out.printf("OSM region warm-up failed for %s: %s%n", region.name(), exception.getMessage());
                }
            }
            System.out.printf(
                    "OSM region warm-up finished in %d ms: %,d configured regions%n",
                    System.currentTimeMillis() - startedAt,
                    warmedRegions
            );
        });
    }

    private void warmUpRegion(ConfiguredRegion region) {
        long startedAt = System.currentTimeMillis();
        Graph graph = subGraph(preloadedGraph, region.bounds());
        WarmRegionGraph warmRegionGraph = new WarmRegionGraph(
                region.name(),
                region.bounds(),
                graph,
                new ConcurrentHashMap<>()
        );
        warmRegionGraphs.add(warmRegionGraph);
        graphCache.putIfAbsent(region.bounds().cacheKey(), graph);

        for (String departureTime : parseDepartureTimes(regionWarmupDepartureTimes)) {
            String slotKey = shadeSlotKey(departureTime);
            if (!slotKey.isBlank()) {
                warmRegionGraph.shadeBySlot().computeIfAbsent(
                        slotKey,
                        ignored -> scoreGraphSegments(graph, departureTime)
                );
            }
        }

        System.out.printf(
                "OSM region warm-up completed for %s in %d ms: %,d nodes, %,d directed edges, %,d segments, %,d shade slots%n",
                region.name(),
                System.currentTimeMillis() - startedAt,
                graph.points().size(),
                graph.edgeCount(),
                graph.segmentEdges().size(),
                warmRegionGraph.shadeBySlot().size()
        );
    }

    private void persistOsmGraphIfEnabled(Graph graph) {
        if (!osmDbImportEnabled || !localGeoDatabase.isEnabled() || localGeoDatabase.hasOsmData()) {
            return;
        }

        long startedAt = System.currentTimeMillis();
        List<LocalGeoDatabase.OsmEdgeRecord> edgeRecords = new ArrayList<>();
        for (Map.Entry<Long, List<Edge>> entry : graph.edges().entrySet()) {
            for (Edge edge : entry.getValue()) {
                edgeRecords.add(new LocalGeoDatabase.OsmEdgeRecord(entry.getKey(), edge.to(), edge.distance()));
            }
        }
        localGeoDatabase.replaceOsmData(graph.points(), edgeRecords);
        System.out.printf(
                "OSM graph persisted to local DB in %d ms: %,d nodes, %,d directed edges%n",
                System.currentTimeMillis() - startedAt,
                graph.points().size(),
                edgeRecords.size()
        );
    }

    public List<CandidateRoute> generate(RoutePoint start, RoutePoint end, RouteRequest request) {
        if (!enabled || pbfPath == null || !Files.isRegularFile(pbfPath)) {
            return List.of();
        }

        Bounds bounds = Bounds.around(start, end, DEFAULT_PADDING_DEGREES);
        if (!bounds.isValid(maxBboxSpan)) {
            return List.of();
        }

        try {
            Optional<WarmRegionGraph> warmRegionGraph = warmRegionFor(bounds);
            Graph graph = warmRegionGraph.map(WarmRegionGraph::graph).orElseGet(() -> cachedGraph(bounds));
            Optional<NodeRef> startNode = nearestNode(graph, start);
            Optional<NodeRef> endNode = nearestNode(graph, end);
            if (startNode.isEmpty() || endNode.isEmpty()) {
                return List.of();
            }

            Map<String, Double> shadeByEdge = shadeScores(graph, warmRegionGraph.orElse(null), request);
            List<CandidateRoute> routes = new ArrayList<>();
            Set<String> usedEdges = new HashSet<>();
            addRoute(routes, graph, start, end, startNode.get(), endNode.get(), "osm-shortest", CostProfile.SHORTEST, shadeByEdge, 1.0, usedEdges);
            addRoute(routes, graph, start, end, startNode.get(), endNode.get(), "osm-shade", CostProfile.SHADE, shadeByEdge, 1.25, usedEdges);
            addRoute(routes, graph, start, end, startNode.get(), endNode.get(), "osm-balanced", CostProfile.BALANCED, shadeByEdge, 1.15, usedEdges);
            return routes.size() > MAX_RETURNED_CANDIDATES ? routes.subList(0, MAX_RETURNED_CANDIDATES) : routes;
        } catch (OsmGraphLoadException exception) {
            System.out.printf("OSM PBF graph load failed: %s%n", exception.getCause().getMessage());
            return List.of();
        } catch (Exception exception) {
            System.out.printf("OSM PBF route generation failed: %s%n", exception.getMessage());
            return List.of();
        }
    }

    private Graph cachedGraph(Bounds bounds) {
        return graphCache.computeIfAbsent(bounds.cacheKey(), ignored -> {
            try {
                if (preloadedGraph != null) {
                    return subGraph(preloadedGraph, bounds);
                }
                return loadGraph(bounds);
            } catch (Exception exception) {
                throw new OsmGraphLoadException(exception);
            }
        });
    }

    private Optional<WarmRegionGraph> warmRegionFor(Bounds bounds) {
        return warmRegionGraphs.stream()
                .filter(region -> region.bounds().contains(bounds))
                .findFirst();
    }

    private Map<String, Double> shadeScores(Graph graph, WarmRegionGraph warmRegionGraph, RouteRequest request) {
        String departureTime = request == null ? null : request.getDepartureTime();
        if (warmRegionGraph == null) {
            return scoreGraphSegments(graph, departureTime);
        }

        String slotKey = shadeSlotKey(departureTime);
        if (slotKey.isBlank()) {
            return scoreGraphSegments(graph, departureTime);
        }
        return warmRegionGraph.shadeBySlot().computeIfAbsent(
                slotKey,
                ignored -> scoreGraphSegments(graph, departureTime)
        );
    }

    private Graph subGraph(Graph source, Bounds bounds) {
        Map<Long, RoutePoint> nodes = new HashMap<>();
        for (Long nodeId : source.nodeIdsIn(bounds)) {
            RoutePoint point = source.points().get(nodeId);
            if (point != null) {
                nodes.put(nodeId, point);
            }
        }

        Graph graph = new Graph(nodes);
        for (Long nodeId : nodes.keySet()) {
            for (Edge edge : source.edges().getOrDefault(nodeId, List.of())) {
                if (nodes.containsKey(edge.to())) {
                    graph.edges().computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(edge);
                }
            }
        }
        return graph.withSegmentEdges(segmentEdges(graph));
    }

    private void addRoute(List<CandidateRoute> routes,
                          Graph graph,
                          RoutePoint start,
                          RoutePoint end,
                          NodeRef startNode,
                          NodeRef endNode,
                          String id,
                          CostProfile profile,
                          Map<String, Double> shadeByEdge,
                          double usedEdgePenalty,
                          Set<String> usedEdges) {
        List<Long> nodeIds = shortestPath(graph, startNode.id(), endNode.id(), profile, shadeByEdge, usedEdges, usedEdgePenalty);
        if (nodeIds.size() < 2) {
            return;
        }

        List<RoutePoint> points = toRoutePoints(graph, start, end, nodeIds);
        if (points.size() < 2 || duplicateRoute(points, routes)) {
            return;
        }

        for (int i = 1; i < nodeIds.size(); i++) {
            usedEdges.add(edgeKey(nodeIds.get(i - 1), nodeIds.get(i)));
        }
        routes.add(new CandidateRoute(id, "osm-pbf-local", points, 0.12));
    }

    private Graph loadGraph(Bounds bounds) throws Exception {
        Map<Long, RoutePoint> nodes = loadNodes(bounds);
        Graph graph = new Graph(nodes);
        try (InputStream input = new FileInputStream(pbfPath.toFile())) {
            PbfIterator iterator = new PbfIterator(input, true);
            while (iterator.hasNext()) {
                EntityContainer container = iterator.next();
                if (container.getType() != EntityType.Way) {
                    continue;
                }
                OsmWay way = (OsmWay) container.getEntity();
                if (!isWalkableWay(way)) {
                    continue;
                }
                addWayEdges(graph, way);
            }
        }
        graph = graph.withSegmentEdges(segmentEdges(graph));
        return bounds == null ? graph.withSpatialIndex(SpatialIndex.from(nodes)) : graph;
    }

    private Map<Long, RoutePoint> loadNodes(Bounds bounds) throws Exception {
        Map<Long, RoutePoint> nodes = new HashMap<>();
        try (InputStream input = new FileInputStream(pbfPath.toFile())) {
            PbfIterator iterator = new PbfIterator(input, true);
            while (iterator.hasNext()) {
                EntityContainer container = iterator.next();
                if (container.getType() != EntityType.Node) {
                    continue;
                }
                OsmNode node = (OsmNode) container.getEntity();
                double lat = node.getLatitude();
                double lon = node.getLongitude();
                if (bounds == null || bounds.contains(lat, lon)) {
                    nodes.put(node.getId(), new RoutePoint(lat, lon));
                }
            }
        }
        return nodes;
    }

    private void addWayEdges(Graph graph, OsmWay way) {
        for (int i = 1; i < way.getNumberOfNodes(); i++) {
            long from = way.getNodeId(i - 1);
            long to = way.getNodeId(i);
            RoutePoint fromPoint = graph.points().get(from);
            RoutePoint toPoint = graph.points().get(to);
            if (fromPoint == null || toPoint == null) {
                continue;
            }
            double distance = GeoMath.distanceMeters(fromPoint, toPoint);
            graph.edges().computeIfAbsent(from, ignored -> new ArrayList<>()).add(new Edge(to, distance));
            graph.edges().computeIfAbsent(to, ignored -> new ArrayList<>()).add(new Edge(from, distance));
        }
    }

    private Map<String, Double> scoreGraphSegments(Graph graph, String departureTime) {
        List<String> keys = new ArrayList<>();
        List<List<RoutePoint>> segments = new ArrayList<>();
        for (SegmentEdge segmentEdge : graph.segmentEdges()) {
            keys.add(segmentEdge.key());
            segments.add(List.of(segmentEdge.from(), segmentEdge.to()));
        }

        List<ShadowService.RouteSegmentShade> shades = shadowService.calculateSegmentShadeAnalyses(
                segments,
                departureTime
        );

        Map<String, Double> shadeByEdge = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            double shade = i < shades.size() ? shades.get(i).shadeCoverage() : 0.0;
            shadeByEdge.put(keys.get(i), shade);
        }
        return shadeByEdge;
    }

    private List<ConfiguredRegion> parseConfiguredRegions(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        List<ConfiguredRegion> regions = new ArrayList<>();
        for (String token : value.split(";")) {
            String[] parts = token.split(",");
            if (parts.length != 5) {
                continue;
            }
            try {
                regions.add(new ConfiguredRegion(
                        parts[0].trim(),
                        new Bounds(
                                Double.parseDouble(parts[1].trim()),
                                Double.parseDouble(parts[2].trim()),
                                Double.parseDouble(parts[3].trim()),
                                Double.parseDouble(parts[4].trim())
                        )
                ));
            } catch (NumberFormatException ignored) {
            }
        }
        return regions;
    }

    private List<String> parseDepartureTimes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        List<String> departureTimes = new ArrayList<>();
        for (String token : value.split(",")) {
            String departureTime = token.trim();
            if (!departureTime.isBlank()) {
                departureTimes.add(departureTime);
            }
        }
        return departureTimes;
    }

    private String shadeSlotKey(String departureTime) {
        if (departureTime == null || departureTime.isBlank()) {
            return "";
        }
        String normalized = departureTime.trim();
        try {
            LocalDateTime time = LocalDateTime.parse(normalized.length() == 16 ? normalized + ":00" : normalized);
            int flooredMinute = (time.getMinute() / shadeSlotMinutes) * shadeSlotMinutes;
            return time.withMinute(flooredMinute).withSecond(0).withNano(0).toString().substring(0, 16);
        } catch (Exception ignored) {
            return normalized.length() >= 16 ? normalized.substring(0, 16) : normalized;
        }
    }

    private boolean isWalkableWay(OsmWay way) {
        Map<String, String> tags = tags(way);
        String highway = tags.get("highway");
        if (highway == null) {
            return false;
        }
        if ("motorway".equals(highway) || "trunk".equals(highway) || "primary".equals(highway)) {
            return false;
        }
        if ("no".equals(tags.get("foot")) || "private".equals(tags.get("access"))) {
            return false;
        }
        return Set.of(
                "footway",
                "path",
                "pedestrian",
                "steps",
                "living_street",
                "residential",
                "service",
                "tertiary",
                "secondary",
                "unclassified"
        ).contains(highway);
    }

    private Map<String, String> tags(OsmWay way) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < way.getNumberOfTags(); i++) {
            OsmTag tag = way.getTag(i);
            tags.put(tag.getKey(), tag.getValue());
        }
        return tags;
    }

    private Optional<NodeRef> nearestNode(Graph graph, RoutePoint target) {
        return graph.points().entrySet().stream()
                .map(entry -> new NodeRef(entry.getKey(), GeoMath.distanceMeters(target, entry.getValue())))
                .min(Comparator.comparingDouble(NodeRef::distance));
    }

    private List<Long> shortestPath(Graph graph,
                                    long start,
                                    long end,
                                    CostProfile profile,
                                    Map<String, Double> shadeByEdge,
                                    Set<String> usedEdges,
                                    double usedEdgePenalty) {
        Map<Long, Double> distances = new HashMap<>();
        Map<Long, Long> previous = new HashMap<>();
        PriorityQueue<PathState> queue = new PriorityQueue<>(Comparator.comparingDouble(PathState::cost));
        Set<Long> visited = new HashSet<>();

        distances.put(start, 0.0);
        queue.add(new PathState(start, 0.0));

        while (!queue.isEmpty()) {
            PathState state = queue.poll();
            if (!visited.add(state.nodeId())) {
                continue;
            }
            if (state.nodeId() == end) {
                break;
            }
            for (Edge edge : graph.edges().getOrDefault(state.nodeId(), List.of())) {
                if (visited.contains(edge.to())) {
                    continue;
                }
                String key = edgeKey(state.nodeId(), edge.to());
                double penalty = usedEdges.contains(key) ? usedEdgePenalty : 1.0;
                double nextCost = state.cost() + edgeCost(edge, shadeByEdge.getOrDefault(key, 0.0), profile) * penalty;
                if (nextCost < distances.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
                    distances.put(edge.to(), nextCost);
                    previous.put(edge.to(), state.nodeId());
                    queue.add(new PathState(edge.to(), nextCost));
                }
            }
        }

        if (!distances.containsKey(end)) {
            return List.of();
        }
        return restoreNodePath(previous, start, end);
    }

    private double edgeCost(Edge edge, double shadeScore, CostProfile profile) {
        double shaded = GeoMath.clamp(shadeScore, 0.0, 1.0);
        if (profile == CostProfile.SHADE) {
            return edge.distance() * Math.max(0.18, 1.0 - shaded * 0.86);
        }
        if (profile == CostProfile.BALANCED) {
            return edge.distance() * Math.max(0.42, 1.0 - shaded * 0.45);
        }
        return edge.distance();
    }

    private List<Long> restoreNodePath(Map<Long, Long> previous, long start, long end) {
        List<Long> reversed = new ArrayList<>();
        Long current = end;
        while (current != null) {
            reversed.add(current);
            if (current == start) {
                break;
            }
            current = previous.get(current);
        }

        if (reversed.isEmpty() || reversed.get(reversed.size() - 1) != start) {
            return List.of();
        }

        List<Long> path = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }

    private List<RoutePoint> toRoutePoints(Graph graph, RoutePoint start, RoutePoint end, List<Long> nodeIds) {
        List<RoutePoint> points = new ArrayList<>();
        points.add(start);
        for (Long nodeId : nodeIds) {
            RoutePoint point = graph.points().get(nodeId);
            if (point != null && GeoMath.distanceMeters(points.get(points.size() - 1), point) > 1.0) {
                points.add(point);
            }
        }
        if (GeoMath.distanceMeters(points.get(points.size() - 1), end) > 1.0) {
            points.add(end);
        }
        return points;
    }

    private boolean duplicateRoute(List<RoutePoint> points, List<CandidateRoute> routes) {
        String key = routeKey(points);
        for (CandidateRoute route : routes) {
            if (key.equals(routeKey(route.points()))) {
                return true;
            }
        }
        return false;
    }

    private String routeKey(List<RoutePoint> points) {
        StringBuilder builder = new StringBuilder();
        for (RoutePoint point : points) {
            if (!builder.isEmpty()) {
                builder.append('|');
            }
            builder.append(Math.round(point.getLat() * 10000.0) / 10000.0)
                    .append(',')
                    .append(Math.round(point.getLon() * 10000.0) / 10000.0);
        }
        return builder.toString();
    }

    private String edgeKey(long left, long right) {
        return Math.min(left, right) + ":" + Math.max(left, right);
    }

    private List<SegmentEdge> segmentEdges(Graph graph) {
        List<SegmentEdge> segments = new ArrayList<>();
        for (Map.Entry<Long, List<Edge>> entry : graph.edges().entrySet()) {
            RoutePoint from = graph.points().get(entry.getKey());
            if (from == null) {
                continue;
            }
            for (Edge edge : entry.getValue()) {
                if (entry.getKey() > edge.to()) {
                    continue;
                }
                RoutePoint to = graph.points().get(edge.to());
                if (to != null) {
                    segments.add(new SegmentEdge(edgeKey(entry.getKey(), edge.to()), from, to));
                }
            }
        }
        return segments;
    }

    private record Bounds(double minLat, double minLon, double maxLat, double maxLon) {
        private static Bounds around(RoutePoint start, RoutePoint end, double padding) {
            return new Bounds(
                    Math.min(start.getLat(), end.getLat()) - padding,
                    Math.min(start.getLon(), end.getLon()) - padding,
                    Math.max(start.getLat(), end.getLat()) + padding,
                    Math.max(start.getLon(), end.getLon()) + padding
            );
        }

        private boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }

        private boolean contains(Bounds other) {
            return other.minLat >= minLat
                    && other.maxLat <= maxLat
                    && other.minLon >= minLon
                    && other.maxLon <= maxLon;
        }

        private boolean isValid(double maxSpan) {
            return maxLat > minLat && maxLon > minLon && maxLat - minLat <= maxSpan && maxLon - minLon <= maxSpan;
        }

        private String cacheKey() {
            return rounded(minLat) + "," + rounded(minLon) + "," + rounded(maxLat) + "," + rounded(maxLon);
        }

        private static double rounded(double value) {
            return Math.round(value * 1000.0) / 1000.0;
        }
    }

    private record Graph(Map<Long, RoutePoint> points,
                         Map<Long, List<Edge>> edges,
                         SpatialIndex spatialIndex,
                         List<SegmentEdge> segmentEdges) {
        private Graph(Map<Long, RoutePoint> points) {
            this(points, new LinkedHashMap<>(), null, List.of());
        }

        private Graph withSpatialIndex(SpatialIndex spatialIndex) {
            return new Graph(points, edges, spatialIndex, segmentEdges);
        }

        private Graph withSegmentEdges(List<SegmentEdge> segmentEdges) {
            return new Graph(points, edges, spatialIndex, segmentEdges);
        }

        private Set<Long> nodeIdsIn(Bounds bounds) {
            if (spatialIndex != null) {
                return spatialIndex.nodeIdsIn(bounds, points);
            }

            Set<Long> nodeIds = new HashSet<>();
            for (Map.Entry<Long, RoutePoint> entry : points.entrySet()) {
                RoutePoint point = entry.getValue();
                if (bounds.contains(point.getLat(), point.getLon())) {
                    nodeIds.add(entry.getKey());
                }
            }
            return nodeIds;
        }

        private int edgeCount() {
            return edges.values().stream()
                    .mapToInt(List::size)
                    .sum();
        }
    }

    private record Edge(long to, double distance) {
    }

    private record SegmentEdge(String key, RoutePoint from, RoutePoint to) {
    }

    private record ConfiguredRegion(String name, Bounds bounds) {
    }

    private record WarmRegionGraph(String name,
                                   Bounds bounds,
                                   Graph graph,
                                   Map<String, Map<String, Double>> shadeBySlot) {
    }

    private record SpatialIndex(Map<CellKey, List<Long>> cells) {
        private static SpatialIndex from(Map<Long, RoutePoint> points) {
            Map<CellKey, List<Long>> cells = new HashMap<>();
            for (Map.Entry<Long, RoutePoint> entry : points.entrySet()) {
                RoutePoint point = entry.getValue();
                CellKey key = CellKey.from(point.getLat(), point.getLon());
                cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry.getKey());
            }
            return new SpatialIndex(cells);
        }

        private Set<Long> nodeIdsIn(Bounds bounds, Map<Long, RoutePoint> points) {
            Set<Long> nodeIds = new HashSet<>();
            int minLatCell = CellKey.index(bounds.minLat());
            int maxLatCell = CellKey.index(bounds.maxLat());
            int minLonCell = CellKey.index(bounds.minLon());
            int maxLonCell = CellKey.index(bounds.maxLon());
            for (int latCell = minLatCell; latCell <= maxLatCell; latCell++) {
                for (int lonCell = minLonCell; lonCell <= maxLonCell; lonCell++) {
                    for (Long nodeId : cells.getOrDefault(new CellKey(latCell, lonCell), List.of())) {
                        RoutePoint point = points.get(nodeId);
                        if (point != null && bounds.contains(point.getLat(), point.getLon())) {
                            nodeIds.add(nodeId);
                        }
                    }
                }
            }
            return nodeIds;
        }
    }

    private record CellKey(int latCell, int lonCell) {
        private static CellKey from(double lat, double lon) {
            return new CellKey(index(lat), index(lon));
        }

        private static int index(double value) {
            return (int) Math.floor(value / SPATIAL_INDEX_CELL_DEGREES);
        }
    }

    private record NodeRef(long id, double distance) {
    }

    private record PathState(long nodeId, double cost) {
    }

    private enum CostProfile {
        SHORTEST,
        SHADE,
        BALANCED
    }

    private static class OsmGraphLoadException extends RuntimeException {
        private OsmGraphLoadException(Throwable cause) {
            super(cause);
        }
    }
}