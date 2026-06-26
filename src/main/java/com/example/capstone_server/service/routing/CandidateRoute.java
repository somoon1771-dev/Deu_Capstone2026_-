package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RoutePoint;

import java.util.List;

public record CandidateRoute(
        String id,
        String source,
        List<RoutePoint> points,
        double shadeBias
) {
}
