import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:geolocator/geolocator.dart';
import 'package:latlong2/latlong.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/api/api_client.dart';
import '../../core/location/location_service.dart';
import '../route/route_models.dart';
import '../search/place_models.dart';

class MapScreen extends StatefulWidget {
  const MapScreen({super.key});

  @override
  State<MapScreen> createState() => _MapScreenState();
}

class _MapScreenState extends State<MapScreen> {
  static const _serverUrlPreferenceKey = 'server_url';
  static const _cctvMarkerLimit = 120;

  final _mapController = MapController();
  final _locationService = LocationService();
  final _destinationController = TextEditingController(text: '부산교육대학교');
  final _serverUrlController = TextEditingController();

  StreamSubscription<Position>? _locationSubscription;
  String? _apiBaseUrl;
  String? _preferredBaseUrl;
  Position? _currentPosition;
  PlaceSearchResult? _destinationPlace;
  List<PlaceSearchResult> _destinationResults = [];
  RouteResponse? _routeResponse;
  RouteOption? _selectedRoute;
  bool _loading = false;
  String _status = '서버 연결 중...';

  ApiClient get _api => ApiClient(baseUrl: _apiBaseUrl ?? '');

  @override
  void initState() {
    super.initState();
    _resolveBackend();
    _startLocationTracking();
  }

  @override
  void dispose() {
    _locationSubscription?.cancel();
    _destinationController.dispose();
    _serverUrlController.dispose();
    super.dispose();
  }

  Future<void> _resolveBackend() async {
    try {
      final preferences = await SharedPreferences.getInstance();
      _preferredBaseUrl = preferences.getString(_serverUrlPreferenceKey)?.trim();
      final baseUrl = await ApiClient.resolveBaseUrl(preferredBaseUrl: _preferredBaseUrl);
      if (!mounted) {
        return;
      }
      setState(() {
        _apiBaseUrl = baseUrl;
        _status = '서버 연결됨: $baseUrl';
      });
    } catch (_) {
      _setStatus('서버를 찾을 수 없습니다.');
    }
  }

