# record_video_data_sync

A Flutter video recorder that knows **exactly when its first frame was
captured**, so a video can be lined up with sensor data (accelerometer, GPS,
timers) to a few milliseconds.

Ordinary camera plugins report "recording started" when their call returns --
on a Galaxy S24 that is 140-470 ms after the camera really began filming, and
it varies from one recording to the next. This plugin keeps the camera
sensor's capture timestamp on every frame, all the way into the MP4, and
converts it to the phone's wall clock.

Built for MAX2000 rowing analytics (video + boat acceleration).

## Usage

```dart
final rec = await PreciseRecorder.open(width: 1280, height: 720, fps: 30);
// show rec.preview() in your widget tree
await rec.start('/path/clip.mp4', rotationDegrees: 90);
// ...
final result = await rec.stop();
// result.firstFrameEpochUs: phone time (us since epoch) of video time 0
```

Ask for the CAMERA permission before `open`.

## Status

* Android: video + sound.
* iOS: planned.
