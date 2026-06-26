package com.example.capstone_server.service.geo;

import com.example.capstone_server.dto.RoutePoint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LocalGeoDatabase {
    private static final int BATCH_SIZE = 2_000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public LocalGeoDatabase(JdbcTemplate jdbcTemplate,
                            ObjectMapper objectMapper,
                            @Value("${capstone.local-db.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @PostConstruct
    public void initialize() {
        if (!enabled) {
            return;
        }

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS building_tiles (
                    cache_key VARCHAR(128) PRIMARY KEY,
                    min_lat DOUBLE,
                    min_lon DOUBLE,
                    max_lat DOUBLE,
                    max_lon DOUBLE,
                    truncated BOOLEAN,
                    fetched_at BIGINT
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS building_features (
                    feature_key VARCHAR(512) PRIMARY KEY,
                    min_lat DOUBLE,
                    min_lon DOUBLE,
                    max_lat DOUBLE,
                    max_lon DOUBLE,
                    feature_json LONGTEXT
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS building_tile_features (
                    cache_key VARCHAR(128),
                    feature_key VARCHAR(512),
                    PRIMARY KEY (cache_key, feature_key)
                )
                """);

        createIndexIfNotExists(
                "building_features",
                "idx_building_features_bbox",
                "CREATE INDEX idx_building_features_bbox ON building_features(min_lat, max_lat, min_lon, max_lon)"
        );

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS osm_nodes (
                    id BIGINT PRIMARY KEY,
                    lat DOUBLE,
                    lon DOUBLE,
                    lat_cell INT,
                    lon_cell INT
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS osm_edges (
                    from_id BIGINT,
                    to_id BIGINT,
                    distance DOUBLE,
                    PRIMARY KEY (from_id, to_id)
                )
                """);

        createIndexIfNotExists(
                "osm_nodes",
                "idx_osm_nodes_cell",
                "CREATE INDEX idx_osm_nodes_cell ON osm_nodes(lat_cell, lon_cell)"
        );

        createIndexIfNotExists(
                "osm_edges",
                "idx_osm_edges_from",
                "CREATE INDEX idx_osm_edges_from ON osm_edges(from_id)"
        );
    }

    private void createIndexIfNotExists(String tableName, String indexName, String createSql) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.statistics
                        WHERE table_schema = DATABASE()
                          AND table_name = ?
                          AND index_name = ?
                        """,
                Integer.class,
                tableName,
                indexName
        );

        if (count == null || count == 0) {
            jdbcTemplate.execute(createSql);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<List<Map<String, Object>>> findBuildingTile(String cacheKey) {
        if (!enabled) {
            return Optional.empty();
        }

        Integer tileCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM building_tiles WHERE cache_key = ?",
                Integer.class,
                cacheKey
        );

        if (tileCount == null || tileCount == 0) {
            return Optional.empty();
        }

        List<Map<String, Object>> features = jdbcTemplate.query(
                """
                        SELECT bf.feature_json
                        FROM building_tile_features btf
                        JOIN building_features bf ON bf.feature_key = btf.feature_key
                        WHERE btf.cache_key = ?
                        """,
                (rs, rowNum) -> readFeature(rs.getString("feature_json")),
                cacheKey
        );

        return Optional.of(features);
    }

    public void saveBuildingTile(String cacheKey,
                                 double minLat,
                                 double minLon,
                                 double maxLat,
                                 double maxLon,
                                 List<Map<String, Object>> features,
                                 boolean truncated) {
        if (!enabled) {
            return;
        }

        if (features == null || features.isEmpty()) {
            System.out.println("[BUILDING SAVE SKIPPED] empty feature list : " + cacheKey);
            return;
        }

        jdbcTemplate.update(
                """
                        INSERT INTO building_tiles(cache_key, min_lat, min_lon, max_lat, max_lon, truncated, fetched_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            min_lat = VALUES(min_lat),
                            min_lon = VALUES(min_lon),
                            max_lat = VALUES(max_lat),
                            max_lon = VALUES(max_lon),
                            truncated = VALUES(truncated),
                            fetched_at = VALUES(fetched_at)
                        """,
                cacheKey,
                minLat,
                minLon,
                maxLat,
                maxLon,
                truncated,
                System.currentTimeMillis()
        );

        jdbcTemplate.update("DELETE FROM building_tile_features WHERE cache_key = ?", cacheKey);

        List<Object[]> featureRows = new ArrayList<>();
        List<Object[]> relationRows = new ArrayList<>();

        for (Map<String, Object> feature : features) {
            String featureKey = stableFeatureKey(feature);
            Bounds bounds = featureBounds(feature);

            featureRows.add(new Object[]{
                    featureKey,
                    bounds.minLat(),
                    bounds.minLon(),
                    bounds.maxLat(),
                    bounds.maxLon(),
                    writeFeature(feature)
            });

            relationRows.add(new Object[]{cacheKey, featureKey});
        }

        batchUpdate(
                """
                        INSERT INTO building_features(feature_key, min_lat, min_lon, max_lat, max_lon, feature_json)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            min_lat = VALUES(min_lat),
                            min_lon = VALUES(min_lon),
                            max_lat = VALUES(max_lat),
                            max_lon = VALUES(max_lon),
                            feature_json = VALUES(feature_json)
                        """,
                featureRows
        );

        batchUpdate(
                """
                        INSERT INTO building_tile_features(cache_key, feature_key)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE
                            cache_key = VALUES(cache_key),
                            feature_key = VALUES(feature_key)
                        """,
                relationRows
        );
    }

    public boolean hasOsmData() {
        if (!enabled) {
            return false;
        }

        Integer nodeCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM osm_nodes", Integer.class);
        Integer edgeCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM osm_edges", Integer.class);

        return nodeCount != null && edgeCount != null && nodeCount > 0 && edgeCount > 0;
    }

    public void replaceOsmData(Map<Long, RoutePoint> points, List<OsmEdgeRecord> edges) {
        if (!enabled) {
            return;
        }

        jdbcTemplate.update("DELETE FROM osm_edges");
        jdbcTemplate.update("DELETE FROM osm_nodes");

        List<Object[]> nodeRows = new ArrayList<>(BATCH_SIZE);

        for (Map.Entry<Long, RoutePoint> entry : points.entrySet()) {
            RoutePoint point = entry.getValue();

            nodeRows.add(new Object[]{
                    entry.getKey(),
                    point.getLat(),
                    point.getLon(),
                    cell(point.getLat()),
                    cell(point.getLon())
            });

            if (nodeRows.size() >= BATCH_SIZE) {
                batchUpdate("""
                        INSERT INTO osm_nodes(id, lat, lon, lat_cell, lon_cell)
                        VALUES (?, ?, ?, ?, ?)
                        """, nodeRows);
                nodeRows.clear();
            }
        }

        batchUpdate("""
                INSERT INTO osm_nodes(id, lat, lon, lat_cell, lon_cell)
                VALUES (?, ?, ?, ?, ?)
                """, nodeRows);

        List<Object[]> edgeRows = new ArrayList<>(BATCH_SIZE);

        for (OsmEdgeRecord edge : edges) {
            edgeRows.add(new Object[]{
                    edge.fromId(),
                    edge.toId(),
                    edge.distance()
            });

            if (edgeRows.size() >= BATCH_SIZE) {
                batchUpdate("""
                        INSERT INTO osm_edges(from_id, to_id, distance)
                        VALUES (?, ?, ?)
                        """, edgeRows);
                edgeRows.clear();
            }
        }

        batchUpdate("""
                INSERT INTO osm_edges(from_id, to_id, distance)
                VALUES (?, ?, ?)
                """, edgeRows);
    }

    private void batchUpdate(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(sql, rows);
    }

    private Map<String, Object> readFeature(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String writeFeature(Map<String, Object> feature) {
        try {
            return objectMapper.writeValueAsString(feature);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String stableFeatureKey(Map<String, Object> feature) {
        Object properties = feature.get("properties");

        if (properties instanceof Map<?, ?> map) {
            Object id = firstPresent(map, "id", "pnu", "ufid", "buld_mnnm");

            if (id != null) {
                return String.valueOf(id);
            }
        }

        return Integer.toHexString(writeFeature(feature).hashCode());
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);

            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private Bounds featureBounds(Map<String, Object> feature) {
        BoundsBuilder builder = new BoundsBuilder();
        Object geometry = feature.get("geometry");

        if (geometry instanceof Map<?, ?> map) {
            collectCoordinateBounds(map.get("coordinates"), builder);
        }

        return builder.build();
    }

    private void collectCoordinateBounds(Object value, BoundsBuilder builder) {
        if (!(value instanceof List<?> list)) {
            return;
        }

        if (list.size() >= 2 && list.get(0) instanceof Number && list.get(1) instanceof Number) {
            builder.include(
                    ((Number) list.get(1)).doubleValue(),
                    ((Number) list.get(0)).doubleValue()
            );
            return;
        }

        for (Object entry : list) {
            collectCoordinateBounds(entry, builder);
        }
    }

    private int cell(double value) {
        return (int) Math.floor(value / 0.01);
    }

    public record OsmEdgeRecord(long fromId, long toId, double distance) {
    }

    private record Bounds(double minLat, double minLon, double maxLat, double maxLon) {
    }

    private static class BoundsBuilder {
        private double minLat = Double.POSITIVE_INFINITY;
        private double minLon = Double.POSITIVE_INFINITY;
        private double maxLat = Double.NEGATIVE_INFINITY;
        private double maxLon = Double.NEGATIVE_INFINITY;

        private void include(double lat, double lon) {
            minLat = Math.min(minLat, lat);
            minLon = Math.min(minLon, lon);
            maxLat = Math.max(maxLat, lat);
            maxLon = Math.max(maxLon, lon);
        }

        private Bounds build() {
            if (!Double.isFinite(minLat)) {
                return new Bounds(0.0, 0.0, 0.0, 0.0);
            }

            return new Bounds(minLat, minLon, maxLat, maxLon);
        }
    }
}