package com.example.capstone_server.service.routing;

public final class ApiKeys {
    public static final String DATA_GO_KR_SERVICE_KEY =
            System.getenv().getOrDefault("DATA_GO_KR_SERVICE_KEY", "");
    public static final String VWORLD_API_KEY =
            System.getenv().getOrDefault("VWORLD_API_KEY", "");
    public static final String TMAP_API_KEY =
            System.getenv().getOrDefault("TMAP_API_KEY", "");

    private ApiKeys() {
    }
}
