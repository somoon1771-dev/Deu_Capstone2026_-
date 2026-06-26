package com.example.capstone_server.dto;

public class CctvPointDto {
    private final Long id;
    private final String district;
    private final String address;
    private final String purpose;
    private final Integer qty;
    private final Double lat;
    private final Double lon;

    public CctvPointDto(Long id,
                        String district,
                        String address,
                        String purpose,
                        Integer qty,
                        Double lat,
                        Double lon) {
        this.id = id;
        this.district = district;
        this.address = address;
        this.purpose = purpose;
        this.qty = qty;
        this.lat = lat;
        this.lon = lon;
    }

    public Long getId() {
        return id;
    }

    public String getDistrict() {
        return district;
    }

    public String getAddress() {
        return address;
    }

    public String getPurpose() {
        return purpose;
    }

    public Integer getQty() {
        return qty;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLon() {
        return lon;
    }
}
