import 'package:flutter/material.dart';

import 'src/features/map/map_screen.dart';

void main() {
  runApp(const ShadeRouteApp());
}

class ShadeRouteApp extends StatelessWidget {
  const ShadeRouteApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '그늘 경로',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF1F6F78),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      home: const MapScreen(),
    );
  }
}
