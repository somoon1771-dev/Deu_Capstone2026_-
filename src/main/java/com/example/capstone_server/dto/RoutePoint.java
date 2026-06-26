package com.example.capstone_server.dto;

public class RoutePoint {

    private Double lat;
    private Double lon;

    public RoutePoint(Double lat, Double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLon() {
        return lon;
    }
}