package com.example.capstone_server.dto;

public class PlaceSearchResult {
    private final String name;
    private final String address;
    private final String category;
    private final Double lat;
    private final Double lon;

    public PlaceSearchResult(String name, String address, String category, Double lat, Double lon) {
        this.name = name;
        this.address = address;
        this.category = category;
        this.lat = lat;
        this.lon = lon;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getCategory() {
        return category;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLon() {
        return lon;
    }
}
