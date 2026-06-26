package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RouteRequest;
import com.example.capstone_server.dto.RouteResponse;
import com.example.capstone_server.service.RouteService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class RoutePrimingRunner {
    private final RouteService routeService;
    private final boolean enabled;
    private final long initialDelayMillis;
    private final String routesValue;
    private final String departureTimesValue;

    public RoutePrimingRunner(RouteService routeService,
                              @Value("${capstone.routing.route-priming.enabled:false}") boolean enabled,
                              @Value("${capstone.routing.route-priming.initial-delay-ms:130000}") long initialDelayMillis,
                              @Value("${capstone.routing.route-priming.routes:}") String routesValue,
                              @Value("${capstone.routing.route-priming.departure-times:}") String departureTimesValue) {
        this.routeService = routeService;
        this.enabled = enabled;
        this.initialDelayMillis = Math.max(0, initialDelayMillis);
        this.routesValue = routesValue == null ? "" : routesValue;
        this.departureTimesValue = departureTimesValue == null ? "" : departureTimesValue;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void primeAfterStartup() {
        if (!enabled || routesValue.isBlank() || departureTimesValue.isBlank()) {
            return;
        }

        List<PrimeRoute> routes = parseRoutes(routesValue);
        List<String> departureTimes = parseDepartureTimes(departureTimesValue);
        if (routes.isEmpty() || departureTimes.isEmpty()) {
            return;
        }

        CompletableFuture.runAsync(() -> prime(routes, departureTimes));
    }

    private void prime(List<PrimeRoute> routes, List<String> departureTimes) {
        try {
            if (initialDelayMillis > 0) {
                Thread.sleep(initialDelayMillis);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }

        long startedAt = System.currentTimeMillis();
        int successCount = 0;
        int failureCount = 0;
        System.out.printf(
                "Route priming started after %d ms: %,d routes x %,d departure times%n",
                initialDelayMillis,
                routes.size(),
                departureTimes.size()
        );

        for (PrimeRoute route : routes) {
            for (String departureTime : departureTimes) {
                long requestStartedAt = System.currentTimeMillis();
                try {
                    RouteResponse response = routeService.findRoutes(request(route, departureTime));
                    successCount++;
                    System.out.printf(
                            "Route primed %s at %s in %d ms: %,d options%n",
                            route.name(),
                            departureTime,
                            System.currentTimeMillis() - requestStartedAt,
                            response.getRoutes() == null ? 0 : response.getRoutes().size()
                    );
                } catch (Exception exception) {
                    failureCount++;
                    System.out.printf(
                            "Route priming failed for %s at %s after %d ms: %s%n",
                            route.name(),
                            departureTime,
                            System.currentTimeMillis() - requestStartedAt,
                            exception.getMessage()
                    );
                }
            }
        }

        System.out.printf(
                "Route priming finished in %d ms: %,d success, %,d failed%n",
                System.currentTimeMillis() - startedAt,
                successCount,
                failureCount
        );
    }

    private RouteRequest request(PrimeRoute route, String departureTime) {
        RouteRequest request = new RouteRequest();
        request.setStartLat(route.startLat());
        request.setStartLon(route.startLon());
        request.setEndLat(route.endLat());
        request.setEndLon(route.endLon());
        request.setDepartureTime(departureTime);
        request.setPreference("BALANCED");
        request.setCloudCover(10.0);
        request.setRaining(false);
        return request;
    }

    private List<PrimeRoute> parseRoutes(String value) {
        List<PrimeRoute> routes = new ArrayList<>();
        for (String rawRoute : value.split(";")) {
            String[] parts = rawRoute.split(",");
            if (parts.length != 5) {
                continue;
            }
            try {
                routes.add(new PrimeRoute(
                        parts[0].trim(),
                        Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()),
                        Double.parseDouble(parts[3].trim()),
                        Double.parseDouble(parts[4].trim())
                ));
            } catch (NumberFormatException ignored) {
            }
        }
        return routes;
    }

    private List<String> parseDepartureTimes(String value) {
        List<String> departureTimes = new ArrayList<>();
        for (String token : value.split(",")) {
            String departureTime = token.trim();
            if (!departureTime.isBlank()) {
                departureTimes.add(departureTime);
            }
        }
        return departureTimes;
    }

    private record PrimeRoute(String name,
                              double startLat,
                              double startLon,
                              double endLat,
                              double endLon) {
    }
}
