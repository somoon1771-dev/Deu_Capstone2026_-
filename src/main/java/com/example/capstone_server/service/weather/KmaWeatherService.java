package com.example.capstone_server.service.weather;

import com.example.capstone_server.dto.RouteRequest;
import com.example.capstone_server.service.routing.ApiKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

@Service
public class KmaWeatherService {
    private static final String BASE_URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0";
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HHmm");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int requestTimeoutSeconds;

    public KmaWeatherService(ObjectMapper objectMapper,
                             @Value("${capstone.weather.kma.enabled:true}") boolean enabled,
                             @Value("${capstone.weather.kma.request-timeout-seconds:4}") int requestTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
    }

    public void applyWeatherIfMissing(RouteRequest request) {
        if (!enabled || request.getStartLat() == null || request.getStartLon() == null || hasAllWeatherFields(request)) {
            return;
        }

        try {
            WeatherSnapshot snapshot = fetchWeather(request.getStartLat(), request.getStartLon(), request.getDepartureTime());
            if (request.getTemperatureCelsius() == null && snapshot.temperatureCelsius() != null) {
                request.setTemperatureCelsius(snapshot.temperatureCelsius());
            }
            if (request.getHumidity() == null && snapshot.humidity() != null) {
                request.setHumidity(snapshot.humidity());
            }
            if (request.getWindSpeedMps() == null && snapshot.windSpeedMps() != null) {
                request.setWindSpeedMps(snapshot.windSpeedMps());
            }
            if (request.getCloudCover() == null && snapshot.cloudCover() != null) {
                request.setCloudCover(snapshot.cloudCover());
            }
            if (request.getRaining() == null && snapshot.raining() != null) {
                request.setRaining(snapshot.raining());
            }
        } catch (Exception ignored) {
            // Route evaluation keeps sensible weather defaults when the public weather API is unavailable.
        }
    }

    private WeatherSnapshot fetchWeather(double lat, double lon, String departureTime)
            throws IOException, InterruptedException {
        Grid grid = toKmaGrid(lat, lon);
        ZonedDateTime targetTime = parseDepartureTime(departureTime);
        if (Duration.between(ZonedDateTime.now(KOREA_ZONE), targetTime).toHours() <= 6) {
            try {
                WeatherSnapshot forecast = fetchUltraShortForecast(grid, targetTime);
                if (forecast.hasAnyValue()) {
                    return forecast;
                }
            } catch (IOException ignored) {
                // Fall back to the latest observed values below.
            }
        }
        return fetchUltraShortNowcast(grid);
    }

    private WeatherSnapshot fetchUltraShortForecast(Grid grid, ZonedDateTime targetTime)
            throws IOException, InterruptedException {
        BaseDateTime base = latestUltraShortForecastBase(ZonedDateTime.now(KOREA_ZONE));
        JsonNode root = requestKma("/getUltraSrtFcst", base, grid, 100);
        Map<String, JsonNode> itemsByCategory = closestForecastItems(root, targetTime);
        return snapshotFromItems(itemsByCategory);
    }

    private WeatherSnapshot fetchUltraShortNowcast(Grid grid)
            throws IOException, InterruptedException {
        BaseDateTime base = latestUltraShortNowcastBase(ZonedDateTime.now(KOREA_ZONE));
        JsonNode root = requestKma("/getUltraSrtNcst", base, grid, 20);
        Map<String, JsonNode> itemsByCategory = latestItems(root);
        return snapshotFromItems(itemsByCategory);
    }

