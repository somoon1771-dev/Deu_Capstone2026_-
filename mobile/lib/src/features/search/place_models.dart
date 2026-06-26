class PlaceSearchResult {
  const PlaceSearchResult({
    required this.name,
    required this.address,
    required this.category,
    required this.lat,
    required this.lon,
  });

  factory PlaceSearchResult.fromJson(Map<String, dynamic> json) {
    return PlaceSearchResult(
      name: json['name'] as String? ?? '',
      address: json['address'] as String? ?? '',
      category: json['category'] as String? ?? '',
      lat: (json['lat'] as num).toDouble(),
      lon: (json['lon'] as num).toDouble(),
    );
  }

  final String name;
  final String address;
  final String category;
  final double lat;
  final double lon;
}
