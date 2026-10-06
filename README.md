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
// Show it: on Android the texture is turned to the screen, on iOS it
// already arrives upright.
final rot = await PreciseRecorder.displayRotation();
final size = rec.previewSizeFor(rot);
// SizedBox(width: size.width, height: size.height,
//   child: RotatedBox(quarterTurns: rec.previewQuarterTurns(rot), child: rec.previewTexture()))
await rec.start('/path/clip.mp4', rotationDegrees: rot * 90, withAudio: true);
// ...
final result = await rec.stop();
// result.captureCenterEpochUs: when the CENTRE of the first frame was really
// captured (phone clock, us since epoch) -- use this to place sensor data.
```

Android: ask for CAMERA (and RECORD_AUDIO) before `open`. iOS asks by itself
(add NSCameraUsageDescription / NSMicrophoneUsageDescription).

## How exact

A frame isn't one instant: the sensor collects light for a while (the
exposure: ~1 ms in sunshine, 17-33 ms indoors) and reads the picture out row
by row (rolling shutter, ~9-14 ms). Android stamps a frame at the start of the
top row's exposure, iOS at its end. `captureCenterEpochUs` converts both to
the same moment -- the middle of the exposure at the middle row -- from each
recording's own exposure time, so it holds in any light.

Measured with an LED flashed by an external device (microsecond clock) on a
Galaxy S24 and an iPhone, indoors and in sunshine: within about 2 ms, and the
two platforms agree within about 2 ms (one 30 fps frame is 33 ms).

## Status

* Android (Camera2 + MediaCodec): video + sound.
* iOS (AVFoundation): video + sound.