  Future<void> _openServerSettings() async {
    _serverUrlController.text = _preferredBaseUrl ?? _apiBaseUrl ?? '';
    final nextUrl = await showDialog<String>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: const Text('서버 주소 설정'),
          content: TextField(
            controller: _serverUrlController,
            keyboardType: TextInputType.url,
            decoration: const InputDecoration(
              labelText: '서버 URL',
              hintText: 'https://example.trycloudflare.com',
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(''),
              child: const Text('자동 연결'),
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(_serverUrlController.text.trim()),
              child: const Text('저장'),
            ),
          ],
        );
      },
    );
    if (nextUrl == null) {
      return;
    }
    await _saveServerUrl(nextUrl);
  }

  Future<void> _saveServerUrl(String nextUrl) async {
    final preferences = await SharedPreferences.getInstance();
    final trimmed = nextUrl.trim();
    if (trimmed.isEmpty) {
      await preferences.remove(_serverUrlPreferenceKey);
      setState(() {
        _preferredBaseUrl = null;
        _apiBaseUrl = null;
        _status = '서버 연결 중...';
      });
      await _resolveBackend();
      return;
    }

    final normalized = _normalizeServerUrl(trimmed);
    final healthy = await ApiClient(baseUrl: normalized).isHealthy();
    if (!healthy) {
      _setStatus('서버 주소에 연결할 수 없습니다.');
      return;
    }
    await preferences.setString(_serverUrlPreferenceKey, normalized);
    setState(() {
      _preferredBaseUrl = normalized;
      _apiBaseUrl = normalized;
      _status = '서버 연결됨: $normalized';
    });
  }

  String _normalizeServerUrl(String value) {
    var normalized = value.trim();
    if (!normalized.startsWith('http://') && !normalized.startsWith('https://')) {
      normalized = 'https://$normalized';
    }
    while (normalized.endsWith('/')) {
      normalized = normalized.substring(0, normalized.length - 1);
    }
    return normalized;
  }

  Future<void> _startLocationTracking() async {
    final current = await _locationService.currentPosition();
    if (!mounted) {
      return;
    }
    setState(() {
      _currentPosition = current;
    });
    if (current != null) {
      _mapController.move(LatLng(current.latitude, current.longitude), 16);
    }

    _locationSubscription = _locationService.positionStream().listen((position) {
      if (!mounted) {
        return;
      }
      setState(() {
        _currentPosition = position;
      });
    });
  }

  Future<void> _searchDestination() async {
    if (_apiBaseUrl == null) {
      await _resolveBackend();
    }
    if (_apiBaseUrl == null) {
      return;
    }

    final query = _destinationController.text.trim();
    if (query.isEmpty) {
      _setStatus('목적지를 입력해주세요');
      return;
    }

    await _run(() async {
      final results = await _api.searchPlaces(query);
      setState(() {
        _destinationResults = results;
        _destinationPlace = null;
        _routeResponse = null;
        _selectedRoute = null;
      });
      _setStatus(results.isEmpty ? '검색 결과가 없습니다' : '목적지를 입력해주세요');
    });
  }

  Future<void> _requestRoute() async {
    if (_apiBaseUrl == null) {
      await _resolveBackend();
    }
    if (_apiBaseUrl == null) {
      return;
    }

    final destination = _destinationPlace;
    if (destination == null) {
      _setStatus('목적지를 선택해주세요');
      return;
    }

    final current = _currentPosition;
    if (current == null) {
      _setStatus('현재 위치를 확인하는 중입니다');
      return;
    }

    await _run(() async {
      final response = await _api.requestRoutes(
        RouteRequest(
          startLat: current.latitude,
          startLon: current.longitude,
          endLat: destination.lat,
          endLon: destination.lon,
          preference: 'BALANCED',
          departureTime: DateTime.now().toLocal().toIso8601String(),
        ),
      );
      final selected = response.routes.isNotEmpty ? response.routes.first : null;
      setState(() {
        _routeResponse = response;
        _selectedRoute = selected;
      });
      _fitSelectedRoute();
      _setStatus(selected == null ? '경로를 찾지 못했습니다' : '추천 경로 중 하나를 선택해주세요');
    });
  }

  Future<void> _run(Future<void> Function() task) async {
    setState(() {
      _loading = true;
    });
    try {
      await task();
    } catch (error) {
      _setStatus(error.toString());
    } finally {
      if (mounted) {
        setState(() {
          _loading = false;
        });
      }
    }
  }

  void _applyDestination(PlaceSearchResult place) {
    setState(() {
      _destinationPlace = place;
      _destinationController.text = place.name;
      _destinationResults = [];
      _routeResponse = null;
      _selectedRoute = null;
    });
    _mapController.move(LatLng(place.lat, place.lon), 16);
  }

  void _selectRoute(RouteOption route) {
    setState(() {
      _selectedRoute = route;
    });
    _fitSelectedRoute();
  }

  void _selectAdjacentRoute(int direction) {
    final routes = _routeResponse?.routes ?? [];
    if (routes.isEmpty) {
      return;
    }
    final currentIndex = routes.indexOf(_selectedRoute ?? routes.first);
    final safeIndex = currentIndex < 0 ? 0 : currentIndex;
    final nextIndex = (safeIndex + direction + routes.length) % routes.length;
    _selectRoute(routes[nextIndex]);
  }

  void _fitSelectedRoute() {
    final route = _selectedRoute;
    if (route == null || route.points.isEmpty) {
      return;
    }
    final bounds = LatLngBounds.fromPoints(
      route.points.map((point) => LatLng(point.lat, point.lon)).toList(),
    );
    _mapController.fitCamera(
      CameraFit.bounds(bounds: bounds, padding: const EdgeInsets.all(48)),
    );
  }

  void _showCctvDetail(CctvPoint cctv) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      width: 34,
                      height: 34,
                      decoration: const BoxDecoration(
                        color: Color(0xFF2563EB),
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(Icons.videocam, color: Colors.white, size: 20),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'CCTV 위치',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                _CctvDetailRow(label: '구군', value: cctv.district.isEmpty ? '-' : cctv.district),
                _CctvDetailRow(label: '주소', value: cctv.address.isEmpty ? '-' : cctv.address),
                _CctvDetailRow(label: '목적', value: cctv.purpose.isEmpty ? '-' : cctv.purpose),
                _CctvDetailRow(label: '수량', value: '${cctv.qty}대'),
                _CctvDetailRow(
                  label: '좌표',
                  value: '${cctv.lat.toStringAsFixed(6)}, ${cctv.lon.toStringAsFixed(6)}',
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  void _setStatus(String status) {
    if (!mounted) {
      return;
    }
    setState(() {
      _status = status;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          _buildMap(),
          SafeArea(
            child: Column(
              children: [
                _DestinationPanel(
                  loading: _loading,
                  apiBaseUrl: _apiBaseUrl,
                  destinationController: _destinationController,
                  destinationPlace: _destinationPlace,
                  destinationResults: _destinationResults,
                  onSearchDestination: _searchDestination,
                  onSelectDestination: _applyDestination,
                  onRequestRoute: _requestRoute,
                  onOpenServerSettings: _openServerSettings,
                ),
                const Spacer(),
                if (_routeResponse != null)
                  _RouteCandidatePanel(
                    response: _routeResponse!,
                    selectedRoute: _selectedRoute,
                    routes: _routeResponse!.routes,
                    onSelectRoute: _selectRoute,
                    onPreviousRoute: () => _selectAdjacentRoute(-1),
                    onNextRoute: () => _selectAdjacentRoute(1),
                  ),
                _StatusBar(status: _status, loading: _loading),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMap() {
    return FlutterMap(
      mapController: _mapController,
      options: const MapOptions(
        initialCenter: LatLng(35.1535, 129.0327),
        initialZoom: 15,
      ),
      children: [
        TileLayer(
          urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
          userAgentPackageName: 'com.example.shade_route_mobile',
        ),
        PolylineLayer(polylines: _routePolylines()),
        MarkerLayer(markers: _markers()),
      ],
    );
  }

  List<Polyline> _routePolylines() {
    final route = _selectedRoute;
    if (route == null) {
      return [];
    }

    if (route.isSafeRoute && route.segmentScores.isEmpty) {
      return [
        Polyline(
          points: route.points.map((point) => LatLng(point.lat, point.lon)).toList(),
          strokeWidth: 7,
          color: const Color(0xFF16A34A),
        ),
      ];
    }

    if (route.segmentScores.isEmpty) {
      return [
        Polyline(
          points: route.points.map((point) => LatLng(point.lat, point.lon)).toList(),
          strokeWidth: 7,
          color: route.isSafeRoute
              ? const Color(0xFF16A34A)
              : const Color(0xFFFF8A00),
        ),
      ];
    }

    return route.segmentScores.map((segment) {
      return Polyline(
        points: [
          LatLng(segment.start.lat, segment.start.lon),
          LatLng(segment.end.lat, segment.end.lon),
        ],
        strokeWidth: route.isSafeRoute ? 9 : 8,
        color: route.isSafeRoute
            ? const Color(0xFF16A34A)
            : segment.isShade
                ? const Color(0xFF7A7D82)
                : const Color(0xFFFF8A00),
      );
    }).toList();
  }

  List<Marker> _markers() {
    final markers = <Marker>[];
    final selectedRoute = _selectedRoute;

    if (selectedRoute != null) {
      for (final cctv in selectedRoute.nearbyCctvs.take(_cctvMarkerLimit)) {
        markers.add(
          Marker(
            point: LatLng(cctv.lat, cctv.lon),
            width: 38,
            height: 38,
            child: GestureDetector(
              onTap: () => _showCctvDetail(cctv),
              child: Tooltip(
                message: '${cctv.district}\n${cctv.address}\n${cctv.purpose}\n수량 ${cctv.qty}대',
                child: Container(
                  decoration: BoxDecoration(
                    color: const Color(0xFF2563EB).withValues(alpha: 0.88),
                    shape: BoxShape.circle,
                    border: Border.all(color: Colors.white, width: 2),
                    boxShadow: const [
                      BoxShadow(blurRadius: 8, color: Color(0x55000000)),
                    ],
                  ),
                  child: const Icon(Icons.videocam, color: Colors.white, size: 19),
                ),
              ),
            ),
          ),
        );
      }
    }

    final current = _currentPosition;
    if (current != null) {
      markers.add(
        Marker(
          point: LatLng(current.latitude, current.longitude),
          width: 42,
          height: 42,
          child: const Icon(Icons.my_location, color: Color(0xFF1F6F78), size: 34),
        ),
      );
    }

    final destination = _destinationPlace;
    if (destination != null) {
      markers.add(
        Marker(
          point: LatLng(destination.lat, destination.lon),
          width: 42,
          height: 42,
          child: const Icon(Icons.place, color: Colors.redAccent, size: 34),
        ),
      );
    }

    return markers;
  }
}

class _CctvDetailRow extends StatelessWidget {
  const _CctvDetailRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 54,
            child: Text(
              label,
              style: const TextStyle(color: Colors.black54),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(child: Text(value)),
        ],
      ),
    );
  }
}

class _DestinationPanel extends StatelessWidget {
  const _DestinationPanel({
    required this.loading,
    required this.apiBaseUrl,
    required this.destinationController,
    required this.destinationPlace,
    required this.destinationResults,
    required this.onSearchDestination,
    required this.onSelectDestination,
    required this.onRequestRoute,
    required this.onOpenServerSettings,
  });

  final bool loading;
  final String? apiBaseUrl;
  final TextEditingController destinationController;
  final PlaceSearchResult? destinationPlace;
  final List<PlaceSearchResult> destinationResults;
  final VoidCallback onSearchDestination;
  final ValueChanged<PlaceSearchResult> onSelectDestination;
  final VoidCallback onRequestRoute;
  final VoidCallback onOpenServerSettings;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.all(12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.94),
        borderRadius: BorderRadius.circular(8),
        boxShadow: const [
          BoxShadow(blurRadius: 18, color: Color(0x22000000)),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Icon(
                apiBaseUrl == null ? Icons.cloud_off : Icons.cloud_done,
                size: 18,
                color: apiBaseUrl == null ? Colors.redAccent : Colors.green,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  apiBaseUrl == null ? '서버 연결 중...' : '서버 연결됨: $apiBaseUrl',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              IconButton(
                onPressed: onOpenServerSettings,
                icon: const Icon(Icons.settings),
                tooltip: '서버 주소 설정',
              ),
            ],
          ),
          const SizedBox(height: 8),
          _PlaceInput(
            label: '목적지',
            controller: destinationController,
            results: destinationResults,
            onSearch: onSearchDestination,
            onSelect: onSelectDestination,
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: loading ? null : onRequestRoute,
              icon: const Icon(Icons.route),
              label: Text(destinationPlace == null ? '목적지를 먼저 선택해주세요' : '경로 찾기'),
            ),
          ),
        ],
      ),
    );
  }
}

