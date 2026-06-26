package com.example.capstone_server.service;

import com.example.capstone_server.dto.RouteOption;
import com.example.capstone_server.dto.RoutePoint;
import com.example.capstone_server.dto.RouteRequest;
import com.example.capstone_server.dto.RouteResponse;
import com.example.capstone_server.service.routing.CandidateRoute;
import com.example.capstone_server.service.routing.CandidateRouteGenerator;
import com.example.capstone_server.service.routing.EvaluatedRoute;
import com.example.capstone_server.service.routing.RouteEvaluator;
import com.example.capstone_server.service.routing.RouteSelector;
import com.example.capstone_server.service.routing.SegmentGraphRouteOptimizer;
import com.example.capstone_server.service.weather.KmaWeatherService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class RouteService {
    private final CandidateRouteGenerator candidateRouteGenerator;
    private final RouteEvaluator routeEvaluator;
    private final RouteSelector routeSelector;
    private final SegmentGraphRouteOptimizer segmentGraphRouteOptimizer;
    private final KmaWeatherService kmaWeatherService;
    private final boolean segmentOptimizerEnabled;
    private final boolean segmentOptimizerOnlyWhenMissingRoute;

    public RouteService(CandidateRouteGenerator candidateRouteGenerator,
                        RouteEvaluator routeEvaluator,
                        RouteSelector routeSelector,
                        SegmentGraphRouteOptimizer segmentGraphRouteOptimizer,
                        KmaWeatherService kmaWeatherService,
                        @Value("${capstone.routing.segment-optimizer.enabled:true}") boolean segmentOptimizerEnabled,
                        @Value("${capstone.routing.segment-optimizer.only-when-missing-route:true}") boolean segmentOptimizerOnlyWhenMissingRoute) {
        this.candidateRouteGenerator = candidateRouteGenerator;
        this.routeEvaluator = routeEvaluator;
        this.routeSelector = routeSelector;
        this.segmentGraphRouteOptimizer = segmentGraphRouteOptimizer;
        this.kmaWeatherService = kmaWeatherService;
        this.segmentOptimizerEnabled = segmentOptimizerEnabled;
        this.segmentOptimizerOnlyWhenMissingRoute = segmentOptimizerOnlyWhenMissingRoute;
    }

    public RouteResponse findRoutes(RouteRequest request) {
        kmaWeatherService.applyWeatherIfMissing(request);

        RoutePoint start = new RoutePoint(request.getStartLat(), request.getStartLon());
        RoutePoint end = new RoutePoint(request.getEndLat(), request.getEndLon());

        List<CandidateRoute> candidates = candidateRouteGenerator.generate(start, end, request);
        List<EvaluatedRoute> evaluatedRoutes = routeEvaluator.evaluate(candidates, request);

        boolean shadeRoutesNotUseful = shouldHideShadeRoutes(evaluatedRoutes);

        List<RouteOption> routes = shadeRoutesNotUseful
                ? routeSelector.selectShortestAndSafe(evaluatedRoutes)
                : routeSelector.select(evaluatedRoutes);

        if (!shadeRoutesNotUseful && shouldRunSegmentOptimizer(request, routes)) {
            Optional<CandidateRoute> optimizedCandidate = segmentGraphRouteOptimizer.optimize(evaluatedRoutes);

            if (optimizedCandidate.isPresent()) {
                List<CandidateRoute> combinedCandidates = new ArrayList<>(candidates);
                combinedCandidates.add(optimizedCandidate.get());

                evaluatedRoutes = routeEvaluator.evaluate(combinedCandidates, request);

                shadeRoutesNotUseful = shouldHideShadeRoutes(evaluatedRoutes);

                routes = shadeRoutesNotUseful
                        ? routeSelector.selectShortestAndSafe(evaluatedRoutes)
                        : routeSelector.select(evaluatedRoutes);
            }
        }

        RouteOption representative = routes.isEmpty()
                ? new RouteOption("SHORTEST", 0, 0.0, 0.0, 0.0, 1.0, 1.0, "empty", List.of(start, end))
                : routes.get(0);

        return new RouteResponse(
                "route candidates calculated",
                request.getStartLat(),
                request.getStartLon(),
                request.getEndLat(),
                request.getEndLon(),
                representative.getDistance(),
                representative.getShadeScore(),
                representative.getTotalScore(),
                request.getTemperatureCelsius(),
                request.getHumidity(),
                request.getWindSpeedMps(),
                request.getCloudCover(),
                request.getRaining(),
                representative.getWeatherComfortScore(),
                representative.getHeatStressScore(),
                representative.getPoints(),
                routes
        );
    }

    /**
     * SHADE / BALANCED를 숨길지 판단한다.
     *
     * 비/구름 값만 보고 SHORTEST + SAFE로 줄이면,
     * 실제로 햇빛/그늘 구간이 존재하는데도 경로가 2개만 표시되는 문제가 생긴다.
     *
     * 따라서 날씨 값이 아니라 실제 계산된 shadeScore만 기준으로 판단한다.
     * 모든 후보 경로가 거의 100% 그늘일 때만 SHORTEST + SAFE만 제공한다.
     */
    private boolean shouldHideShadeRoutes(List<EvaluatedRoute> evaluatedRoutes) {
        if (evaluatedRoutes.isEmpty()) {
            return false;
        }

        return evaluatedRoutes.stream()
                .allMatch(route -> route.shadeScore() >= 0.995);
    }

    private boolean shouldRunSegmentOptimizer(RouteRequest request, List<RouteOption> routes) {
        if (!segmentOptimizerEnabled) {
            return false;
        }

        if ("SHORTEST".equalsIgnoreCase(request.getPreference())) {
            return false;
        }

        return !segmentOptimizerOnlyWhenMissingRoute || routes.size() < 3;
    }
}