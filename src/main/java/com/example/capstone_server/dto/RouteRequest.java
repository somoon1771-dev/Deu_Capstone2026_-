package com.example.capstone_server.dto;

import jakarta.validation.constraints.NotNull;

public class RouteRequest {

    @NotNull
    private Double startLat;

    @NotNull
    private Double startLon;

    @NotNull
    private Double endLat;

    @NotNull
    private Double endLon;

    private String departureTime;

    private String preference;

    private Double cloudCover;

    private Boolean raining;

    private Double temperatureCelsius;

    private Double humidity;

    private Double windSpeedMps;

    public Double getStartLat() {
        return startLat;
    }

    public void setStartLat(Double startLat) {
        this.startLat = startLat;
    }

    public Double getStartLon() {
        return startLon;
    }

    public void setStartLon(Double startLon) {
        this.startLon = startLon;
    }

    public Double getEndLat() {
        return endLat;
    }

    public void setEndLat(Double endLat) {
        this.endLat = endLat;
    }

    public Double getEndLon() {
        return endLon;
    }

    public void setEndLon(Double endLon) {
        this.endLon = endLon;
    }

    public String getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(String departureTime) {
        this.departureTime = departureTime;
    }

    public String getPreference() {
        return preference;
    }

    public void setPreference(String preference) {
        this.preference = preference;
    }

    public Double getCloudCover() {
        return cloudCover;
    }

    public void setCloudCover(Double cloudCover) {
        this.cloudCover = cloudCover;
    }

    public Boolean getRaining() {
        return raining;
    }

    public void setRaining(Boolean raining) {
        this.raining = raining;
    }

    public Double getTemperatureCelsius() {
        return temperatureCelsius;
    }

    public void setTemperatureCelsius(Double temperatureCelsius) {
        this.temperatureCelsius = temperatureCelsius;
    }

    public Double getHumidity() {
        return humidity;
    }

    public void setHumidity(Double humidity) {
        this.humidity = humidity;
    }

    public Double getWindSpeedMps() {
        return windSpeedMps;
    }

    public void setWindSpeedMps(Double windSpeedMps) {
        this.windSpeedMps = windSpeedMps;
    }
}