class _PlaceInput extends StatelessWidget {
  const _PlaceInput({
    required this.label,
    required this.controller,
    required this.results,
    required this.onSearch,
    required this.onSelect,
  });

  final String label;
  final TextEditingController controller;
  final List<PlaceSearchResult> results;
  final VoidCallback onSearch;
  final ValueChanged<PlaceSearchResult> onSelect;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: controller,
                textInputAction: TextInputAction.search,
                onSubmitted: (_) => onSearch(),
                decoration: InputDecoration(labelText: label, isDense: true),
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filledTonal(
              onPressed: onSearch,
              icon: const Icon(Icons.search),
            ),
          ],
        ),
        ...results.take(3).map((place) {
          return Material(
            color: Colors.transparent,
            child: ListTile(
              dense: true,
              title: Text(place.name, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(place.address, maxLines: 1, overflow: TextOverflow.ellipsis),
              onTap: () => onSelect(place),
            ),
          );
        }),
      ],
    );
  }
}

class _RouteCandidatePanel extends StatelessWidget {
  const _RouteCandidatePanel({
    required this.response,
    required this.selectedRoute,
    required this.routes,
    required this.onSelectRoute,
    required this.onPreviousRoute,
    required this.onNextRoute,
  });

  final RouteResponse response;
  final RouteOption? selectedRoute;
  final List<RouteOption> routes;
  final ValueChanged<RouteOption> onSelectRoute;
  final VoidCallback onPreviousRoute;
  final VoidCallback onNextRoute;

