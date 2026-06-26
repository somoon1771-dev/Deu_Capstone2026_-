package com.example.capstone_server.service;

import com.example.capstone_server.dto.PlaceSearchResult;
import com.example.capstone_server.service.routing.ApiKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlaceSearchService {
    private static final String VWORLD_SEARCH_URL = "http://map.vworld.kr/search.do";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper;

    public PlaceSearchService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<PlaceSearchResult> search(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }

        Map<String, PlaceSearchResult> results = new LinkedHashMap<>();
        collect(results, trimmed);
        if (!trimmed.contains("부산")) {
            collect(results, "부산 " + trimmed);
        }
        return new ArrayList<>(results.values());
    }

    private void collect(Map<String, PlaceSearchResult> results, String query) {
        for (PlaceSearchResult result : searchVWorldPoi(query)) {
            String key = result.getName() + "|" + result.getLat() + "|" + result.getLon();
            results.putIfAbsent(key, result);
            if (results.size() >= 8) {
                return;
            }
        }
    }

    private List<PlaceSearchResult> searchVWorldPoi(String query) {
        try {
            String url = VWORLD_SEARCH_URL
                    + "?q=" + encode(query)
                    + "&category=Poi"
                    + "&pageUnit=8"
                    + "&pageIndex=1"
                    + "&output=json"
                    + "&apiKey=" + encode(ApiKeys.VWORLD_API_KEY);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "capstone-server/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            return parseResults(response.body());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<PlaceSearchResult> parseResults(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode list = root.path("LIST");
        if (!list.isArray()) {
            return List.of();
        }

        List<PlaceSearchResult> results = new ArrayList<>();
        for (JsonNode item : list) {
            double lat = number(item.path("ypos").asText());
            double lon = number(item.path("xpos").asText());
            if (lat == 0.0 || lon == 0.0) {
                continue;
            }
            String roadAddress = item.path("njuso").asText("");
            String parcelAddress = item.path("juso").asText("");
            results.add(new PlaceSearchResult(
                    item.path("nameFull").asText(item.path("nameAll").asText("")),
                    roadAddress.isBlank() ? parcelAddress : roadAddress,
                    item.path("codeName").asText(""),
                    lat,
                    lon
            ));
        }
        return results;
    }

    private double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
