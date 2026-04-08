
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:flutter_mp_pose_landmarker_example/main.dart'
    as app; // import your main.dart

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('Pose Landmarker Integration Test', () {
    testWidgets('Launch app, switch camera, toggle logging', (tester) async {
      // Launch the app
      app.main();
      await tester.pumpAndSettle(
          const Duration(seconds: 5)); // Time to accept the Permission Request
      await tester.pumpAndSettle();

      // Check initial widgets
      expect(find.byType(app.NativeCameraPreview), findsOneWidget);
      expect(find.byIcon(Icons.cameraswitch), findsOneWidget);
      expect(find.byIcon(Icons.pause), findsOneWidget);
      expect(find.byIcon(Icons.bug_report), findsOneWidget);

      // Tap switch camera button
      await tester.tap(find.byIcon(Icons.cameraswitch));
      await tester.pumpAndSettle();

      // Tap logging toggle
      await tester.tap(find.byIcon(Icons.bug_report));
      await tester.pumpAndSettle();

      // Tap pause/resume detection
      await tester.tap(find.byIcon(Icons.pause));
      await tester.pumpAndSettle();

      // Type new detection confidence
      await tester.enterText(find.byType(TextField).first, '0.8');
      await tester.pumpAndSettle();

      // Tap Apply button
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();
    });
  });
}
