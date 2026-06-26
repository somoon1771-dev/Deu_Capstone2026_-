package com.example.capstone_server.dto;

import java.util.List;

public class RouteOption {

    private final String routeType;
    private final Integer distance;
    private final Double shadeScore;
    private final Double distanceScore;
    private final Double totalScore;
    private final Double weatherComfortScore;
    private final Double heatStressScore;
    private final Double longestSunExposureMeters;
    private final Double sunExposurePenalty;
    private final Double detourRatio;
    private final Double progressEfficiency;
    private final Double safetyScore;
    private final Integer cctvCount;
    private final List<CctvPointDto> nearbyCctvs;
    private final String source;
    private final List<RoutePoint> points;
    private final List<RouteSegmentScore> segmentScores;

    public RouteOption(String routeType,
                       Integer distance,
                       Double shadeScore,
                       Double distanceScore,
                       Double totalScore,
                       Double detourRatio,
                       Double progressEfficiency,
                       String source,
                       List<RoutePoint> points) {
        this(routeType, distance, shadeScore, distanceScore, totalScore, null, null, null, null,
                detourRatio, progressEfficiency, null, null, List.of(), source, points, List.of());
    }

    public RouteOption(String routeType,
                       Integer distance,
                       Double shadeScore,
                       Double distanceScore,
                       Double totalScore,
                       Double detourRatio,
                       Double progressEfficiency,
                       String source,
                       List<RoutePoint> points,
                       List<RouteSegmentScore> segmentScores) {
        this(routeType, distance, shadeScore, distanceScore, totalScore, null, null, null, null,
                detourRatio, progressEfficiency, null, null, List.of(), source, points, segmentScores);
    }

    public RouteOption(String routeType,
                       Integer distance,
                       Double shadeScore,
                       Double distanceScore,
                       Double totalScore,
                       Double weatherComfortScore,
                       Double heatStressScore,
                       Double longestSunExposureMeters,
                       Double sunExposurePenalty,
                       Double detourRatio,
                       Double progressEfficiency,
                       String source,
                       List<RoutePoint> points,
                       List<RouteSegmentScore> segmentScores) {
        this(routeType, distance, shadeScore, distanceScore, totalScore, weatherComfortScore, heatStressScore,
                longestSunExposureMeters, sunExposurePenalty, detourRatio, progressEfficiency,
                null, null, List.of(), source, points, segmentScores);
    }

    public RouteOption(String routeType,
                       Integer distance,
                       Double shadeScore,
                       Double distanceScore,
                       Double totalScore,
                       Double weatherComfortScore,
                       Double heatStressScore,
                       Double longestSunExposureMeters,
                       Double sunExposurePenalty,
                       Double detourRatio,
                       Double progressEfficiency,
                       Double safetyScore,
                       Integer cctvCount,
                       String source,
                       List<RoutePoint> points,
                       List<RouteSegmentScore> segmentScores) {
        this(routeType, distance, shadeScore, distanceScore, totalScore, weatherComfortScore, heatStressScore,
                longestSunExposureMeters, sunExposurePenalty, detourRatio, progressEfficiency,
                safetyScore, cctvCount, List.of(), source, points, segmentScores);
    }

    public RouteOption(String routeType,
                       Integer distance,
                       Double shadeScore,
                       Double distanceScore,
                       Double totalScore,
                       Double weatherComfortScore,
                       Double heatStressScore,
                       Double longestSunExposureMeters,
                       Double sunExposurePenalty,
                       Double detourRatio,
                       Double progressEfficiency,
                       Double safetyScore,
                       Integer cctvCount,
                       List<CctvPointDto> nearbyCctvs,
                       String source,
                       List<RoutePoint> points,
                       List<RouteSegmentScore> segmentScores) {
        this.routeType = routeType;
        this.distance = distance;
        this.shadeScore = shadeScore;
        this.distanceScore = distanceScore;
        this.totalScore = totalScore;
        this.weatherComfortScore = weatherComfortScore;
        this.heatStressScore = heatStressScore;
        this.longestSunExposureMeters = longestSunExposureMeters;
        this.sunExposurePenalty = sunExposurePenalty;
        this.detourRatio = detourRatio;
        this.progressEfficiency = progressEfficiency;
        this.safetyScore = safetyScore;
        this.cctvCount = cctvCount;
        this.nearbyCctvs = nearbyCctvs == null ? List.of() : nearbyCctvs;
        this.source = source;
        this.points = points;
        this.segmentScores = segmentScores;
    }

    public String getRouteType() {
        return routeType;
    }

    public Integer getDistance() {
        return distance;
    }

    public Double getShadeScore() {
        return shadeScore;
    }

    public Double getDistanceScore() {
        return distanceScore;
    }

    public Double getTotalScore() {
        return totalScore;
    }

    public Double getWeatherComfortScore() {
        return weatherComfortScore;
    }

    public Double getHeatStressScore() {
        return heatStressScore;
    }

    public Double getLongestSunExposureMeters() {
        return longestSunExposureMeters;
    }

    public Double getSunExposurePenalty() {
        return sunExposurePenalty;
    }

    public Double getDetourRatio() {
        return detourRatio;
    }

    public Double getProgressEfficiency() {
        return progressEfficiency;
    }

    public Double getSafetyScore() {
        return safetyScore;
    }

    public Integer getCctvCount() {
        return cctvCount;
    }

    public List<CctvPointDto> getNearbyCctvs() {
        return nearbyCctvs;
    }

    public String getSource() {
        return source;
    }

    public List<RoutePoint> getPoints() {
        return points;
    }

    public List<RouteSegmentScore> getSegmentScores() {
        return segmentScores;
    }
}
