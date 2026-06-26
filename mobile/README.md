# Shade Route Mobile

Flutter mobile app scaffold for the weather and shade-aware pedestrian navigation system.

## Current behavior

- Searches start and destination places through the backend VWorld place API.
- Requests recommended walking routes from the Spring Boot backend.
- Displays only route polylines on the map.
- Colors route segments by shade state:
  - Orange: sunny segment
  - Gray: shaded segment
- Tracks GPS position with a 5 meter distance filter.
- Does not auto-reroute when the user leaves the route.
- Recalculates only when the user searches and requests a new route.

## Backend URL

For Android emulator, use:

```text
http://10.0.2.2:8080
```

For a physical Android phone, use the PC LAN IP:

```text
http://YOUR_PC_IP:8080
```

The phone and backend PC must be on the same Wi-Fi network unless the server is deployed publicly.

## Flutter setup

Flutter is not installed in the current workspace environment, so platform folders must be generated locally:

```bash
cd mobile
flutter create .
flutter pub get
flutter run
```

Android location permission is needed. If `flutter create .` overwrites files, keep the `lib/`, `pubspec.yaml`, and `analysis_options.yaml` contents from this scaffold.
