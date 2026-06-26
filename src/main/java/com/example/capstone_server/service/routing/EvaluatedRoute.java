package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.CctvPointDto;
import com.example.capstone_server.dto.RouteSegmentScore;

import java.util.List;

public record EvaluatedRoute(
        CandidateRoute candidate,
        double distanceMeters,
        double shadeScore,
        double distanceScore,
        double totalScore,
        double segmentScore,
        double weatherComfortScore,
        double heatStressScore,
        double longestSunExposureMeters,
        double sunExposurePenalty,
        double detourRatio,
        double progressEfficiency,
        double maxRegressionRatio,
        double zigzagScore,
        double shadowImprovement,
        double safetyScore,
        int cctvCount,
        List<CctvPointDto> nearbyCctvs,
        List<RouteSegmentScore> segmentScores
) {
}
