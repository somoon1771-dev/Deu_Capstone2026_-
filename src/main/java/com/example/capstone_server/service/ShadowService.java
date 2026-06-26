package com.example.capstone_server.service;

import com.example.capstone_server.dto.RoutePoint;
import com.example.capstone_server.dto.ShadowRequest;
import com.example.capstone_server.dto.ShadowResponse;
import com.example.capstone_server.service.geo.LocalGeoDatabase;
import com.example.capstone_server.service.routing.ApiKeys;
import com.example.capstone_server.service.routing.SolarPosition;
import com.example.capstone_server.service.routing.SolarPositionCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShadowService {
    private static final String VWORLD_DATA_URL = "http://api.vworld.kr/req/data";
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final int VWORLD_MAX_PAGES = 5;
    private static final double ROUTE_TILE_CHUNK_METERS = 700.0;
    private static final double ROUTE_TILE_PADDING_METERS = 150.0;
    private static final double AREA_TILE_SPAN_DEGREES = 0.012;
    private static final int ADAPTIVE_SPLIT_MAX_DEPTH = 2;
    private static final int BUILDING_FETCH_CACHE_MAX_ENTRIES = 256;
    private static final Duration BUILDING_FETCH_CACHE_TTL = Duration.ofMinutes(15);
    private static final double SHADOW_INDEX_CELL_DEGREES = 0.0015;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper;
    private final SolarPositionCalculator solarPositionCalculator;
    private final LocalGeoDatabase localGeoDatabase;
    private final Map<String, CacheEntry<FetchResult>> buildingFetchCache = new ConcurrentHashMap<>();

    public ShadowService(ObjectMapper objectMapper,
                         SolarPositionCalculator solarPositionCalculator,
                         LocalGeoDatabase localGeoDatabase) {
        this.objectMapper = objectMapper;
        this.solarPositionCalculator = solarPositionCalculator;
        this.localGeoDatabase = localGeoDatabase;
    }

    public ShadowResponse findShadows(ShadowRequest request) {
        Bounds bounds = resolveBounds(request);
        List<Map<String, Object>> buildingFeatures = fetchBuildingsForShadowRequest(request, bounds);
        System.out.printf(
                "[SHADOW RESPONSE BUILDINGS] bounds=%s, routePoints=%d, buildings=%,d%n",
                bounds,
                request.getRoutePoints() == null ? 0 : request.getRoutePoints().size(),
                buildingFeatures.size()
        );
        ZonedDateTime departureTime = resolveDepartureTime(request.getDepartureTime());
        RoutePoint center = centerPoint(bounds);
        SolarPosition solarPosition = solarPositionCalculator.calculate(center, departureTime);

        List<Map<String, Object>> buildingShadows = buildingShadowFeatures(buildingFeatures, solarPosition);

        List<Map<String, Object>> treeShadows = treeShadowFeatures(request, solarPosition);
        return new ShadowResponse(
                buildingFeatures.size(),
                buildingShadows.size(),
                treeShadows.size(),
                featureCollection(buildingFeatures),
                featureCollection(buildingShadows),
                featureCollection(treeShadows)
        );
    }

    public double calculateRouteShadeCoverage(List<RoutePoint> routePoints, String departureTimeValue) {
        return calculateRouteShadeCoverages(List.of(routePoints), departureTimeValue).stream()
                .findFirst()
                .orElse(0.0);
    }

    public List<Double> calculateRouteShadeCoverages(List<List<RoutePoint>> routes, String departureTimeValue) {
        return calculateRouteShadeAnalyses(routes, departureTimeValue).stream()
                .map(RouteShadeAnalysis::shadeCoverage)
                .toList();
    }

    public int warmUpBuildingBounds(String name, double minLat, double minLon, double maxLat, double maxLon) {
        Bounds bounds = new Bounds(minLat, minLon, maxLat, maxLon).normalized();
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> features = fetchBuildingsForBounds(bounds);
        System.out.printf(
                "Building warm-up completed for %s in %d ms: %,d buildings, bounds=%s%n",
                name,
                System.currentTimeMillis() - startedAt,
                features.size(),
                bounds
        );
        return features.size();
    }

    public List<RouteShadeAnalysis> calculateRouteShadeAnalyses(List<List<RoutePoint>> routes, String departureTimeValue) {
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }

        Bounds bounds = routeBounds(routes.stream()
                .flatMap(List::stream)
                .toList());
        List<Map<String, Object>> buildingFeatures = fetchBuildingsAlongRoutes(routes, bounds);
        ZonedDateTime departureTime = resolveDepartureTime(departureTimeValue);
        SolarPosition solarPosition = solarPositionCalculator.calculate(centerPoint(bounds), departureTime);
        List<Map<String, Object>> shadowFeatures = buildingShadowFeatures(buildingFeatures, solarPosition);
        List<CompiledShadowFeature> compiledShadowFeatures = compileShadowFeatures(shadowFeatures);

        List<RouteShadeAnalysis> analyses = new ArrayList<>();
        for (List<RoutePoint> routePoints : routes) {
            analyses.add(analyzeRouteShadeCoverage(routePoints, solarPosition, compiledShadowFeatures));
        }
        return analyses;
    }

    public List<RouteSegmentShade> calculateSegmentShadeAnalyses(List<List<RoutePoint>> segments, String departureTimeValue) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }

        Bounds bounds = routeBounds(segments.stream()
                .flatMap(List::stream)
                .toList());
        List<Map<String, Object>> buildingFeatures = fetchBuildingsForBounds(bounds);
        ZonedDateTime departureTime = resolveDepartureTime(departureTimeValue);
        SolarPosition solarPosition = solarPositionCalculator.calculate(centerPoint(bounds), departureTime);
        List<CompiledShadowFeature> compiledShadowFeatures = compileShadowFeatures(
                buildingShadowFeatures(buildingFeatures, solarPosition)
        );
        CompiledShadowIndex shadowIndex = CompiledShadowIndex.from(compiledShadowFeatures);

        List<RouteSegmentShade> analyses = new ArrayList<>();
        for (List<RoutePoint> segment : segments) {
            if (segment == null || segment.size() < 2 || compiledShadowFeatures.isEmpty()) {
                RoutePoint start = segment == null || segment.isEmpty() ? new RoutePoint(0.0, 0.0) : segment.get(0);
                RoutePoint end = segment == null || segment.size() < 2 ? start : segment.get(1);
                analyses.add(new RouteSegmentShade(start, end, distanceMeters(start, end), 0.0));
                continue;
            }
            analyses.add(analyzeSegmentShade(segment.get(0), segment.get(1), shadowIndex));
        }
        return analyses;
    }

    private double calculateRouteShadeCoverage(List<RoutePoint> routePoints,
                                               SolarPosition solarPosition,
                                               List<CompiledShadowFeature> baseShadowFeatures) {
        return analyzeRouteShadeCoverage(routePoints, solarPosition, baseShadowFeatures).shadeCoverage();
    }

    private RouteShadeAnalysis analyzeRouteShadeCoverage(List<RoutePoint> routePoints,
                                                         SolarPosition solarPosition,
                                                         List<CompiledShadowFeature> baseShadowFeatures) {
        if (routePoints == null || routePoints.size() < 2) {
            return new RouteShadeAnalysis(0.0, List.of());
        }
        if (solarPosition.elevationDegrees() <= 0.0) {
            return new RouteShadeAnalysis(1.0, fullShadeSegmentAnalyses(routePoints));
        }

        List<CompiledShadowFeature> shadowFeatures = new ArrayList<>(baseShadowFeatures);

        ShadowRequest treeRequest = new ShadowRequest();
        treeRequest.setRoutePoints(routePoints);
        shadowFeatures.addAll(compileShadowFeatures(treeShadowFeatures(treeRequest, solarPosition)));

        if (shadowFeatures.isEmpty()) {
            return new RouteShadeAnalysis(0.0, emptySegmentAnalyses(routePoints));
        }
        CompiledShadowIndex shadowIndex = CompiledShadowIndex.from(shadowFeatures);

        List<RouteSegmentShade> segments = new ArrayList<>();
        double shadedDistance = 0.0;
        double totalDistance = 0.0;
        for (int i = 1; i < routePoints.size(); i++) {
            RouteSegmentShade segment = analyzeSegmentShade(routePoints.get(i - 1), routePoints.get(i), shadowIndex);
            segments.add(segment);
            shadedDistance += segment.distanceMeters() * segment.shadeCoverage();
            totalDistance += segment.distanceMeters();
        }
        double shadeCoverage = totalDistance <= 0.0 ? 0.0 : shadedDistance / totalDistance;
        return new RouteShadeAnalysis(shadeCoverage, segments);
    }

    private RouteSegmentShade analyzeSegmentShade(RoutePoint start,
                                                  RoutePoint end,
                                                  CompiledShadowIndex shadowIndex) {
        double distance = distanceMeters(start, end);
        int steps = Math.max(1, (int) Math.ceil(distance / 8.0));
        int shaded = 0;
        for (int step = 1; step <= steps; step++) {
            double ratio = step / (double) steps;
            RoutePoint sample = new RoutePoint(
                    start.getLat() + (end.getLat() - start.getLat()) * ratio,
                    start.getLon() + (end.getLon() - start.getLon()) * ratio
            );
            if (shadowIndex.contains(sample)) {
                shaded++;
            }
        }
        return new RouteSegmentShade(start, end, distance, shaded / (double) steps);
    }

    private List<RouteSegmentShade> emptySegmentAnalyses(List<RoutePoint> routePoints) {
        List<RouteSegmentShade> segments = new ArrayList<>();
        for (int i = 1; i < routePoints.size(); i++) {
            RoutePoint start = routePoints.get(i - 1);
            RoutePoint end = routePoints.get(i);
            segments.add(new RouteSegmentShade(start, end, distanceMeters(start, end), 0.0));
        }
        return segments;
    }

    private List<RouteSegmentShade> fullShadeSegmentAnalyses(List<RoutePoint> routePoints) {
        List<RouteSegmentShade> segments = new ArrayList<>();
        for (int i = 1; i < routePoints.size(); i++) {
            RoutePoint start = routePoints.get(i - 1);
            RoutePoint end = routePoints.get(i);
            segments.add(new RouteSegmentShade(start, end, distanceMeters(start, end), 1.0));
        }
        return segments;
    }

    private List<Map<String, Object>> buildingShadowFeatures(List<Map<String, Object>> buildingFeatures,
                                                             SolarPosition solarPosition) {
        List<Map<String, Object>> buildingShadows = new ArrayList<>();
        for (Map<String, Object> feature : buildingFeatures) {
            Map<String, Object> geometry = objectMap(feature.get("geometry"));
            double height = buildingHeightMeters(objectMap(feature.get("properties")));
            Map<String, Object> shadowGeometry = shadowGeometry(geometry, solarPosition, height);
            if (shadowGeometry != null) {
                buildingShadows.add(feature(shadowGeometry, properties(
                        "type", "building-shadow",
                        "heightMeters", round(height),
                        "solarElevation", round(solarPosition.elevationDegrees()),
                        "solarAzimuth", round(solarPosition.azimuthDegrees())
                )));
            }
        }
        return buildingShadows;
    }

    private List<Map<String, Object>> fetchBuildingsForShadowRequest(ShadowRequest request, Bounds bounds) {
        if (request.getRoutePoints() != null && !request.getRoutePoints().isEmpty()) {
            System.out.println("[SHADOW FETCH MODE] routePoints");
            return fetchBuildingsAlongRoute(request.getRoutePoints(), bounds);
        }

        System.out.println("[SHADOW FETCH MODE] bounds");
        return fetchBuildingsForBounds(bounds);
    }

    private List<Map<String, Object>> fetchBuildingsAlongRoutes(List<List<RoutePoint>> routes, Bounds fallbackBounds) {
        if (routes == null || routes.isEmpty()) {
            return fetchBuildingsForBounds(fallbackBounds);
        }

        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (List<RoutePoint> route : routes) {
            for (Map<String, Object> feature : fetchBuildingsAlongRoute(route, fallbackBounds)) {
                deduped.putIfAbsent(featureKey(feature), feature);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private List<Map<String, Object>> fetchBuildingsAlongRoute(List<RoutePoint> routePoints, Bounds fallbackBounds) {
        if (routePoints == null || routePoints.isEmpty()) {
            return fetchBuildingsForBounds(fallbackBounds);
        }

        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();

        for (Bounds routeTile : routeTiles(routePoints)) {
            for (Bounds safeTile : splitBounds(routeTile, AREA_TILE_SPAN_DEGREES)) {
                collectBuildingsForTile(safeTile, 0, deduped);
            }
        }

        return new ArrayList<>(deduped.values());
    }

    private List<Map<String, Object>> fetchBuildingsForBounds(Bounds bounds) {
        if (!bounds.isValid()) {
            return List.of();
        }

        double latSpan = bounds.latSpan();
        double lonSpan = bounds.lonSpan();
        if (latSpan <= AREA_TILE_SPAN_DEGREES && lonSpan <= AREA_TILE_SPAN_DEGREES) {
            return fetchVWorldBuildings(bounds);
        }

        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Bounds tile : splitBounds(bounds, AREA_TILE_SPAN_DEGREES)) {
            collectBuildingsForTile(tile, 0, deduped);
        }
        return new ArrayList<>(deduped.values());
    }

    private List<Map<String, Object>> fetchVWorldBuildings(Bounds bounds) {
        return fetchVWorldBuildingsResult(bounds).features();
    }

    private FetchResult fetchVWorldBuildingsResult(Bounds bounds) {
        String cacheKey = bounds.cacheKey();
        FetchResult cached = cachedFetchResult(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (localGeoDatabase.isEnabled()) {
            var storedFeatures = localGeoDatabase.findBuildingTile(cacheKey);
            if (storedFeatures.isPresent()) {
                System.out.printf("[BUILDING DB HIT] cacheKey=%s, features=%,d%n", cacheKey, storedFeatures.get().size());
                FetchResult result = new FetchResult(storedFeatures.get(), false);
                cacheFetchResult(cacheKey, result);
                return result;
            }
            System.out.printf("[BUILDING DB MISS] cacheKey=%s, bounds=%s%n", cacheKey, bounds);
        }

        List<Map<String, Object>> features = new ArrayList<>();
        boolean truncated = false;
        try {
            int page = 1;
            int totalPages = 1;
            while (page <= totalPages && page <= VWORLD_MAX_PAGES) {
                String url = vworldUrl(bounds, page);
                System.out.printf("[VWORLD REQUEST] page=%d, bounds=%s%n", page, bounds);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json")
                        .header("User-Agent", "capstone-server/1.0")
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP_CLIENT.send(
                        httpRequest,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    System.out.printf("VWorld building fetch HTTP %d for bounds %s%n", response.statusCode(), bounds);
                    return new FetchResult(features, false);
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode responseNode = root.path("response");
                String status = responseNode.path("status").asText();
                if (!"OK".equalsIgnoreCase(status)) {
                    System.out.printf("VWorld building fetch failed for bounds %s: %s%n",
                            bounds,
                            responseNode.path("error").path("text").asText(response.body()));
                    if ("NOT_FOUND".equalsIgnoreCase(status)) {
                        return saveAndCacheFetchResult(cacheKey, bounds, features, false);
                    }
                    return new FetchResult(features, false);
                }

                JsonNode featureCollection = responseNode.path("result").path("featureCollection");
                JsonNode pageNode = responseNode.path("page");
                totalPages = Math.max(1, pageNode.path("total").asInt(1));
                truncated = totalPages > VWORLD_MAX_PAGES;
                JsonNode featureNodes = featureCollection.path("features");
                if (featureNodes.isArray()) {
                    for (JsonNode featureNode : featureNodes) {
                        Map<String, Object> feature = objectMapper.convertValue(featureNode, Map.class);
                        if (feature.get("geometry") != null) {
                            feature.put("properties", normalizeBuildingProperties(objectMap(feature.get("properties"))));
                            features.add(feature);
                        }
                    }
                }
                page++;
            }
        } catch (Exception exception) {
            System.out.printf("VWorld building fetch exception for bounds %s: %s%n", bounds, exception.getMessage());
            return new FetchResult(features, false);
        }
        return saveAndCacheFetchResult(cacheKey, bounds, features, truncated);
    }

    private FetchResult saveAndCacheFetchResult(String cacheKey, Bounds bounds, List<Map<String, Object>> features, boolean truncated) {
        System.out.printf("[BUILDING SAVE] cacheKey=%s, features=%,d, truncated=%s%n", cacheKey, features.size(), truncated);
        FetchResult result = new FetchResult(features, truncated);
        cacheFetchResult(cacheKey, result);
        localGeoDatabase.saveBuildingTile(
                cacheKey,
                bounds.minLat(),
                bounds.minLon(),
                bounds.maxLat(),
                bounds.maxLon(),
                features,
                truncated
        );
        return result;
    }

    private FetchResult cachedFetchResult(String cacheKey) {
        CacheEntry<FetchResult> entry = buildingFetchCache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            buildingFetchCache.remove(cacheKey, entry);
            return null;
        }
        return entry.value();
    }

    private void cacheFetchResult(String cacheKey, FetchResult result) {
        if (buildingFetchCache.size() >= BUILDING_FETCH_CACHE_MAX_ENTRIES) {
            purgeExpiredFetchCacheEntries();
            if (buildingFetchCache.size() >= BUILDING_FETCH_CACHE_MAX_ENTRIES) {
                String oldestKey = null;
                long oldestLoadedAt = Long.MAX_VALUE;
                for (Map.Entry<String, CacheEntry<FetchResult>> entry : buildingFetchCache.entrySet()) {
                    if (entry.getValue().loadedAtMillis() < oldestLoadedAt) {
                        oldestLoadedAt = entry.getValue().loadedAtMillis();
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey != null) {
                    buildingFetchCache.remove(oldestKey);
                }
            }
        }
        buildingFetchCache.put(cacheKey, new CacheEntry<>(result, System.currentTimeMillis()));
    }

    private void purgeExpiredFetchCacheEntries() {
        buildingFetchCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private List<Bounds> splitBounds(Bounds bounds, double tileSpan) {
        List<Bounds> tiles = new ArrayList<>();
        for (double minLat = bounds.minLat(); minLat < bounds.maxLat(); minLat += tileSpan) {
            double maxLat = Math.min(bounds.maxLat(), minLat + tileSpan);
            for (double minLon = bounds.minLon(); minLon < bounds.maxLon(); minLon += tileSpan) {
                double maxLon = Math.min(bounds.maxLon(), minLon + tileSpan);
                tiles.add(new Bounds(minLat, minLon, maxLat, maxLon));
            }
        }
        return tiles;
    }

    private List<Bounds> routeTiles(List<RoutePoint> routePoints) {
        List<Bounds> tiles = new ArrayList<>();
        List<RoutePoint> chunk = new ArrayList<>();
        chunk.add(routePoints.get(0));
        double accumulatedDistance = 0.0;

        for (int i = 1; i < routePoints.size(); i++) {
            RoutePoint previous = routePoints.get(i - 1);
            RoutePoint current = routePoints.get(i);
            accumulatedDistance += distanceMeters(previous, current);
            chunk.add(current);

            if (accumulatedDistance >= ROUTE_TILE_CHUNK_METERS) {
                tiles.add(boundsAroundChunk(chunk, ROUTE_TILE_PADDING_METERS));
                chunk = new ArrayList<>();
                chunk.add(current);
                accumulatedDistance = 0.0;
            }
        }

        if (!chunk.isEmpty()) {
            tiles.add(boundsAroundChunk(chunk, ROUTE_TILE_PADDING_METERS));
        }
        return mergeOverlappingBounds(tiles);
    }

    private Bounds boundsAroundChunk(List<RoutePoint> points, double paddingMeters) {
        double minLat = Double.POSITIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (RoutePoint point : points) {
            minLat = Math.min(minLat, point.getLat());
            minLon = Math.min(minLon, point.getLon());
            maxLat = Math.max(maxLat, point.getLat());
            maxLon = Math.max(maxLon, point.getLon());
        }

        RoutePoint center = new RoutePoint((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0);
        double latDelta = paddingMeters / 111_320.0;
        double lonDelta = paddingMeters / (111_320.0 * Math.cos(Math.toRadians(center.getLat())));
        return new Bounds(minLat - latDelta, minLon - lonDelta, maxLat + latDelta, maxLon + lonDelta).normalized();
    }

    private List<Bounds> mergeOverlappingBounds(List<Bounds> boundsList) {
        List<Bounds> merged = new ArrayList<>();
        for (Bounds candidate : boundsList) {
            boolean mergedExisting = false;
            for (int i = 0; i < merged.size(); i++) {
                Bounds current = merged.get(i);
                if (current.intersects(candidate)) {
                    merged.set(i, current.union(candidate));
                    mergedExisting = true;
                    break;
                }
            }
            if (!mergedExisting) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private void collectBuildingsForTile(Bounds tile, int depth, Map<String, Map<String, Object>> deduped) {
        FetchResult result = fetchVWorldBuildingsResult(tile);
        for (Map<String, Object> feature : result.features()) {
            deduped.putIfAbsent(featureKey(feature), feature);
        }

        if (!result.truncated() || depth >= ADAPTIVE_SPLIT_MAX_DEPTH) {
            return;
        }

        for (Bounds child : splitBoundsAdaptive(tile)) {
            collectBuildingsForTile(child, depth + 1, deduped);
        }
    }

    private List<Bounds> splitBoundsAdaptive(Bounds bounds) {
        if (bounds.latSpan() >= bounds.lonSpan()) {
            double midLat = (bounds.minLat() + bounds.maxLat()) / 2.0;
            return List.of(
                    new Bounds(bounds.minLat(), bounds.minLon(), midLat, bounds.maxLon()),
                    new Bounds(midLat, bounds.minLon(), bounds.maxLat(), bounds.maxLon())
            );
        }

        double midLon = (bounds.minLon() + bounds.maxLon()) / 2.0;
        return List.of(
                new Bounds(bounds.minLat(), bounds.minLon(), bounds.maxLat(), midLon),
                new Bounds(bounds.minLat(), midLon, bounds.maxLat(), bounds.maxLon())
        );
    }

    private Bounds boundsAround(RoutePoint center, double radiusMeters) {
        double latDelta = radiusMeters / 111_320.0;
        double lonDelta = radiusMeters / (111_320.0 * Math.cos(Math.toRadians(center.getLat())));
        return new Bounds(
                center.getLat() - latDelta,
                center.getLon() - lonDelta,
                center.getLat() + latDelta,
                center.getLon() + lonDelta
        ).normalized();
    }

    private String featureKey(Map<String, Object> feature) {
        Map<String, Object> properties = objectMap(feature.get("properties"));
        Object primaryId = properties.get("id");
        if (primaryId == null) {
            primaryId = properties.get("pnu");
        }
        if (primaryId == null) {
            primaryId = properties.get("ufid");
        }
        if (primaryId != null) {
            return String.valueOf(primaryId);
        }
        try {
            return objectMapper.writeValueAsString(feature.get("geometry"));
        } catch (Exception exception) {
            return String.valueOf(feature.get("geometry"));
        }
    }

    private String vworldUrl(Bounds bounds, int page) {
        String box = String.format(
                "BOX(%s,%s,%s,%s)",
                bounds.minLon(),
                bounds.minLat(),
                bounds.maxLon(),
                bounds.maxLat()
        );
        return VWORLD_DATA_URL
                + "?service=data"
                + "&version=2.0"
                + "&request=GetFeature"
                + "&format=json"
                + "&data=LT_C_SPBD"
                + "&geomFilter=" + encode(box)
                + "&size=1000"
                + "&page=" + page
                + "&key=" + encode(ApiKeys.VWORLD_API_KEY);
    }

    private Map<String, Object> normalizeBuildingProperties(Map<String, Object> properties) {
        Map<String, Object> normalized = new LinkedHashMap<>(properties);
        normalized.put("layerType", "building");
        normalized.put("estimatedHeightMeters", round(buildingHeightMeters(properties)));
        normalized.put("heightSource", buildingHeightSource(properties));
        return normalized;
    }

    private double buildingHeightMeters(Map<String, Object> properties) {
        double height = number(properties.get("height"));
        if (height <= 0.0) {
            height = number(properties.get("heit"));
        }
        if (height <= 0.0) {
            height = number(properties.get("buld_hgt"));
        }
        if (height <= 0.0) {
            double floors = number(properties.get("gro_flo_co"));
            if (floors <= 0.0) {
                floors = number(properties.get("grnd_flr_cnt"));
            }
            if (floors <= 0.0) {
                floors = number(properties.get("floor"));
            }
            height = floors > 0.0 ? floors * 3.2 : 12.0;
        }
        return Math.max(3.0, Math.min(height, 120.0));
    }

    private String buildingHeightSource(Map<String, Object> properties) {
        if (number(properties.get("height")) > 0.0) {
            return "vworld-height";
        }
        if (number(properties.get("heit")) > 0.0) {
            return "vworld-heit";
        }
        if (number(properties.get("buld_hgt")) > 0.0) {
            return "vworld-buld_hgt";
        }
        if (number(properties.get("gro_flo_co")) > 0.0) {
            return "vworld-gro_flo_co-estimated";
        }
        if (number(properties.get("grnd_flr_cnt")) > 0.0) {
            return "vworld-grnd_flr_cnt-estimated";
        }
        if (number(properties.get("floor")) > 0.0) {
            return "vworld-floor-estimated";
        }
        return "default-estimated";
    }

    private Map<String, Object> shadowGeometry(Map<String, Object> geometry,
                                               SolarPosition solarPosition,
                                               double heightMeters) {
        if (solarPosition.elevationDegrees() <= 0.0) {
            return null;
        }
        double lengthMeters = Math.min(180.0, heightMeters / Math.tan(Math.toRadians(Math.max(5.0, solarPosition.elevationDegrees()))));
        double shadowBearing = normalizeDegrees(solarPosition.azimuthDegrees() + 180.0);
        return castShadowGeometry(geometry, shadowBearing, lengthMeters);
    }

    private Map<String, Object> castShadowGeometry(Map<String, Object> geometry, double bearingDegrees, double meters) {
        String type = String.valueOf(geometry.get("type"));
        Object coordinates = geometry.get("coordinates");
        if ("Polygon".equals(type)) {
            List<Object> shadowPolygon = castShadowPolygon(coordinates, bearingDegrees, meters);
            return shadowPolygon.isEmpty() ? null : geometry("Polygon", shadowPolygon);
        }
        if ("MultiPolygon".equals(type)) {
            List<Object> shadowPolygons = new ArrayList<>();
            if (coordinates instanceof List<?> polygons) {
                for (Object polygon : polygons) {
                    List<Object> shadowPolygon = castShadowPolygon(polygon, bearingDegrees, meters);
                    if (!shadowPolygon.isEmpty()) {
                        shadowPolygons.add(shadowPolygon);
                    }
                }
            }
            return shadowPolygons.isEmpty() ? null : geometry("MultiPolygon", shadowPolygons);
        }
        return null;
    }

    private List<Object> castShadowPolygon(Object polygon, double bearingDegrees, double meters) {
        List<List<Double>> exterior = exteriorRing(polygon);
        if (exterior.size() < 3) {
            return List.of();
        }

        List<List<Double>> points = new ArrayList<>(exterior);
        for (List<Double> point : exterior) {
            points.add(translateCoordinate(point, bearingDegrees, meters));
        }

        List<List<Double>> hull = convexHull(points);
        if (hull.size() < 3) {
            return List.of();
        }
        hull.add(hull.get(0));
        return List.of(new ArrayList<>(hull));
    }

    private Map<String, Object> translateGeometry(Map<String, Object> geometry, double bearingDegrees, double meters) {
        String type = String.valueOf(geometry.get("type"));
        Object coordinates = geometry.get("coordinates");
        if ("Polygon".equals(type)) {
            return geometry("Polygon", translatePolygon(coordinates, bearingDegrees, meters));
        }
        if ("MultiPolygon".equals(type)) {
            List<Object> translated = new ArrayList<>();
            if (coordinates instanceof List<?> polygons) {
                for (Object polygon : polygons) {
                    translated.add(translatePolygon(polygon, bearingDegrees, meters));
                }
            }
            return geometry("MultiPolygon", translated);
        }
        return null;
    }

    private List<Object> translatePolygon(Object polygon, double bearingDegrees, double meters) {
        List<Object> rings = new ArrayList<>();
        if (polygon instanceof List<?> sourceRings) {
            for (Object ring : sourceRings) {
                List<Object> translatedRing = new ArrayList<>();
                if (ring instanceof List<?> sourcePoints) {
                    for (Object point : sourcePoints) {
                        translatedRing.add(translateCoordinate(point, bearingDegrees, meters));
                    }
                }
                rings.add(translatedRing);
            }
        }
        return rings;
    }

    private List<Double> translateCoordinate(Object coordinate, double bearingDegrees, double meters) {
        if (!(coordinate instanceof List<?> values) || values.size() < 2) {
            return List.of(0.0, 0.0);
        }
        double lon = number(values.get(0));
        double lat = number(values.get(1));
        double radians = Math.toRadians(bearingDegrees);
        double northMeters = Math.cos(radians) * meters;
        double eastMeters = Math.sin(radians) * meters;
        double nextLat = lat + northMeters / 111_320.0;
        double nextLon = lon + eastMeters / (111_320.0 * Math.cos(Math.toRadians(lat)));
        return List.of(nextLon, nextLat);
    }

    private List<List<Double>> exteriorRing(Object polygon) {
        if (!(polygon instanceof List<?> rings) || rings.isEmpty() || !(rings.get(0) instanceof List<?> points)) {
            return List.of();
        }

        List<List<Double>> exterior = new ArrayList<>();
        for (Object point : points) {
            if (point instanceof List<?> values && values.size() >= 2) {
                exterior.add(List.of(number(values.get(0)), number(values.get(1))));
            }
        }
        if (exterior.size() > 1 && exterior.get(0).equals(exterior.get(exterior.size() - 1))) {
            exterior.remove(exterior.size() - 1);
        }
        return exterior;
    }

    private List<List<Double>> convexHull(List<List<Double>> points) {
        List<List<Double>> sorted = points.stream()
                .distinct()
                .sorted(Comparator.<List<Double>>comparingDouble(point -> point.get(0))
                        .thenComparingDouble(point -> point.get(1)))
                .toList();
        if (sorted.size() <= 1) {
            return new ArrayList<>(sorted);
        }

        List<List<Double>> lower = new ArrayList<>();
        for (List<Double> point : sorted) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), point) <= 0.0) {
                lower.remove(lower.size() - 1);
            }
            lower.add(point);
        }

        List<List<Double>> upper = new ArrayList<>();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            List<Double> point = sorted.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), point) <= 0.0) {
                upper.remove(upper.size() - 1);
            }
            upper.add(point);
        }

        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private double cross(List<Double> origin, List<Double> left, List<Double> right) {
        return (left.get(0) - origin.get(0)) * (right.get(1) - origin.get(1))
                - (left.get(1) - origin.get(1)) * (right.get(0) - origin.get(0));
    }

    private List<Map<String, Object>> treeShadowFeatures(ShadowRequest request, SolarPosition solarPosition) {
        List<RoutePoint> routePoints = request.getRoutePoints() == null ? List.of() : request.getRoutePoints();
        List<RoutePoint> samples = sampleRoute(routePoints, 35.0);
        List<Map<String, Object>> features = new ArrayList<>();
        for (int i = 0; i < samples.size(); i += 2) {
            RoutePoint tree = offset(samples.get(i), 90.0, 3.5);
            features.add(feature(treeShadowPolygon(tree, solarPosition), properties(
                    "type", "tree-shadow",
                    "estimatedTreeHeightMeters", 7.0
            )));
        }
        return features;
    }

    private Map<String, Object> treeShadowPolygon(RoutePoint tree, SolarPosition solarPosition) {
        double lengthMeters = Math.min(28.0, 7.0 / Math.tan(Math.toRadians(Math.max(8.0, solarPosition.elevationDegrees()))));
        double bearing = normalizeDegrees(solarPosition.azimuthDegrees() + 180.0);
        RoutePoint tip = offset(tree, bearing, lengthMeters);
        RoutePoint left = offset(tree, normalizeDegrees(bearing - 90.0), 2.5);
        RoutePoint right = offset(tree, normalizeDegrees(bearing + 90.0), 2.5);
        RoutePoint tipLeft = offset(tip, normalizeDegrees(bearing - 90.0), 1.4);
        RoutePoint tipRight = offset(tip, normalizeDegrees(bearing + 90.0), 1.4);
        return geometry("Polygon", List.of(List.of(
                lonLat(left),
                lonLat(tipLeft),
                lonLat(tipRight),
                lonLat(right),
                lonLat(left)
        )));
    }

    private List<RoutePoint> sampleRoute(List<RoutePoint> routePoints, double intervalMeters) {
        List<RoutePoint> samples = new ArrayList<>();
        if (routePoints.isEmpty()) {
            return samples;
        }
        samples.add(routePoints.get(0));
        for (int i = 1; i < routePoints.size(); i++) {
            RoutePoint start = routePoints.get(i - 1);
            RoutePoint end = routePoints.get(i);
            double distance = distanceMeters(start, end);
            int steps = Math.max(1, (int) Math.ceil(distance / intervalMeters));
            for (int step = 1; step <= steps; step++) {
                double ratio = step / (double) steps;
                samples.add(new RoutePoint(
                        start.getLat() + (end.getLat() - start.getLat()) * ratio,
                        start.getLon() + (end.getLon() - start.getLon()) * ratio
                ));
            }
        }
        return samples;
    }

    private List<CompiledShadowFeature> compileShadowFeatures(List<Map<String, Object>> shadowFeatures) {
        List<CompiledShadowFeature> compiled = new ArrayList<>();
        for (Map<String, Object> feature : shadowFeatures) {
            CompiledShadowFeature compiledFeature = compileShadowFeature(objectMap(feature.get("geometry")));
            if (compiledFeature != null) {
                compiled.add(compiledFeature);
            }
        }
        return compiled;
    }

    private CompiledShadowFeature compileShadowFeature(Map<String, Object> geometry) {
        String type = String.valueOf(geometry.get("type"));
        Object coordinates = geometry.get("coordinates");
        List<CompiledPolygon> polygons = new ArrayList<>();
        if ("Polygon".equals(type)) {
            CompiledPolygon polygon = compilePolygon(coordinates);
            if (polygon != null) {
                polygons.add(polygon);
            }
        } else if ("MultiPolygon".equals(type) && coordinates instanceof List<?> rawPolygons) {
            for (Object polygonCoordinates : rawPolygons) {
                CompiledPolygon polygon = compilePolygon(polygonCoordinates);
                if (polygon != null) {
                    polygons.add(polygon);
                }
            }
        }
        return polygons.isEmpty() ? null : new CompiledShadowFeature(polygons);
    }

    private CompiledPolygon compilePolygon(Object polygon) {
        if (!(polygon instanceof List<?> rings) || rings.isEmpty()) {
            return null;
        }

        List<List<List<Double>>> compiledRings = new ArrayList<>();
        Bounds bounds = null;
        for (Object ring : rings) {
            List<List<Double>> compiledRing = compileRing(ring);
            if (compiledRing.size() < 3) {
                continue;
            }
            compiledRings.add(compiledRing);
            Bounds ringBounds = ringBounds(compiledRing);
            bounds = bounds == null ? ringBounds : bounds.union(ringBounds);
        }
        if (compiledRings.isEmpty() || bounds == null) {
            return null;
        }
        return new CompiledPolygon(bounds, compiledRings);
    }

    private List<List<Double>> compileRing(Object ring) {
        if (!(ring instanceof List<?> coordinates)) {
            return List.of();
        }
        List<List<Double>> compiled = new ArrayList<>();
        for (Object coordinate : coordinates) {
            if (coordinate instanceof List<?> values && values.size() >= 2) {
                compiled.add(List.of(number(values.get(0)), number(values.get(1))));
            }
        }
        return compiled;
    }

    private Bounds ringBounds(List<List<Double>> ring) {
        double minLat = Double.POSITIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (List<Double> coordinate : ring) {
            double lon = coordinate.get(0);
            double lat = coordinate.get(1);
            minLat = Math.min(minLat, lat);
            minLon = Math.min(minLon, lon);
            maxLat = Math.max(maxLat, lat);
            maxLon = Math.max(maxLon, lon);
        }
        return new Bounds(minLat, minLon, maxLat, maxLon).normalized();
    }

    private boolean containsPoint(Map<String, Object> geometry, RoutePoint point) {
        String type = String.valueOf(geometry.get("type"));
        Object coordinates = geometry.get("coordinates");
        if ("Polygon".equals(type)) {
            return containsPointInPolygon(coordinates, point);
        }
        if ("MultiPolygon".equals(type) && coordinates instanceof List<?> polygons) {
            for (Object polygon : polygons) {
                if (containsPointInPolygon(polygon, point)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsPointInPolygon(Object polygon, RoutePoint point) {
        if (!(polygon instanceof List<?> rings) || rings.isEmpty()) {
            return false;
        }
        if (!containsPointInRing(rings.get(0), point)) {
            return false;
        }
        for (int i = 1; i < rings.size(); i++) {
            if (containsPointInRing(rings.get(i), point)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsPointInRing(Object ring, RoutePoint point) {
        if (!(ring instanceof List<?> coordinates) || coordinates.size() < 3) {
            return false;
        }

        double x = point.getLon();
        double y = point.getLat();
        boolean inside = false;
        int previousIndex = coordinates.size() - 1;
        for (int currentIndex = 0; currentIndex < coordinates.size(); currentIndex++) {
            List<Double> current = coordinatePair(coordinates.get(currentIndex));
            List<Double> previous = coordinatePair(coordinates.get(previousIndex));
            double xi = current.get(0);
            double yi = current.get(1);
            double xj = previous.get(0);
            double yj = previous.get(1);
            double denominator = yj - yi;
            if (Math.abs(denominator) < 0.0000000001) {
                denominator = denominator < 0.0 ? -0.0000000001 : 0.0000000001;
            }
            boolean intersects = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / denominator + xi);
            if (intersects) {
                inside = !inside;
            }
            previousIndex = currentIndex;
        }
        return inside;
    }

    private List<Double> coordinatePair(Object coordinate) {
        if (coordinate instanceof List<?> values && values.size() >= 2) {
            return List.of(number(values.get(0)), number(values.get(1)));
        }
        return List.of(0.0, 0.0);
    }

    private RoutePoint offset(RoutePoint point, double bearingDegrees, double meters) {
        double radians = Math.toRadians(bearingDegrees);
        double northMeters = Math.cos(radians) * meters;
        double eastMeters = Math.sin(radians) * meters;
        double lat = point.getLat() + northMeters / 111_320.0;
        double lon = point.getLon() + eastMeters / (111_320.0 * Math.cos(Math.toRadians(point.getLat())));
        return new RoutePoint(lat, lon);
    }

    private double distanceMeters(RoutePoint a, RoutePoint b) {
        double lat1 = Math.toRadians(a.getLat());
        double lat2 = Math.toRadians(b.getLat());
        double deltaLat = Math.toRadians(b.getLat() - a.getLat());
        double deltaLon = Math.toRadians(b.getLon() - a.getLon());
        double haversine = Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2.0) * Math.sin(deltaLon / 2.0);
        return 6_371_000.0 * 2.0 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1.0 - haversine));
    }

    private Bounds resolveBounds(ShadowRequest request) {
        Bounds requested = new Bounds(
                safeNumber(request.getMinLat()),
                safeNumber(request.getMinLon()),
                safeNumber(request.getMaxLat()),
                safeNumber(request.getMaxLon())
        ).normalized();
        Bounds routeBounds = routeBounds(request.getRoutePoints());
        if (!requested.isValid()) {
            return routeBounds;
        }
        if (requested.latSpan() > 0.04 || requested.lonSpan() > 0.04) {
            return routeBounds.isValid() ? routeBounds : requested.centerCropped(0.03);
        }
        return requested;
    }

    private Bounds routeBounds(List<RoutePoint> routePoints) {
        if (routePoints == null || routePoints.isEmpty()) {
            return new Bounds(0.0, 0.0, 0.0, 0.0);
        }
        double minLat = Double.POSITIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (RoutePoint point : routePoints) {
            minLat = Math.min(minLat, point.getLat());
            minLon = Math.min(minLon, point.getLon());
            maxLat = Math.max(maxLat, point.getLat());
            maxLon = Math.max(maxLon, point.getLon());
        }
        double padding = 0.003;
        return new Bounds(minLat - padding, minLon - padding, maxLat + padding, maxLon + padding).normalized();
    }

    private RoutePoint centerPoint(Bounds bounds) {
        return new RoutePoint(
                (bounds.minLat() + bounds.maxLat()) / 2.0,
                (bounds.minLon() + bounds.maxLon()) / 2.0
        );
    }

    private ZonedDateTime resolveDepartureTime(String value) {
        if (value == null || value.isBlank()) {
            return ZonedDateTime.now(SEOUL_ZONE);
        }
        try {
            return LocalDateTime.parse(value).atZone(SEOUL_ZONE);
        } catch (Exception ignored) {
            return ZonedDateTime.now(SEOUL_ZONE);
        }
    }

    private Map<String, Object> featureCollection(List<Map<String, Object>> features) {
        return Map.of(
                "type", "FeatureCollection",
                "features", features
        );
    }

    private Map<String, Object> feature(Map<String, Object> geometry, Map<String, Object> properties) {
        return Map.of(
                "type", "Feature",
                "geometry", geometry,
                "properties", properties
        );
    }

    private Map<String, Object> geometry(String type, Object coordinates) {
        return Map.of(
                "type", type,
                "coordinates", coordinates
        );
    }

    private Map<String, Object> properties(Object... values) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < values.length - 1; i += 2) {
            properties.put(String.valueOf(values[i]), values[i + 1]);
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private List<Double> lonLat(RoutePoint point) {
        return List.of(point.getLon(), point.getLat());
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private double safeNumber(Double value) {
        return value == null ? 0.0 : value;
    }

    private double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0 ? result + 360.0 : result;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Bounds(double minLat, double minLon, double maxLat, double maxLon) {
        private Bounds normalized() {
            return new Bounds(
                    Math.min(minLat, maxLat),
                    Math.min(minLon, maxLon),
                    Math.max(minLat, maxLat),
                    Math.max(minLon, maxLon)
            );
        }

        private boolean isValid() {
            return minLat >= 30.0
                    && maxLat <= 40.0
                    && minLon >= 120.0
                    && maxLon <= 135.0
                    && latSpan() > 0.0
                    && lonSpan() > 0.0;
        }

        private double latSpan() {
            return maxLat - minLat;
        }

        private double lonSpan() {
            return maxLon - minLon;
        }

        private boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }

        private String cacheKey() {
            return roundKey(minLat) + "," + roundKey(minLon) + "," + roundKey(maxLat) + "," + roundKey(maxLon);
        }

        private double roundKey(double value) {
            return Math.round(value * 10000.0) / 10000.0;
        }

        private boolean intersects(Bounds other) {
            return minLat <= other.maxLat
                    && maxLat >= other.minLat
                    && minLon <= other.maxLon
                    && maxLon >= other.minLon;
        }

        private Bounds union(Bounds other) {
            return new Bounds(
                    Math.min(minLat, other.minLat),
                    Math.min(minLon, other.minLon),
                    Math.max(maxLat, other.maxLat),
                    Math.max(maxLon, other.maxLon)
            );
        }

        private Bounds centerCropped(double span) {
            double centerLat = (minLat + maxLat) / 2.0;
            double centerLon = (minLon + maxLon) / 2.0;
            double half = span / 2.0;
            return new Bounds(centerLat - half, centerLon - half, centerLat + half, centerLon + half);
        }
    }

    private record FetchResult(List<Map<String, Object>> features, boolean truncated) {
    }

    private record CacheEntry<T>(T value, long loadedAtMillis) {
        private boolean isExpired() {
            return System.currentTimeMillis() - loadedAtMillis > BUILDING_FETCH_CACHE_TTL.toMillis();
        }
    }

    private record CompiledShadowIndex(Map<ShadowCellKey, List<CompiledShadowFeature>> cells,
                                       List<CompiledShadowFeature> fallbackFeatures) {
        private static CompiledShadowIndex from(List<CompiledShadowFeature> features) {
            Map<ShadowCellKey, List<CompiledShadowFeature>> cells = new HashMap<>();
            List<CompiledShadowFeature> fallbackFeatures = new ArrayList<>();
            for (CompiledShadowFeature feature : features) {
                Bounds bounds = feature.bounds();
                if (!bounds.isValid()) {
                    fallbackFeatures.add(feature);
                    continue;
                }

                int minLatCell = ShadowCellKey.index(bounds.minLat());
                int maxLatCell = ShadowCellKey.index(bounds.maxLat());
                int minLonCell = ShadowCellKey.index(bounds.minLon());
                int maxLonCell = ShadowCellKey.index(bounds.maxLon());
                int cellCount = (maxLatCell - minLatCell + 1) * (maxLonCell - minLonCell + 1);
                if (cellCount > 256) {
                    fallbackFeatures.add(feature);
                    continue;
                }

                for (int latCell = minLatCell; latCell <= maxLatCell; latCell++) {
                    for (int lonCell = minLonCell; lonCell <= maxLonCell; lonCell++) {
                        cells.computeIfAbsent(new ShadowCellKey(latCell, lonCell), ignored -> new ArrayList<>())
                                .add(feature);
                    }
                }
            }
            return new CompiledShadowIndex(cells, fallbackFeatures);
        }

        private boolean contains(RoutePoint point) {
            for (CompiledShadowFeature feature : fallbackFeatures) {
                if (feature.contains(point)) {
                    return true;
                }
            }
            for (CompiledShadowFeature feature : cells.getOrDefault(ShadowCellKey.from(point), List.of())) {
                if (feature.contains(point)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record ShadowCellKey(int latCell, int lonCell) {
        private static ShadowCellKey from(RoutePoint point) {
            return new ShadowCellKey(index(point.getLat()), index(point.getLon()));
        }

        private static int index(double value) {
            return (int) Math.floor(value / SHADOW_INDEX_CELL_DEGREES);
        }
    }

    private record CompiledShadowFeature(List<CompiledPolygon> polygons) {
        private Bounds bounds() {
            Bounds bounds = null;
            for (CompiledPolygon polygon : polygons) {
                bounds = bounds == null ? polygon.bounds() : bounds.union(polygon.bounds());
            }
            return bounds == null ? new Bounds(0.0, 0.0, 0.0, 0.0) : bounds;
        }

        private boolean contains(RoutePoint point) {
            for (CompiledPolygon polygon : polygons) {
                if (polygon.contains(point)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record CompiledPolygon(Bounds bounds, List<List<List<Double>>> rings) {
        private boolean contains(RoutePoint point) {
            if (!bounds.contains(point.getLat(), point.getLon())) {
                return false;
            }
            if (!containsPointInCompiledRing(rings.get(0), point)) {
                return false;
            }
            for (int i = 1; i < rings.size(); i++) {
                if (containsPointInCompiledRing(rings.get(i), point)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean containsPointInCompiledRing(List<List<Double>> coordinates, RoutePoint point) {
        if (coordinates.size() < 3) {
            return false;
        }

        double x = point.getLon();
        double y = point.getLat();
        boolean inside = false;
        int previousIndex = coordinates.size() - 1;
        for (int currentIndex = 0; currentIndex < coordinates.size(); currentIndex++) {
            List<Double> current = coordinates.get(currentIndex);
            List<Double> previous = coordinates.get(previousIndex);
            double xi = current.get(0);
            double yi = current.get(1);
            double xj = previous.get(0);
            double yj = previous.get(1);
            double denominator = yj - yi;
            if (Math.abs(denominator) < 0.0000000001) {
                denominator = denominator < 0.0 ? -0.0000000001 : 0.0000000001;
            }
            boolean intersects = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / denominator + xi);
            if (intersects) {
                inside = !inside;
            }
            previousIndex = currentIndex;
        }
        return inside;
    }

    public record RouteShadeAnalysis(double shadeCoverage, List<RouteSegmentShade> segments) {
    }

    public record RouteSegmentShade(RoutePoint start, RoutePoint end, double distanceMeters, double shadeCoverage) {
    }
}
