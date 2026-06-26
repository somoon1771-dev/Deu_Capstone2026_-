package com.example.capstone_server.controller;

import com.example.capstone_server.dto.ShadowRequest;
import com.example.capstone_server.dto.ShadowResponse;
import com.example.capstone_server.service.ShadowService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shadows")
public class ShadowController {
    private final ShadowService shadowService;

    public ShadowController(ShadowService shadowService) {
        this.shadowService = shadowService;
    }

    @PostMapping
    public ShadowResponse findShadows(@RequestBody ShadowRequest request) {
        return shadowService.findShadows(request);
    }
}
