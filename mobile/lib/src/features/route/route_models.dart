class RoutePoint {
  const RoutePoint({
    required this.lat,
    required this.lon,
  });

  factory RoutePoint.fromJson(Map<String, dynamic> json) {
    return RoutePoint(
      lat: (json['lat'] as num).toDouble(),
      lon: (json['lon'] as num).toDouble(),
    );
  }

  final double lat;
  final double lon;

  Map<String, dynamic> toJson() => {
        'lat': lat,
        'lon': lon,
      };
}

class CctvPoint {
  const CctvPoint({
    required this.id,
    required this.district,
    required this.address,
    required this.purpose,
    required this.qty,
    required this.lat,
    required this.lon,
  });

  factory CctvPoint.fromJson(Map<String, dynamic> json) {
    return CctvPoint(
      id: (json['id'] as num?)?.toInt() ?? 0,
      district: json['district'] as String? ?? '',
      address: json['address'] as String? ?? '',
      purpose: json['purpose'] as String? ?? '',
      qty: (json['qty'] as num?)?.toInt() ?? 1,
      lat: (json['lat'] as num?)?.toDouble() ?? 0.0,
      lon: (json['lon'] as num?)?.toDouble() ?? 0.0,
    );
  }

  final int id;
  final String district;
  final String address;
  final String purpose;
  final int qty;
  final double lat;
  final double lon;

  bool get hasValidCoordinate {
    return lat.abs() > 0.000001 && lon.abs() > 0.000001;
  }

  String get displayTitle {
    if (address.isNotEmpty) {
      return address;
    }
    if (district.isNotEmpty) {
      return district;
    }
    return 'CCTV';
  }
}

class RouteRequest {
  const RouteRequest({
    required this.startLat,
    required this.startLon,
    required this.endLat,
    required this.endLon,
    required this.preference,
    this.departureTime,
  });

  final double startLat;
  final double startLon;
  final double endLat;
  final double endLon;
  final String preference;
  final String? departureTime;

  Map<String, dynamic> toJson() => {
        'startLat': startLat,
        'startLon': startLon,
        'endLat': endLat,
        'endLon': endLon,
        'preference': preference,
        if (departureTime != null) 'departureTime': departureTime,
      };
}

class RouteResponse {
  const RouteResponse({
    required this.routes,
    required this.temperatureCelsius,
    required this.humidity,
    required this.windSpeedMps,
    required this.cloudCover,
    required this.raining,
    required this.weatherComfortScore,
    required this.heatStressScore,
  });

  factory RouteResponse.fromJson(Map<String, dynamic> json) {
    return RouteResponse(
      routes: ((json['routes'] as List<dynamic>?) ?? [])
          .map((item) => RouteOption.fromJson(item as Map<String, dynamic>))
          .toList(),
      temperatureCelsius: (json['temperatureCelsius'] as num?)?.toDouble(),
      humidity: (json['humidity'] as num?)?.toDouble(),
      windSpeedMps: (json['windSpeedMps'] as num?)?.toDouble(),
      cloudCover: (json['cloudCover'] as num?)?.toDouble(),
      raining: json['raining'] as bool?,
      weatherComfortScore: (json['weatherComfortScore'] as num?)?.toDouble(),
      heatStressScore: (json['heatStressScore'] as num?)?.toDouble(),
    );
  }

  final List<RouteOption> routes;
  final double? temperatureCelsius;
  final double? humidity;
  final double? windSpeedMps;
  final double? cloudCover;
  final bool? raining;
  final double? weatherComfortScore;
  final double? heatStressScore;
}

class RouteOption {
  const RouteOption({
    required this.routeType,
    required this.distance,
    required this.shadeScore,
    required this.distanceScore,
    required this.totalScore,
    required this.weatherComfortScore,
    required this.heatStressScore,
    required this.longestSunExposureMeters,
    required this.sunExposurePenalty,
    required this.safetyScore,
    required this.cctvCount,
    required this.points,
    required this.segmentScores,
    required this.nearbyCctvs,
  });

  factory RouteOption.fromJson(Map<String, dynamic> json) {
    return RouteOption(
      routeType: json['routeType'] as String? ?? 'ROUTE',
      distance: (json['distance'] as num?)?.toInt() ?? 0,
      shadeScore: (json['shadeScore'] as num?)?.toDouble() ?? 0,
      distanceScore: (json['distanceScore'] as num?)?.toDouble() ?? 0,
      totalScore: (json['totalScore'] as num?)?.toDouble() ?? 0,
      weatherComfortScore: (json['weatherComfortScore'] as num?)?.toDouble(),
      heatStressScore: (json['heatStressScore'] as num?)?.toDouble(),
      longestSunExposureMeters: (json['longestSunExposureMeters'] as num?)?.toDouble(),
      sunExposurePenalty: (json['sunExposurePenalty'] as num?)?.toDouble(),
      safetyScore: (json['safetyScore'] as num?)?.toDouble(),
      cctvCount: (json['cctvCount'] as num?)?.toInt(),
      points: ((json['points'] as List<dynamic>?) ?? [])
          .map((item) => RoutePoint.fromJson(item as Map<String, dynamic>))
          .toList(),
      segmentScores: ((json['segmentScores'] as List<dynamic>?) ?? [])
          .map((item) => RouteSegmentScore.fromJson(item as Map<String, dynamic>))
          .toList(),
      nearbyCctvs: ((json['nearbyCctvs'] as List<dynamic>?) ?? [])
          .map((item) => CctvPoint.fromJson(item as Map<String, dynamic>))
          .where((cctv) => cctv.hasValidCoordinate)
          .toList(),
    );
  }

  final String routeType;
  final int distance;
  final double shadeScore;
  final double distanceScore;
  final double totalScore;
  final double? weatherComfortScore;
  final double? heatStressScore;
  final double? longestSunExposureMeters;
  final double? sunExposurePenalty;
  final double? safetyScore;
  final int? cctvCount;
  final List<RoutePoint> points;
  final List<RouteSegmentScore> segmentScores;
  final List<CctvPoint> nearbyCctvs;

  bool get isSafeRoute => routeType.toUpperCase() == 'SAFE';
}

class RouteSegmentScore {
  const RouteSegmentScore({
    required this.start,
    required this.end,
    required this.shadeScore,
    required this.distance,
  });

  factory RouteSegmentScore.fromJson(Map<String, dynamic> json) {
    return RouteSegmentScore(
      start: RoutePoint.fromJson(json['start'] as Map<String, dynamic>),
      end: RoutePoint.fromJson(json['end'] as Map<String, dynamic>),
      shadeScore: (json['shadeScore'] as num?)?.toDouble() ?? 0,
      distance: (json['distance'] as num?)?.toInt() ?? 0,
    );
  }

  final RoutePoint start;
  final RoutePoint end;
  final double shadeScore;
  final int distance;

  bool get isShade => shadeScore >= 0.5;
}
