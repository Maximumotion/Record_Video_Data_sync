/// Video recorder that reports the exact phone-clock time of the first
/// recorded frame, so the video can be lined up with sensor data to about a
/// millisecond (MAX2000 rowing analytics).
library;

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// What a finished recording reports.
class RecordingResult {
  RecordingResult._(Map<Object?, Object?> m)
      : path = m['path'] as String,
        firstFrameEpochUs = (m['firstFrameEpochUs'] as num).toInt(),
        lastFrameEpochUs = (m['lastFrameEpochUs'] as num).toInt(),
        frameCount = (m['frameCount'] as num).toInt(),
        timestampSource = m['timestampSource'] as String,
        width = (m['width'] as num).toInt(),
        height = (m['height'] as num).toInt(),
        fps = (m['fps'] as num).toInt(),
        hasAudio = m['hasAudio'] == true;

  /// MP4 file.
  final String path;

  /// Phone wall-clock time (microseconds since epoch) at which the file's
  /// first frame was exposed -- video time 0 happened at this moment.
  final int firstFrameEpochUs;

  /// Same, for the last frame.
  final int lastFrameEpochUs;
  final int frameCount;

  /// The camera clock the timestamps came from: "realtime" or "monotonic".
  final String timestampSource;
  final int width;
  final int height;
  final int fps;

  /// Sound was recorded (asked for, microphone allowed and working).
  final bool hasAudio;

  @override
  String toString() =>
      'RecordingResult($path, first=$firstFrameEpochUs, frames=$frameCount, $timestampSource, ${width}x$height@$fps)';
}

/// Opened camera: show [preview], then [start] / [stop].
class PreciseRecorder {
  PreciseRecorder._(this.textureId, this.width, this.height, this.sensorOrientation);

  static const MethodChannel _ch = MethodChannel('record_video_data_sync');

  final int textureId;
  final int width;
  final int height;
  final int sensorOrientation;

  /// Opens the back camera at [width]x[height] (16:9) and [fps] frames per
  /// second. Ask for the CAMERA permission first.
  static Future<PreciseRecorder> open({int width = 1280, int height = 720, int fps = 30}) async {
    final m = await _ch.invokeMapMethod<String, Object?>('open', {'width': width, 'height': height, 'fps': fps});
    return PreciseRecorder._(
      (m!['textureId'] as num).toInt(),
      (m['width'] as num).toInt(),
      (m['height'] as num).toInt(),
      (m['sensorOrientation'] as num).toInt(),
    );
  }

  /// Camera preview (landscape buffer, [width]:[height]).
  Widget preview() => AspectRatio(aspectRatio: width / height, child: Texture(textureId: textureId));

  /// [rotationDegrees]: how the phone is held -- 0 portrait, 90 landscape
  /// (top to the left), 180, 270 landscape (top to the right).
  /// [withAudio]: also record sound (ask for RECORD_AUDIO first). If the
  /// microphone can't be used, the video is recorded without sound --
  /// [RecordingResult.hasAudio] says which.
  Future<void> start(String path, {int rotationDegrees = 90, bool withAudio = false}) =>
      _ch.invokeMethod('start', {'path': path, 'rotationDegrees': rotationDegrees, 'withAudio': withAudio});

  /// Finishes the file. Null if no frame was recorded.
  Future<RecordingResult?> stop() async {
    final m = await _ch.invokeMethod<Map<Object?, Object?>>('stop');
    return m == null ? null : RecordingResult._(m);
  }

  Future<void> setZoom(double ratio) => _ch.invokeMethod('setZoom', {'ratio': ratio});

  Future<List<double>> zoomRange() async =>
      ((await _ch.invokeListMethod<double>('zoomRange')) ?? const [1.0, 1.0]);

  Future<void> close() => _ch.invokeMethod('close');

  /// How the screen is turned right now: 0 portrait, 1 = 90 (landscape,
  /// top to the left), 2 = 180, 3 = 270. Android only (0 elsewhere).
  static Future<int> displayRotation() async {
    try {
      return (await _ch.invokeMethod<int>('displayRotation')) ?? 0;
    } catch (_) {
      return 0;
    }
  }

  /// Quarter turns that show the camera image upright on a screen turned
  /// by [displayRotation] (use with RotatedBox around [preview]).
  int previewQuarterTurns(int displayRotation) =>
      previewQuarterTurnsFor(sensorOrientation, displayRotation);
}

/// Quarter turns that show a camera with [sensorOrientation] upright on a
/// screen turned by [displayRotation] (0..3). See
/// [PreciseRecorder.previewQuarterTurns].
int previewQuarterTurnsFor(int sensorOrientation, int displayRotation) =>
    ((sensorOrientation - displayRotation * 90) % 360 + 360) % 360 ~/ 90;
