package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RoutePoint;

import java.util.ArrayList;
import java.util.List;

final class GeoMath {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double METERS_PER_DEGREE_LATITUDE = 111_320.0;

    private GeoMath() {
    }

    static double distanceMeters(RoutePoint a, RoutePoint b) {
        double lat1 = Math.toRadians(a.getLat());
        double lat2 = Math.toRadians(b.getLat());
        double deltaLat = Math.toRadians(b.getLat() - a.getLat());
        double deltaLon = Math.toRadians(b.getLon() - a.getLon());

        double haversine = Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2.0) * Math.sin(deltaLon / 2.0);
        return EARTH_RADIUS_METERS * 2.0 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1.0 - haversine));
    }

    static double pathDistanceMeters(List<RoutePoint> points) {
        double distance = 0.0;
        for (int i = 1; i < points.size(); i++) {
            distance += distanceMeters(points.get(i - 1), points.get(i));
        }
        return distance;
    }

    static double bearingDegrees(RoutePoint a, RoutePoint b) {
        double lat1 = Math.toRadians(a.getLat());
        double lat2 = Math.toRadians(b.getLat());
        double deltaLon = Math.toRadians(b.getLon() - a.getLon());
        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        return normalizeDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    static RoutePoint interpolate(RoutePoint a, RoutePoint b, double ratio) {
        return new RoutePoint(
                a.getLat() + (b.getLat() - a.getLat()) * ratio,
                a.getLon() + (b.getLon() - a.getLon()) * ratio
        );
    }

    static RoutePoint offset(RoutePoint point, double bearingDegrees, double meters) {
        double radians = Math.toRadians(bearingDegrees);
        double northMeters = Math.cos(radians) * meters;
        double eastMeters = Math.sin(radians) * meters;
        double lat = point.getLat() + northMeters / METERS_PER_DEGREE_LATITUDE;
        double lon = point.getLon()
                + eastMeters / (METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(point.getLat())));
        return new RoutePoint(lat, lon);
    }

    static List<RoutePoint> samplePath(List<RoutePoint> points, double intervalMeters) {
        List<RoutePoint> samples = new ArrayList<>();
        if (points.isEmpty()) {
            return samples;
        }
        samples.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            RoutePoint start = points.get(i - 1);
            RoutePoint end = points.get(i);
            double segmentDistance = distanceMeters(start, end);
            int steps = Math.max(1, (int) Math.ceil(segmentDistance / intervalMeters));
            for (int step = 1; step <= steps; step++) {
                samples.add(interpolate(start, end, step / (double) steps));
            }
        }
        return samples;
    }

    static double angleDifferenceDegrees(double left, double right) {
        double diff = Math.abs(normalizeDegrees(left) - normalizeDegrees(right));
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    static double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0 ? result + 360.0 : result;
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