  @override
  Widget build(BuildContext context) {
    final selected = selectedRoute ?? (routes.isEmpty ? null : routes.first);
    final selectedIndex = selected == null ? -1 : routes.indexOf(selected);
    final canNavigate = routes.length > 1;
    return Container(
      margin: const EdgeInsets.fromLTRB(12, 0, 12, 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.95),
        borderRadius: BorderRadius.circular(8),
        boxShadow: const [
          BoxShadow(blurRadius: 18, color: Color(0x22000000)),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Text('추천 경로', style: Theme.of(context).textTheme.titleMedium),
              const Spacer(),
              if (selectedIndex >= 0) Text('${selectedIndex + 1}/${routes.length}'),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              IconButton.filledTonal(
                onPressed: canNavigate ? onPreviousRoute : null,
                icon: const Icon(Icons.chevron_left),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: selected == null
                    ? const Text('추천 경로가 없습니다')
                    : _RouteCandidateTile(
                        route: selected,
                        selected: true,
                        onTap: () => onSelectRoute(selected),
                      ),
              ),
              const SizedBox(width: 8),
              IconButton.filledTonal(
                onPressed: canNavigate ? onNextRoute : null,
                icon: const Icon(Icons.chevron_right),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 14,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              const _Legend(color: Color(0xFFFF8A00), label: '햇빛'),
              const _Legend(color: Color(0xFF7A7D82), label: '그늘'),
              const _Legend(color: Color(0xFF16A34A), label: '안심경로'),
              const _Legend(color: Color(0xFF2563EB), label: 'CCTV'),
              Text(_weatherLabel(response)),
            ],
          ),
        ],
      ),
    );
  }

  String _weatherLabel(RouteResponse response) {
    final temp = response.temperatureCelsius?.toStringAsFixed(1) ?? '-';
    final humidity = response.humidity?.round().toString() ?? '-';
    final rain = response.raining == true ? '비' : '맑음';
    return '$temp C / $humidity% / $rain';
  }
}

