
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_mp_pose_landmarker_example/screen.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      // showPerformanceOverlay: true,
      title: 'Popup Example',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: PoseLandmarkerView(),
    );
  }
}




