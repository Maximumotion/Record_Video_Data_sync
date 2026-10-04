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
* iOS: not implemented yet.