    private JsonNode requestKma(String path, BaseDateTime base, Grid grid, int rows)
            throws IOException, InterruptedException {
        String url = BASE_URL + path
                + "?serviceKey=" + encode(ApiKeys.DATA_GO_KR_SERVICE_KEY)
                + "&pageNo=1"
                + "&numOfRows=" + rows
                + "&dataType=JSON"
                + "&base_date=" + encode(base.date())
                + "&base_time=" + encode(base.time())
                + "&nx=" + grid.x()
                + "&ny=" + grid.y();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
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
            throw new IOException("KMA HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String resultCode = root.path("response").path("header").path("resultCode").asText("");
        if (!"00".equals(resultCode)) {
            String message = root.path("response").path("header").path("resultMsg").asText("");
            throw new IOException("KMA result " + resultCode + " " + message);
        }
        return root;
    }

    private Map<String, JsonNode> latestItems(JsonNode root) {
        Map<String, JsonNode> byCategory = new HashMap<>();
        for (JsonNode item : itemArray(root)) {
            byCategory.put(item.path("category").asText(), item);
        }
        return byCategory;
    }

    private Map<String, JsonNode> closestForecastItems(JsonNode root, ZonedDateTime targetTime) {
        Map<String, JsonNode> byCategory = new HashMap<>();
        long bestDiffSeconds = Long.MAX_VALUE;
        for (JsonNode item : itemArray(root)) {
            String date = item.path("fcstDate").asText("");
            String time = item.path("fcstTime").asText("");
            if (date.isBlank() || time.isBlank()) {
                continue;
            }
            ZonedDateTime forecastTime = LocalDateTime.parse(date + time, DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
                    .atZone(KOREA_ZONE);
            long diff = Math.abs(Duration.between(targetTime, forecastTime).toSeconds());
            if (diff < bestDiffSeconds) {
                byCategory.clear();
                bestDiffSeconds = diff;
            }
            if (diff == bestDiffSeconds) {
                byCategory.put(item.path("category").asText(), item);
            }
        }
        return byCategory;
    }

    private Iterable<JsonNode> itemArray(JsonNode root) {
        JsonNode item = root.path("response").path("body").path("items").path("item");
        if (item.isArray()) {
            return item::elements;
        }
        return java.util.List.of();
    }

    private WeatherSnapshot snapshotFromItems(Map<String, JsonNode> itemsByCategory) {
        Double temperature = value(itemsByCategory, "T1H", "TMP");
        Double humidity = value(itemsByCategory, "REH");
        Double windSpeed = value(itemsByCategory, "WSD");
        Double cloudCover = cloudCover(itemsByCategory);
        Boolean raining = raining(itemsByCategory);
        return new WeatherSnapshot(temperature, humidity, windSpeed, cloudCover, raining);
    }

    private Double value(Map<String, JsonNode> itemsByCategory, String... categories) {
        for (String category : categories) {
            JsonNode item = itemsByCategory.get(category);
            if (item == null) {
                continue;
            }
            String raw = item.path("obsrValue").asText(item.path("fcstValue").asText(""));
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double cloudCover(Map<String, JsonNode> itemsByCategory) {
        Double sky = value(itemsByCategory, "SKY");
        if (sky == null) {
            return null;
        }
        if (sky <= 1.0) {
            return 0.0;
        }
        if (sky <= 3.0) {
            return 60.0;
        }
        return 95.0;
    }

    private Boolean raining(Map<String, JsonNode> itemsByCategory) {
        Double precipitationType = value(itemsByCategory, "PTY");
        if (precipitationType != null && precipitationType > 0.0) {
            return true;
        }
        JsonNode rainItem = itemsByCategory.get("RN1");
        if (rainItem == null) {
            rainItem = itemsByCategory.get("PCP");
        }
        if (rainItem == null) {
            return precipitationType == null ? null : false;
        }
        String raw = rainItem.path("obsrValue").asText(rainItem.path("fcstValue").asText(""));
        if (raw.contains("없음") || "0".equals(raw) || "0.0".equals(raw)) {
            return false;
        }
        return !raw.isBlank();
    }

    private BaseDateTime latestUltraShortNowcastBase(ZonedDateTime now) {
        ZonedDateTime base = now.getMinute() >= 45 ? now : now.minusHours(1);
        return baseDateTime(base, base.getHour() + "00");
    }

    private BaseDateTime latestUltraShortForecastBase(ZonedDateTime now) {
        ZonedDateTime base = now.getMinute() >= 45 ? now : now.minusHours(1);
        return baseDateTime(base, base.getHour() + "30");
    }

    private BaseDateTime baseDateTime(ZonedDateTime time, String rawTime) {
        LocalTime localTime = LocalTime.parse(String.format("%04d", Integer.parseInt(rawTime)), TIME_FORMAT);
        return new BaseDateTime(time.toLocalDate().format(DATE_FORMAT), localTime.format(TIME_FORMAT));
    }

    private ZonedDateTime parseDepartureTime(String departureTime) {
        if (departureTime == null || departureTime.isBlank()) {
            return ZonedDateTime.now(KOREA_ZONE);
        }
        try {
            return OffsetDateTime.parse(departureTime).atZoneSameInstant(KOREA_ZONE);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(departureTime).atZone(KOREA_ZONE);
            } catch (DateTimeParseException ignoredAgain) {
                return ZonedDateTime.now(KOREA_ZONE);
            }
        }
    }

    private boolean hasAllWeatherFields(RouteRequest request) {
        return request.getTemperatureCelsius() != null
                && request.getHumidity() != null
                && request.getWindSpeedMps() != null
                && request.getCloudCover() != null
                && request.getRaining() != null;
    }

    private Grid toKmaGrid(double lat, double lon) {
        double re = 6371.00877;
        double grid = 5.0;
        double slat1 = Math.toRadians(30.0);
        double slat2 = Math.toRadians(60.0);
        double olon = Math.toRadians(126.0);
        double olat = Math.toRadians(38.0);
        double xo = 43.0;
        double yo = 136.0;

        double sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
        double sf = Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sf = Math.pow(sf, sn) * Math.cos(slat1) / sn;
        double ro = Math.tan(Math.PI * 0.25 + olat * 0.5);
        ro = re / grid * sf / Math.pow(ro, sn);
        double ra = Math.tan(Math.PI * 0.25 + Math.toRadians(lat) * 0.5);
        ra = re / grid * sf / Math.pow(ra, sn);
        double theta = Math.toRadians(lon) - olon;
        if (theta > Math.PI) {
            theta -= 2.0 * Math.PI;
        }
        if (theta < -Math.PI) {
            theta += 2.0 * Math.PI;
        }
        theta *= sn;

        int x = (int) Math.floor(ra * Math.sin(theta) + xo + 0.5);
        int y = (int) Math.floor(ro - ra * Math.cos(theta) + yo + 0.5);
        return new Grid(x, y);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record WeatherSnapshot(Double temperatureCelsius,
                                   Double humidity,
                                   Double windSpeedMps,
                                   Double cloudCover,
                                   Boolean raining) {
        private boolean hasAnyValue() {
            return temperatureCelsius != null
                    || humidity != null
                    || windSpeedMps != null
                    || cloudCover != null
                    || raining != null;
        }
    }

    private record Grid(int x, int y) {
    }

    private record BaseDateTime(String date, String time) {
    }
}
