package com.example.capstone_server.service.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class RouteQualityFilter {

    public List<EvaluatedRoute> shadeCandidates(List<EvaluatedRoute> routes) {
        return routes.stream()
                .filter(route -> !"shortest".equals(route.candidate().id()))
                .filter(this::passesShadeRules)
                .sorted(Comparator.comparingDouble(EvaluatedRoute::shadeScore).reversed())
                .toList();
    }

    public List<EvaluatedRoute> balancedCandidates(List<EvaluatedRoute> routes) {
        return routes.stream()
                .filter(route -> !"shortest".equals(route.candidate().id()))
                .filter(this::passesBalancedRules)
                .sorted(Comparator.comparingDouble(EvaluatedRoute::totalScore).reversed())
                .toList();
    }

    public Optional<EvaluatedRoute> bestFallbackShade(List<EvaluatedRoute> routes) {
        return routes.stream()
                .filter(route -> !"shortest".equals(route.candidate().id()))
                .filter(route -> route.detourRatio() <= 2.0)
                .filter(route -> route.progressEfficiency() >= 0.60)
                .filter(route -> route.maxRegressionRatio() <= 0.22)
                .filter(route -> route.zigzagScore() <= 0.80)
                .max(Comparator.comparingDouble(EvaluatedRoute::shadeScore));
    }

    public Optional<EvaluatedRoute> bestFallbackBalanced(List<EvaluatedRoute> routes) {
        return routes.stream()
                .filter(route -> !"shortest".equals(route.candidate().id()))
                .filter(route -> route.detourRatio() <= 1.45)
                .filter(route -> route.progressEfficiency() >= 0.82)
                .filter(route -> route.maxRegressionRatio() <= 0.10)
                .filter(route -> route.zigzagScore() <= 0.35)
                .max(Comparator.comparingDouble(EvaluatedRoute::totalScore));
    }

    private boolean passesShadeRules(EvaluatedRoute route) {
        double detourLimit = route.shadeScore() >= 0.40 ? 2.0 : 1.8;
        return route.detourRatio() <= detourLimit
                && route.progressEfficiency() >= 0.60
                && route.maxRegressionRatio() <= 0.22
                && route.zigzagScore() <= 0.80;
    }

    private boolean passesBalancedRules(EvaluatedRoute route) {
        return route.detourRatio() <= 1.45
                && route.progressEfficiency() >= 0.82
                && route.maxRegressionRatio() <= 0.10
                && route.zigzagScore() <= 0.35;
    }
}