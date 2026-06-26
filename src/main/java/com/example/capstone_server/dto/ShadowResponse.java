package com.example.capstone_server.dto;

import java.util.Map;

public class ShadowResponse {
    private final Integer buildingCount;
    private final Integer buildingShadowCount;
    private final Integer treeShadowCount;
    private final Map<String, Object> buildings;
    private final Map<String, Object> buildingShadows;
    private final Map<String, Object> treeShadows;

    public ShadowResponse(Integer buildingCount,
                          Integer buildingShadowCount,
                          Integer treeShadowCount,
                          Map<String, Object> buildings,
                          Map<String, Object> buildingShadows,
                          Map<String, Object> treeShadows) {
        this.buildingCount = buildingCount;
        this.buildingShadowCount = buildingShadowCount;
        this.treeShadowCount = treeShadowCount;
        this.buildings = buildings;
        this.buildingShadows = buildingShadows;
        this.treeShadows = treeShadows;
    }

    public Integer getBuildingCount() {
        return buildingCount;
    }

    public Integer getBuildingShadowCount() {
        return buildingShadowCount;
    }

    public Integer getTreeShadowCount() {
        return treeShadowCount;
    }

    public Map<String, Object> getBuildings() {
        return buildings;
    }

    public Map<String, Object> getBuildingShadows() {
        return buildingShadows;
    }

    public Map<String, Object> getTreeShadows() {
        return treeShadows;
    }
}
