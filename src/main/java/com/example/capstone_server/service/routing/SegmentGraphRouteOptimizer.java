package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RoutePoint;
import com.example.capstone_server.dto.RouteSegmentScore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class SegmentGraphRouteOptimizer {
    private static final double NODE_PRECISION = 10_000.0;
    private static final double MIN_EDGE_COST_FACTOR = 0.22;
    private static final double POOR_SHADE_PENALTY = 0.18;

    public Optional<CandidateRoute> optimize(List<EvaluatedRoute> evaluatedRoutes) {
        if (evaluatedRoutes == null || evaluatedRoutes.isEmpty()) {
            return Optional.empty();
        }

        RoutePoint start = evaluatedRoutes.get(0).candidate().points().get(0);
        List<RoutePoint> firstPoints = evaluatedRoutes.get(0).candidate().points();
        RoutePoint end = firstPoints.get(firstPoints.size() - 1);

        Graph graph = buildGraph(evaluatedRoutes);
        NodeKey startKey = NodeKey.from(start);
        NodeKey endKey = NodeKey.from(end);
        List<RoutePoint> points = shortestPath(graph, startKey, endKey);
        if (points.size() < 2 || duplicatesExistingRoute(points, evaluatedRoutes)) {
            return Optional.empty();
        }

        return Optional.of(new CandidateRoute(
                "segment-optimized",
                "segment-graph-optimized",
                points,
                0.18
        ));
    }

    private Graph buildGraph(List<EvaluatedRoute> routes) {
        Graph graph = new Graph();
        for (EvaluatedRoute route : routes) {
            for (RouteSegmentScore segment : route.segmentScores()) {
                NodeKey from = NodeKey.from(segment.getStart());
                NodeKey to = NodeKey.from(segment.getEnd());
                graph.points.putIfAbsent(from, segment.getStart());
                graph.points.putIfAbsent(to, segment.getEnd());
                double cost = edgeCost(segment);
                graph.edges.computeIfAbsent(from, ignored -> new ArrayList<>())
                        .add(new Edge(to, cost));
                graph.edges.computeIfAbsent(to, ignored -> new ArrayList<>())
                        .add(new Edge(from, cost * 1.03));
            }
        }
        return graph;
    }

    private double edgeCost(RouteSegmentScore segment) {
        double score = segment.getSegmentScore() == null ? 0.0 : segment.getSegmentScore();
        double shadeScore = segment.getShadeScore() == null ? 0.0 : segment.getShadeScore();
        double heatStress = segment.getHeatStressScore() == null ? 0.0 : segment.getHeatStressScore();
        double poorShadePenalty = (1.0 - shadeScore) * heatStress * POOR_SHADE_PENALTY;
        double costFactor = Math.max(MIN_EDGE_COST_FACTOR, 1.0 - score + poorShadePenalty);
        return Math.max(1.0, segment.getDistance()) * costFactor;
    }

    private List<RoutePoint> shortestPath(Graph graph, NodeKey start, NodeKey end) {
        if (!graph.points.containsKey(start) || !graph.points.containsKey(end)) {
            return List.of();
        }

        Map<NodeKey, Double> distances = new HashMap<>();
        Map<NodeKey, NodeKey> previous = new HashMap<>();
        Set<NodeKey> visited = new HashSet<>();
        PriorityQueue<PathState> queue = new PriorityQueue<>(Comparator.comparingDouble(PathState::cost));

        distances.put(start, 0.0);
        queue.add(new PathState(start, 0.0));

        while (!queue.isEmpty()) {
            PathState state = queue.poll();
            if (!visited.add(state.node())) {
                continue;
            }
            if (state.node().equals(end)) {
                break;
            }

            for (Edge edge : graph.edges.getOrDefault(state.node(), List.of())) {
                if (visited.contains(edge.to())) {
                    continue;
                }
                double nextCost = state.cost() + edge.cost();
                if (nextCost < distances.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
                    distances.put(edge.to(), nextCost);
                    previous.put(edge.to(), state.node());
                    queue.add(new PathState(edge.to(), nextCost));
                }
            }
        }

        if (!distances.containsKey(end)) {
            return List.of();
        }
        return restorePath(graph, previous, start, end);
    }

    private List<RoutePoint> restorePath(Graph graph, Map<NodeKey, NodeKey> previous, NodeKey start, NodeKey end) {
        List<NodeKey> reversed = new ArrayList<>();
        NodeKey current = end;
        while (current != null) {
            reversed.add(current);
            if (current.equals(start)) {
                break;
            }
            current = previous.get(current);
        }
        if (reversed.isEmpty() || !reversed.get(reversed.size() - 1).equals(start)) {
            return List.of();
        }

        List<RoutePoint> points = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            points.add(graph.points.get(reversed.get(i)));
        }
        return removeAdjacentDuplicates(points);
    }

    private List<RoutePoint> removeAdjacentDuplicates(List<RoutePoint> points) {
        List<RoutePoint> deduped = new ArrayList<>();
        for (RoutePoint point : points) {
            if (deduped.isEmpty() || GeoMath.distanceMeters(deduped.get(deduped.size() - 1), point) > 1.0) {
                deduped.add(point);
            }
        }
        return deduped;
    }

    private boolean duplicatesExistingRoute(List<RoutePoint> points, List<EvaluatedRoute> evaluatedRoutes) {
        String candidateKey = routeKey(points);
        for (EvaluatedRoute route : evaluatedRoutes) {
            if (candidateKey.equals(routeKey(route.candidate().points()))) {
                return true;
            }
        }
        return false;
    }

    private String routeKey(List<RoutePoint> points) {
        Set<String> keys = new LinkedHashSet<>();
        for (RoutePoint point : points) {
            keys.add(NodeKey.from(point).value());
        }
        return String.join("|", keys);
    }

    private static class Graph {
        private final Map<NodeKey, RoutePoint> points = new HashMap<>();
        private final Map<NodeKey, List<Edge>> edges = new HashMap<>();
    }

    private record Edge(NodeKey to, double cost) {
    }

    private record PathState(NodeKey node, double cost) {
    }

    private record NodeKey(String value) {
        private static NodeKey from(RoutePoint point) {
            double lat = Math.round(point.getLat() * NODE_PRECISION) / NODE_PRECISION;
            double lon = Math.round(point.getLon() * NODE_PRECISION) / NODE_PRECISION;
            return new NodeKey(lat + "," + lon);
        }
    }
}
