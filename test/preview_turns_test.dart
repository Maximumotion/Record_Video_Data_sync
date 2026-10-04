import 'package:flutter_test/flutter_test.dart';
import 'package:record_video_data_sync/record_video_data_sync.dart';

void main() {
  test('preview quarter turns for a 90-degree back camera', () {
    // Galaxy S24 back camera: sensor 90. Screen rotation 0..3 (portrait,
    // landscape top-left, upside down, landscape top-right).
    final turns = [0, 1, 2, 3].map((r) => previewQuarterTurnsFor(90, r)).toList();
    expect(turns, [1, 0, 3, 2]);
  });
  test('270-degree sensors (some phones) turn the other way', () {
    expect([0, 1, 2, 3].map((r) => previewQuarterTurnsFor(270, r)).toList(), [3, 2, 1, 0]);
  });
}
