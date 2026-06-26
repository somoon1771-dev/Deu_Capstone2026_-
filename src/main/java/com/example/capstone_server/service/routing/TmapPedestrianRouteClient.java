package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RoutePoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TmapPedestrianRouteClient {
    private static final String PEDESTRIAN_URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1";
    private static final Pattern LINESTRING_MARKER_PATTERN =
            Pattern.compile("\"type\"\\s*:\\s*\"LineString\"\\s*,\\s*\"coordinates\"\\s*:", Pattern.DOTALL);
    private static final Pattern PAIR_PATTERN =
            Pattern.compile("\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*]");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final boolean enabled;
    private final int requestTimeoutSeconds;

    public TmapPedestrianRouteClient(@Value("${capstone.routing.tmap.enabled:true}") boolean enabled,
                                     @Value("${capstone.routing.tmap.request-timeout-seconds:5}") int requestTimeoutSeconds) {
        this.enabled = enabled;
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
    }

    public CandidateRoute buildRoute(String id,
                                     String sourceLabel,
                                     RoutePoint start,
                                     RoutePoint end,
                                     List<RoutePoint> waypoints,
                                     double shadeBias) {
        if (!enabled) {
            return fallback(id, sourceLabel + "-disabled", start, end, waypoints, shadeBias);
        }

        try {
            String body = requestRoute(start, end, waypoints);
            List<RoutePoint> points = parseRoutePoints(body);
            List<RoutePoint> cleanedPoints = removeBacktrackingPoints(points);

            if (cleanedPoints.size() < 2) {
                return fallback(id, sourceLabel + "-empty", start, end, waypoints, shadeBias);
            }

            return new CandidateRoute(id, "tmap-pedestrian-" + sourceLabel, cleanedPoints, shadeBias);
        } catch (Exception e) {
            return fallback(id, sourceLabel + "-fallback", start, end, waypoints, shadeBias);
        }
    }

    private String requestRoute(RoutePoint start, RoutePoint end, List<RoutePoint> waypoints)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        append(body, "startX", Double.toString(start.getLon()));
        append(body, "startY", Double.toString(start.getLat()));
        append(body, "endX", Double.toString(end.getLon()));
        append(body, "endY", Double.toString(end.getLat()));
        append(body, "reqCoordType", "WGS84GEO");
        append(body, "resCoordType", "WGS84GEO");
        append(body, "startName", "start");
        append(body, "endName", "end");
        append(body, "searchOption", "0");
        append(body, "sort", "index");

        if (!waypoints.isEmpty()) {
            append(body, "passList", toPassList(waypoints));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PEDESTRIAN_URL))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("appKey", ApiKeys.TMAP_API_KEY)
                .header("User-Agent", "capstone-server/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("TMAP HTTP " + response.statusCode() + " " + summarize(response.body()));
        }

        return response.body();
    }

    private List<RoutePoint> parseRoutePoints(String body) {
        List<RoutePoint> points = new ArrayList<>();
        Matcher lineMatcher = LINESTRING_MARKER_PATTERN.matcher(body);

        while (lineMatcher.find()) {
            String coordinatesBlock = extractBalancedArray(body, lineMatcher.end());
            Matcher pairMatcher = PAIR_PATTERN.matcher(coordinatesBlock);

            while (pairMatcher.find()) {
                double lon = Double.parseDouble(pairMatcher.group(1));
                double lat = Double.parseDouble(pairMatcher.group(2));
                RoutePoint point = new RoutePoint(lat, lon);

                if (points.isEmpty() || !samePoint(points.get(points.size() - 1), point)) {
                    points.add(point);
                }
            }
        }

        return points;
    }

    private List<RoutePoint> removeBacktrackingPoints(List<RoutePoint> points) {
        if (points == null || points.size() < 3) {
            return points == null ? List.of() : points;
        }

        List<RoutePoint> cleaned = new ArrayList<>();

        for (RoutePoint point : points) {
            int size = cleaned.size();

            // A -> B -> A 형태의 왕복 구간 제거
            if (size >= 2 && samePoint(cleaned.get(size - 2), point)) {
                cleaned.remove(size - 1);
                continue;
            }

            // 연속 중복 좌표 제거
            if (size >= 1 && samePoint(cleaned.get(size - 1), point)) {
                continue;
            }

            cleaned.add(point);
        }

        return cleaned;
    }

    private CandidateRoute fallback(String id,
                                    String sourceLabel,
                                    RoutePoint start,
                                    RoutePoint end,
                                    List<RoutePoint> waypoints,
                                    double shadeBias) {
        List<RoutePoint> points = new ArrayList<>();
        points.add(start);
        points.addAll(waypoints);
        points.add(end);

        List<RoutePoint> cleanedPoints = removeBacktrackingPoints(points);
        return new CandidateRoute(id, sourceLabel, cleanedPoints, shadeBias);
    }

    private String toPassList(List<RoutePoint> waypoints) {
        List<String> encoded = new ArrayList<>();

        for (RoutePoint waypoint : waypoints) {
            encoded.add(waypoint.getLon() + "," + waypoint.getLat());
        }

        return String.join("_", encoded);
    }

    private static void append(StringBuilder builder, String name, String value) {
        if (!builder.isEmpty()) {
            builder.append('&');
        }

        builder.append(encode(name)).append('=').append(encode(value));
    }

    private static boolean samePoint(RoutePoint left, RoutePoint right) {
        if (left == null || right == null) {
            return false;
        }

        return Math.abs(left.getLat() - right.getLat()) < 0.0000001
                && Math.abs(left.getLon() - right.getLon()) < 0.0000001;
    }

    private static String extractBalancedArray(String text, int searchFrom) {
        int start = text.indexOf('[', searchFrom);

        if (start < 0) {
            return "";
        }

        int depth = 0;

        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;

                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }

        return text.substring(start);
    }

    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }

        String compact = body.replaceAll("\\s+", " ").trim();

        return compact.length() > 240 ? compact.substring(0, 240) + "..." : compact;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}