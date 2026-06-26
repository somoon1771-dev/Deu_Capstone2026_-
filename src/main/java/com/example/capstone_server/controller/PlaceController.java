package com.example.capstone_server.controller;

import com.example.capstone_server.dto.PlaceSearchResult;
import com.example.capstone_server.service.PlaceSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/places")
public class PlaceController {
    private final PlaceSearchService placeSearchService;

    public PlaceController(PlaceSearchService placeSearchService) {
        this.placeSearchService = placeSearchService;
    }

    @GetMapping("/search")
    public List<PlaceSearchResult> search(@RequestParam String query) {
        return placeSearchService.search(query);
    }
}
