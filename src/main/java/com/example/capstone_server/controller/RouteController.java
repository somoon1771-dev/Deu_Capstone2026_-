package com.example.capstone_server.controller;

import com.example.capstone_server.dto.RouteRequest;
import com.example.capstone_server.dto.RouteResponse;
import com.example.capstone_server.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @PostMapping
    public RouteResponse findRoutes(@Valid @RequestBody RouteRequest request) {
        return routeService.findRoutes(request);
    }
}