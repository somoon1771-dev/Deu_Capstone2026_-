package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.CctvPointDto;
import com.example.capstone_server.dto.RoutePoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CctvSafetyService {
    private static final double DEFAULT_RADIUS_METERS = 50.0;
    private static final int MAX_SAMPLE_COUNT = 40;

    private final JdbcTemplate jdbcTemplate;

    public CctvSafetyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CctvSafetyResult calculateSafety(List<RoutePoint> points) {
        if (points == null || points.size() < 2) {
            return new CctvSafetyResult(0.0, 0, List.of());
        }

        int checked = 0;
        int covered = 0;
        Map<Long, CctvPointDto> uniqueCctvsById = new LinkedHashMap<>();

        int step = Math.max(1, points.size() / MAX_SAMPLE_COUNT);
        for (int i = 0; i < points.size(); i += step) {
            RoutePoint point = points.get(i);
            List<CctvPointDto> nearby = findNearbyCctvs(point.getLat(), point.getLon(), DEFAULT_RADIUS_METERS);

            checked++;
            if (!nearby.isEmpty()) {
                covered++;
                for (CctvPointDto cctv : nearby) {
                    uniqueCctvsById.putIfAbsent(cctv.getId(), cctv);
                }
            }
        }

        double safetyScore = checked == 0 ? 0.0 : covered / (double) checked;
        List<CctvPointDto> nearbyCctvs = List.copyOf(uniqueCctvsById.values());
        int cctvCount = nearbyCctvs.stream()
                .mapToInt(cctv -> Math.max(1, cctv.getQty() == null ? 1 : cctv.getQty()))
                .sum();

        return new CctvSafetyResult(round(safetyScore), cctvCount, nearbyCctvs);
    }

    private List<CctvPointDto> findNearbyCctvs(double lat, double lon, double radiusMeters) {
        double latDelta = radiusMeters / 111_320.0;
        double lonDelta = radiusMeters / (111_320.0 * Math.max(0.1, Math.cos(Math.toRadians(lat))));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id,
                       district,
                       address,
                       purpose,
                       COALESCE(qty, 1) AS qty,
                       lat,
                       lon
                FROM busan_general_cctv
                WHERE lat IS NOT NULL
                  AND lon IS NOT NULL
                  AND lat BETWEEN ? AND ?
                  AND lon BETWEEN ? AND ?
                """,
                lat - latDelta,
                lat + latDelta,
                lon - lonDelta,
                lon + lonDelta
        );

        return rows.stream()
                .map(row -> new CctvPointDto(
                        ((Number) row.get("id")).longValue(),
                        stringValue(row.get("district")),
                        stringValue(row.get("address")),
                        stringValue(row.get("purpose")),
                        ((Number) row.get("qty")).intValue(),
                        ((Number) row.get("lat")).doubleValue(),
                        ((Number) row.get("lon")).doubleValue()
                ))
                .filter(cctv -> distanceMeters(lat, lon, cctv.getLat(), cctv.getLon()) <= radiusMeters)
                .toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000.0;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);

        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return earthRadius * c;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record CctvSafetyResult(double safetyScore,
                                   int cctvCount,
                                   List<CctvPointDto> nearbyCctvs) {
    }
}
