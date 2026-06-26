package com.example.capstone_server.dto;

import java.util.List;

public class RouteResponse {

    private String message;
    private Double startLat;
    private Double startLon;
    private Double endLat;
    private Double endLon;
    private Integer distance;
    private Double shadeScore;
    private Double totalScore;
    private Double temperatureCelsius;
    private Double humidity;
    private Double windSpeedMps;
    private Double cloudCover;
    private Boolean raining;
    private Double weatherComfortScore;
    private Double heatStressScore;

    private List<RoutePoint> points;
    private List<RouteOption> routes;

    public RouteResponse(String message,
                         Double startLat,
                         Double startLon,
                         Double endLat,
                         Double endLon,
                         Integer distance,
                         Double shadeScore,
                         Double totalScore,
                         List<RoutePoint> points) {
        this(
                message,
                startLat,
                startLon,
                endLat,
                endLon,
                distance,
                shadeScore,
                totalScore,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                points,
                List.of()
        );
    }

    public RouteResponse(String message,
                         Double startLat,
                         Double startLon,
                         Double endLat,
                         Double endLon,
                         Integer distance,
                         Double shadeScore,
                         Double totalScore,
                         List<RoutePoint> points,
                         List<RouteOption> routes) {
        this(
                message,
                startLat,
                startLon,
                endLat,
                endLon,
                distance,
                shadeScore,
                totalScore,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                points,
                routes
        );
    }

    public RouteResponse(String message,
                         Double startLat,
                         Double startLon,
                         Double endLat,
                         Double endLon,
                         Integer distance,
                         Double shadeScore,
                         Double totalScore,
                         Double temperatureCelsius,
                         Double humidity,
                         Double windSpeedMps,
                         Double cloudCover,
                         Boolean raining,
                         List<RoutePoint> points,
                         List<RouteOption> routes) {
        this(
                message,
                startLat,
                startLon,
                endLat,
                endLon,
                distance,
                shadeScore,
                totalScore,
                temperatureCelsius,
                humidity,
                windSpeedMps,
                cloudCover,
                raining,
                null,
                null,
                points,
                routes
        );
    }

    public RouteResponse(String message,
                         Double startLat,
                         Double startLon,
                         Double endLat,
                         Double endLon,
                         Integer distance,
                         Double shadeScore,
                         Double totalScore,
                         Double temperatureCelsius,
                         Double humidity,
                         Double windSpeedMps,
                         Double cloudCover,
                         Boolean raining,
                         Double weatherComfortScore,
                         Double heatStressScore,
                         List<RoutePoint> points,
                         List<RouteOption> routes) {

        this.message = message;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endLat = endLat;
        this.endLon = endLon;
        this.distance = distance;
        this.shadeScore = shadeScore;
        this.totalScore = totalScore;
        this.temperatureCelsius = temperatureCelsius;
        this.humidity = humidity;
        this.windSpeedMps = windSpeedMps;
        this.cloudCover = cloudCover;
        this.raining = raining;
        this.weatherComfortScore = weatherComfortScore;
        this.heatStressScore = heatStressScore;
        this.points = points;
        this.routes = routes;
    }

    public String getMessage() {
        return message;
    }

    public Double getStartLat() {
        return startLat;
    }

    public Double getStartLon() {
        return startLon;
    }

    public Double getEndLat() {
        return endLat;
    }

    public Double getEndLon() {
        return endLon;
    }

    public Integer getDistance() {
        return distance;
    }

    public Double getShadeScore() {
        return shadeScore;
    }

    public Double getTotalScore() {
        return totalScore;
    }

    public Double getTemperatureCelsius() {
        return temperatureCelsius;
    }

    public Double getHumidity() {
        return humidity;
    }

    public Double getWindSpeedMps() {
        return windSpeedMps;
    }

    public Double getCloudCover() {
        return cloudCover;
    }

    public Boolean getRaining() {
        return raining;
    }

    public Double getWeatherComfortScore() {
        return weatherComfortScore;
    }

    public Double getHeatStressScore() {
        return heatStressScore;
    }

    public List<RoutePoint> getPoints() {
        return points;
    }

    public List<RouteOption> getRoutes() {
        return routes;
    }
}
