
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_mp_pose_landmarker/flutter_mp_pose_landmarker.dart';

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


class PoseLandmarkerView extends StatefulWidget {
  @override
  State<PoseLandmarkerView> createState() => _PoseLandmarkerViewState();
}

class _PoseLandmarkerViewState extends State<PoseLandmarkerView> {
  List<PoseLandmarkPoint> _landmarks = [];
  late StreamSubscription<PoseLandMarker> _poseSubscription;

  @override
  void initState() {
    super.initState();
    PoseLandmarker.setConfig(delegate: 0 , model: 1);
    _poseSubscription = PoseLandmarker.poseLandmarkStream.listen((pose) {
      setState(() {
        _landmarks = pose.landmarks;
      });
    });
  }

  @override
  void dispose() {
    _poseSubscription.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(bottomNavigationBar: FloatingActionButton(
  child: Icon(Icons.cameraswitch),
  onPressed: () {
    PoseLandmarker.switchCamera();
  },
),
      body: Stack(
        fit: StackFit.expand,
        children: [
          NativeCameraPreview(),
          // Positioned square in top-right
          Positioned(
            top: 16,
            right: 16,
            width: 150,
            height: 150,
            child: Container(
              decoration: BoxDecoration(
                border: Border.all(color: Colors.white, width: 2),
                color: Colors.black.withOpacity(0.3),
              ),
              child: CustomPaint(
                painter: LandmarkPainter(_landmarks),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class NativeCameraPreview extends StatelessWidget {
  const NativeCameraPreview({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return const SizedBox.expand(
      child: AndroidView(
        viewType: 'camera_preview_view',
        layoutDirection: TextDirection.ltr,
      ),
    );
  }
}

class LandmarkPainter extends CustomPainter {
  final List<PoseLandmarkPoint> landmarks;
  LandmarkPainter(this.landmarks);

  static const List<List<int>> connections = [
    [0, 1], [1, 2], [2, 3], [3, 7],
    [0, 4], [4, 5], [5, 6], [6, 8],
    [9, 10], [11, 12],
    [11, 13], [13, 15], [12, 14], [14, 16],
    [11, 23], [12, 24], [23, 24],
    [23, 25], [25, 27], [24, 26], [26, 28],
    [27, 31], [28, 32],
    [15, 17], [16, 18], [17, 19], [18, 20],
    [19, 21], [20, 22],
  ];

  @override
  void paint(Canvas canvas, Size size) {
    final pointPaint = Paint()
      ..color = Colors.red
      ..style = PaintingStyle.fill;
    final linePaint = Paint()
      ..color = Colors.blue
      ..strokeWidth = 2;

    // Draw connections
    for (var c in connections) {
      if (c[0] < landmarks.length && c[1] < landmarks.length) {
        final a = landmarks[c[0]];
        final b = landmarks[c[1]];
        canvas.drawLine(
          Offset(a.x * size.width, a.y * size.height),
          Offset(b.x * size.width, b.y * size.height),
          linePaint,
        );
      }
    }
    // Draw points
    for (var lm in landmarks) {
      canvas.drawCircle(
        Offset(lm.x * size.width, lm.y * size.height),
        4,
        pointPaint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant LandmarkPainter old) =>
      old.landmarks != landmarks;
}


