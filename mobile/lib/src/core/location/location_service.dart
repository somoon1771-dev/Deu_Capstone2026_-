import 'dart:async';

import 'package:geolocator/geolocator.dart';

class LocationService {
  Future<Position?> currentPosition() async {
    final allowed = await _ensurePermission();
    if (!allowed) {
      return null;
    }
    return Geolocator.getCurrentPosition(
      locationSettings: const LocationSettings(
        accuracy: LocationAccuracy.best,
        timeLimit: Duration(seconds: 8),
      ),
    );
  }

  Stream<Position> positionStream() async* {
    final allowed = await _ensurePermission();
    if (!allowed) {
      return;
    }

    final settings = AndroidSettings(
      accuracy: LocationAccuracy.best,
      distanceFilter: 2,
      intervalDuration: Duration(seconds: 2),
    );
    yield* Geolocator.getPositionStream(locationSettings: settings);
  }

  Future<bool> _ensurePermission() async {
    final serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      return false;
    }

    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    return permission == LocationPermission.always ||
        permission == LocationPermission.whileInUse;
  }
}
