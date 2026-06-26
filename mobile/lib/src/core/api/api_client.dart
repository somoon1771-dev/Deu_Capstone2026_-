import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../features/route/route_models.dart';
import '../../features/search/place_models.dart';

class ApiClient {
  ApiClient({required this.baseUrl});

  final String baseUrl;

  static const defaultBaseUrls = [
    String.fromEnvironment('BACKEND_URL'),
    'http://10.0.2.2:8080',
    'http://192.168.45.100:8080',
    'http://127.0.0.1:8080',
    'http://localhost:8080',
  ];

  static Future<String> resolveBaseUrl({String? preferredBaseUrl}) async {
    final preferred = preferredBaseUrl?.trim();
    if (preferred != null &&
        preferred.isNotEmpty &&
        await ApiClient(baseUrl: preferred).isHealthy()) {
      return preferred;
    }

    for (final candidate in defaultBaseUrls) {
      final baseUrl = candidate.trim();
      if (baseUrl.isEmpty) {
        continue;
      }
      if (await ApiClient(baseUrl: baseUrl).isHealthy()) {
        return baseUrl;
      }
    }
    throw ApiException(0, 'No reachable backend server found');
  }

  Uri _uri(String path, [Map<String, String>? query]) {
    return Uri.parse('$baseUrl$path').replace(queryParameters: query);
  }

  Future<bool> isHealthy() async {
    try {
      final response = await http
          .get(_uri('/api/health'))
          .timeout(const Duration(seconds: 2));
      return response.statusCode >= 200 &&
          response.statusCode < 300 &&
          utf8.decode(response.bodyBytes).trim() == 'ok';
    } catch (_) {
      return false;
    }
  }

  Future<List<PlaceSearchResult>> searchPlaces(String query) async {
    final response = await http.get(
      _uri('/api/places/search', {'query': query}),
    );
    _ensureSuccess(response);
    final decoded = jsonDecode(utf8.decode(response.bodyBytes)) as List<dynamic>;
    return decoded
        .map((item) => PlaceSearchResult.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<RouteResponse> requestRoutes(RouteRequest request) async {
    final response = await http.post(
      _uri('/api/routes'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode(request.toJson()),
    );
    _ensureSuccess(response);
    return RouteResponse.fromJson(
      jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>,
    );
  }

  void _ensureSuccess(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ApiException(response.statusCode, response.body);
    }
  }
}

class ApiException implements Exception {
  ApiException(this.statusCode, this.body);

  final int statusCode;
  final String body;

  @override
  String toString() => 'API $statusCode: $body';
}
