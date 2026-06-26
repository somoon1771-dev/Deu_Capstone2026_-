package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RoutePoint;
import com.example.capstone_server.dto.RouteRequest;
import com.example.capstone_server.dto.RouteSegmentScore;
import com.example.capstone_server.service.ShadowService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class RouteEvaluator {
    private static final double SAMPLE_INTERVAL_METERS = 25.0;

    private final ShadowService shadowService;
    private final CctvSafetyService cctvSafetyService;

    public RouteEvaluator(ShadowService shadowService,
                          CctvSafetyService cctvSafetyService) {
        this.shadowService = shadowService;
        this.cctvSafetyService = cctvSafetyService;
    }

    public List<EvaluatedRoute> evaluate(List<CandidateRoute> candidates, RouteRequest request) {
        double shortestDistance = candidates.stream()
                .mapToDouble(candidate -> GeoMath.pathDistanceMeters(candidate.points()))
                .min()
                .orElse(1.0);
        List<ShadowService.RouteShadeAnalysis> shadeAnalyses = shadowService.calculateRouteShadeAnalyses(
                candidates.stream().map(CandidateRoute::points).toList(),
                request.getDepartureTime()
        );
        double shortestShade = 0.0;
        List<EvaluatedRoute> evaluated = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            CandidateRoute candidate = candidates.get(i);
            ShadowService.RouteShadeAnalysis analysis = routeAnalysis(i, shadeAnalyses);
            EvaluatedRoute route = evaluate(candidate, request, shortestDistance, 0.0, analysis);
            evaluated.add(route);
            if ("shortest".equals(candidate.id())) {
                shortestShade = route.shadeScore();
            }
        }

        if (shortestShade == 0.0) {
            return evaluated;
        }

        List<EvaluatedRoute> withImprovement = new ArrayList<>();
        for (int i = 0; i < evaluated.size(); i++) {
            EvaluatedRoute route = evaluated.get(i);
            ShadowService.RouteShadeAnalysis analysis = routeAnalysis(i, shadeAnalyses);
            withImprovement.add(evaluate(route.candidate(), request, shortestDistance, shortestShade, analysis));
        }
        return withImprovement;
    }

    private EvaluatedRoute evaluate(CandidateRoute candidate,
                                    RouteRequest request,
                                    double shortestDistance,
                                    double shortestShade,
                                    ShadowService.RouteShadeAnalysis shadeAnalysis) {
        List<RoutePoint> samples = GeoMath.samplePath(candidate.points(), SAMPLE_INTERVAL_METERS);
        double distanceMeters = GeoMath.pathDistanceMeters(candidate.points());
        double shadeScore = shadeAnalysis.shadeCoverage();
        double detourRatio = shortestDistance <= 0.0 ? 1.0 : distanceMeters / shortestDistance;
        double distanceScore = GeoMath.clamp(shortestDistance / Math.max(distanceMeters, 1.0), 0.0, 1.0);
        WeatherContext weatherContext = weatherContext(request);
        double alpha = shadeAlpha(request, weatherContext);
        List<RouteSegmentScore> segmentScores = segmentScores(shadeAnalysis, weatherContext);
        double segmentScore = segmentScores.stream()
                .mapToDouble(score -> score.getSegmentScore() * score.getDistance())
                .sum() / Math.max(distanceMeters, 1.0);
        double weatherComfortScore = routeWeatherComfortScore(segmentScores, distanceMeters);
        double longestSunExposureMeters = longestSunExposureMeters(shadeAnalysis);
        double sunExposurePenalty = sunExposurePenalty(longestSunExposureMeters, weatherContext);
        ProgressMetrics progress = calculateProgress(candidate.points(), samples);
        double zigzagScore = calculateZigzagScore(candidate.points());
        CctvSafetyService.CctvSafetyResult safetyResult = cctvSafetyService.calculateSafety(candidate.points());
        double routeQualityScore = GeoMath.clamp(
                progress.progressEfficiency() * 0.72
                        + (1.0 - progress.maxRegressionRatio()) * 0.20
                        + (1.0 - zigzagScore) * 0.08,
                0.0,
                1.0
        );
        double totalScore = GeoMath.clamp(
                ((1.0 - alpha) * distanceScore + alpha * segmentScore) * 0.90
                        + routeQualityScore * 0.10
                        - sunExposurePenalty,
                0.0,
                1.0
        );

        return new EvaluatedRoute(
                candidate,
                distanceMeters,
                round(shadeScore),
                round(distanceScore),
                round(totalScore),
                round(segmentScore),
                round(weatherComfortScore),
                round(weatherContext.heatStress()),
                round(longestSunExposureMeters),
                round(sunExposurePenalty),
                round(detourRatio),
                round(progress.progressEfficiency()),
                round(progress.maxRegressionRatio()),
                round(zigzagScore),
                round(shadeScore - shortestShade),
                round(safetyResult.safetyScore()),
                safetyResult.cctvCount(),
                safetyResult.nearbyCctvs(),
                segmentScores
        );
    }

    private ShadowService.RouteShadeAnalysis routeAnalysis(int index, List<ShadowService.RouteShadeAnalysis> analyses) {
        if (index < analyses.size()) {
            return analyses.get(index);
        }
        return new ShadowService.RouteShadeAnalysis(0.0, List.of());
    }

    private List<RouteSegmentScore> segmentScores(ShadowService.RouteShadeAnalysis analysis,
                                                  WeatherContext weatherContext) {
        List<RouteSegmentScore> scores = new ArrayList<>();
        for (ShadowService.RouteSegmentShade segment : analysis.segments()) {
            double congestionScore = 1.0;
            double shadeScore = segment.shadeCoverage();
            double weatherComfortScore = weatherComfortScore(shadeScore, weatherContext);
            double segmentScore = GeoMath.clamp(
                    shadeScore * weatherContext.shadeWeight() * 0.58
                            + weatherComfortScore * 0.27
                            + congestionScore * 0.15,
                    0.0,
                    1.0
            );
            scores.add(new RouteSegmentScore(
                    segment.start(),
                    segment.end(),
                    (int) Math.round(segment.distanceMeters()),
                    round(shadeScore),
                    congestionScore,
                    round(weatherComfortScore),
                    round(weatherContext.heatStress()),
                    round(segmentScore)
            ));
        }
        return scores;
    }

    private double routeWeatherComfortScore(List<RouteSegmentScore> segmentScores, double distanceMeters) {
        if (segmentScores.isEmpty()) {
            return 1.0;
        }
        return GeoMath.clamp(
                segmentScores.stream()
                        .mapToDouble(score -> score.getWeatherComfortScore() * score.getDistance())
                        .sum() / Math.max(distanceMeters, 1.0),
                0.0,
                1.0
        );
    }

    private double longestSunExposureMeters(ShadowService.RouteShadeAnalysis analysis) {
        double longest = 0.0;
        double current = 0.0;
        for (ShadowService.RouteSegmentShade segment : analysis.segments()) {
            double sunDistance = segment.distanceMeters() * (1.0 - GeoMath.clamp(segment.shadeCoverage(), 0.0, 1.0));
            if (segment.shadeCoverage() < 0.5) {
                current += sunDistance;
                longest = Math.max(longest, current);
            } else {
                current = 0.0;
            }
        }
        return longest;
    }

    private double sunExposurePenalty(double longestSunExposureMeters, WeatherContext weatherContext) {
        if (weatherContext.sunlightBlocked()) {
            return 0.18;
        }
        double exposureRisk = GeoMath.clamp(longestSunExposureMeters / 320.0, 0.0, 1.0);
        double weatherSensitivity = 0.25 + weatherContext.heatStress() * 0.75;
        return GeoMath.clamp(exposureRisk * weatherSensitivity * 0.18, 0.0, 0.18);
    }

    private ProgressMetrics calculateProgress(List<RoutePoint> routePoints, List<RoutePoint> samples) {
        if (samples.size() < 2) {
            return new ProgressMetrics(1.0, 0.0);
        }

        RoutePoint destination = routePoints.get(routePoints.size() - 1);
        int progressing = 0;
        double regressingDistance = 0.0;
        double currentRegression = 0.0;
        double maxRegression = 0.0;
        double totalDistance = 0.0;

        for (int i = 1; i < samples.size(); i++) {
            RoutePoint previous = samples.get(i - 1);
            RoutePoint current = samples.get(i);
            double segmentDistance = GeoMath.distanceMeters(previous, current);
            totalDistance += segmentDistance;
            double previousToEnd = GeoMath.distanceMeters(previous, destination);
            double currentToEnd = GeoMath.distanceMeters(current, destination);
            if (currentToEnd <= previousToEnd) {
                progressing++;
                currentRegression = 0.0;
            } else {
                regressingDistance += segmentDistance;
                currentRegression += segmentDistance;
                maxRegression = Math.max(maxRegression, currentRegression);
            }
        }

        double progressEfficiency = progressing / (double) (samples.size() - 1);
        double maxRegressionRatio = totalDistance <= 0.0 ? 0.0 : Math.max(maxRegression, regressingDistance * 0.35) / totalDistance;
        return new ProgressMetrics(progressEfficiency, maxRegressionRatio);
    }

    private double calculateZigzagScore(List<RoutePoint> points) {
        if (points.size() < 3) {
            return 0.0;
        }

        int turns = 0;
        for (int i = 2; i < points.size(); i++) {
            double previousBearing = GeoMath.bearingDegrees(points.get(i - 2), points.get(i - 1));
            double currentBearing = GeoMath.bearingDegrees(points.get(i - 1), points.get(i));
            if (GeoMath.angleDifferenceDegrees(previousBearing, currentBearing) >= 60.0) {
                turns++;
            }
        }
        return turns / (double) (points.size() - 2);
    }

    private double weatherCloudCover(RouteRequest request) {
        if (request.getCloudCover() == null) {
            return 0.0;
        }
        double value = request.getCloudCover();
        return GeoMath.clamp(value > 1.0 ? value / 100.0 : value, 0.0, 1.0);
    }

    private WeatherContext weatherContext(RouteRequest request) {
        double cloudCover = weatherCloudCover(request);
        boolean sunlightBlocked = Boolean.TRUE.equals(request.getRaining()) || cloudCover >= 0.80;
        double shadeWeight = sunlightBlocked ? 0.0 : 1.0 - cloudCover * 0.35;

        double temperature = request.getTemperatureCelsius() == null ? 26.0 : request.getTemperatureCelsius();
        double humidity = normalizedPercent(request.getHumidity(), 55.0);
        double windSpeed = request.getWindSpeedMps() == null ? 1.5 : Math.max(0.0, request.getWindSpeedMps());

        double humidHeat = Math.max(0.0, temperature - 27.0) / 13.0;
        double humidityPenalty = humidHeat * humidity * 0.45;
        double windRelief = GeoMath.clamp(windSpeed / 6.0, 0.0, 1.0) * 0.25;
        double rainPenalty = Boolean.TRUE.equals(request.getRaining()) ? 0.18 : 0.0;
        double heatStress = sunlightBlocked
                ? 0.0
                : GeoMath.clamp(humidHeat + humidityPenalty - windRelief + rainPenalty, 0.0, 1.0);
        return new WeatherContext(shadeWeight, heatStress, sunlightBlocked);
    }

    private double weatherComfortScore(double shadeScore, WeatherContext weatherContext) {
        double shadeRelief = shadeScore * weatherContext.heatStress() * 0.45;
        return GeoMath.clamp(1.0 - weatherContext.heatStress() + shadeRelief, 0.0, 1.0);
    }

    private double normalizedPercent(Double value, double defaultValue) {
        if (value == null) {
            value = defaultValue;
        }
        return GeoMath.clamp(value > 1.0 ? value / 100.0 : value, 0.0, 1.0);
    }

    private double shadeAlpha(RouteRequest request, WeatherContext weatherContext) {
        String preference = request.getPreference() == null ? "" : request.getPreference().trim().toUpperCase();
        if (weatherContext.sunlightBlocked()) {
            return 0.18;
        }
        if ("SHADE".equals(preference)) {
            return 0.72;
        }
        if ("SHORTEST".equals(preference)) {
            return 0.35;
        }
        if (Boolean.TRUE.equals(request.getRaining())) {
            return 0.35;
        }
        return weatherContext.heatStress() >= 0.45 ? 0.68 : 0.55;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record WeatherContext(double shadeWeight, double heatStress, boolean sunlightBlocked) {
    }

    private record ProgressMetrics(double progressEfficiency, double maxRegressionRatio) {
    }
}
