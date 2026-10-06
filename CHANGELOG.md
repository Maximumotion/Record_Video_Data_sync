## 0.0.1-dev.2

* Camera timing of the first frame in `RecordingResult`: `exposureUs`,
  `rollingShutterSkewUs` (Android), `sensorMinusFrameUs` (Android check), to
  place data at the middle of the exposure on both platforms (iOS exposure
  from the frame's EXIF attachment).

## 0.0.1-dev.1

* Android: Camera2 + MediaCodec recorder (H.264, 30 fps, 720p/1080p, zoom,
  landscape orientation hint) that returns the exact phone-clock time of the
  first recorded frame (`RecordingResult.firstFrameEpochUs`).
* Bench-verified on a Galaxy S24 with LED flashes logged by an external
  device: flashes land -9..+12 ms from the recorder's timeline (the camera's
  own frame/exposure window).
* Android: sound (AAC 48 kHz mono) on the same MONOTONIC clock as the video,
  via AudioRecord.getTimestamp; `start(withAudio: true)`, falls back to video
  only if the microphone can't be used (`RecordingResult.hasAudio`).
  Clap test: sound lands where the hands stop, within one video frame.
* iOS: AVCaptureSession + AVAssetWriter, video + sound on the capture
  session clock; asks for camera/microphone itself; frames follow the screen
  orientation (`uprightBuffers`). Compile-checked in CI, not yet tested on a
  device (this pre-release is for that test).
* `displayRotation()`, `previewQuarterTurns()`, `previewSizeFor()`,
  `previewTexture()` for apps whose screen rotates.
