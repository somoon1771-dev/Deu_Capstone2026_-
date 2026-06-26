package com.example.capstone_server.dto;

public class RouteSegmentScore {
    private final RoutePoint start;
    private final RoutePoint end;
    private final Integer distance;
    private final Double shadeScore;
    private final Double congestionScore;
    private final Double weatherComfortScore;
    private final Double heatStressScore;
    private final Double segmentScore;

    public RouteSegmentScore(RoutePoint start,
                             RoutePoint end,
                             Integer distance,
                             Double shadeScore,
                             Double congestionScore,
                             Double segmentScore) {
        this(start, end, distance, shadeScore, congestionScore, 1.0, 0.0, segmentScore);
    }

    public RouteSegmentScore(RoutePoint start,
                             RoutePoint end,
                             Integer distance,
                             Double shadeScore,
                             Double congestionScore,
                             Double weatherComfortScore,
                             Double heatStressScore,
                             Double segmentScore) {
        this.start = start;
        this.end = end;
        this.distance = distance;
        this.shadeScore = shadeScore;
        this.congestionScore = congestionScore;
        this.weatherComfortScore = weatherComfortScore;
        this.heatStressScore = heatStressScore;
        this.segmentScore = segmentScore;
    }

    public RoutePoint getStart() {
        return start;
    }

    public RoutePoint getEnd() {
        return end;
    }

    public Integer getDistance() {
        return distance;
    }

    public Double getShadeScore() {
        return shadeScore;
    }

    public Double getCongestionScore() {
        return congestionScore;
    }

    public Double getWeatherComfortScore() {
        return weatherComfortScore;
    }

    public Double getHeatStressScore() {
        return heatStressScore;
    }

    public Double getSegmentScore() {
        return segmentScore;
    }
}