class _RouteCandidateTile extends StatelessWidget {
  const _RouteCandidateTile({
    required this.route,
    required this.selected,
    required this.onTap,
  });

  final RouteOption route;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      elevation: selected ? 2 : 0,
      color: selected ? const Color(0xFFE4F4F6) : const Color(0xFFF7F7F2),
      child: ListTile(
        dense: true,
        leading: Icon(_routeIcon(route.routeType)),
        title: Text(_routeTypeLabel(route.routeType)),
        subtitle: Text(_routeDetailLabel(route)),
        trailing: Text(_trailingLabel(route)),
        onTap: onTap,
      ),
    );
  }

  IconData _routeIcon(String routeType) {
    switch (routeType.toUpperCase()) {
      case 'SAFE':
        return Icons.shield;
      case 'SHORTEST':
        return Icons.directions_walk;
      case 'SHADE':
        return Icons.park;
      case 'BALANCED':
        return Icons.route;
      default:
        return selected ? Icons.check_circle : Icons.radio_button_unchecked;
    }
  }

  String _routeTypeLabel(String routeType) {
    switch (routeType.toUpperCase()) {
      case 'SHORTEST':
        return '최단 경로';
      case 'SHADE':
        return '그늘 우선';
      case 'BALANCED':
        return '균형 경로';
      case 'SAFE':
        return '안심 경로';
      default:
        return routeType;
    }
  }

  String _routeDetailLabel(RouteOption route) {
    if (route.isSafeRoute) {
      final safety = ((route.safetyScore ?? 0) * 100).round();
      final cctvCount = route.cctvCount ?? route.nearbyCctvs.length;
      return '${route.distance} m  |  CCTV 접근성 $safety%  |  CCTV $cctvCount개';
    }

    return '${route.distance} m  |  그늘 ${(route.shadeScore * 100).round()}%';
  }

  String _trailingLabel(RouteOption route) {
    if (route.isSafeRoute) {
      return 'CCTV ${route.cctvCount ?? route.nearbyCctvs.length}';
    }
    return '점수 ${route.totalScore.toStringAsFixed(3)}';
  }
}

class _Legend extends StatelessWidget {
  const _Legend({required this.color, required this.label});

  final Color color;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(width: 18, height: 6, color: color),
        const SizedBox(width: 5),
        Text(label),
      ],
    );
  }
}

class _StatusBar extends StatelessWidget {
  const _StatusBar({required this.status, required this.loading});

  final String status;
  final bool loading;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
      color: Colors.white.withValues(alpha: 0.92),
      child: Row(
        children: [
          if (loading)
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          if (loading) const SizedBox(width: 8),
          Expanded(child: Text(status, maxLines: 1, overflow: TextOverflow.ellipsis)),
        ],
      ),
    );
  }
}
