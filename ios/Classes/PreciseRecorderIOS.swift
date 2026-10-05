import AVFoundation
import CoreMedia
import CoreVideo
import Flutter
import Foundation
import QuartzCore
import UIKit

/// iOS side of record_video_data_sync: AVCaptureSession -> AVAssetWriter.
///
/// Every video frame and audio buffer from one capture session carries a
/// presentation time on the session's clock (the host clock). The writer's
/// session starts exactly at the first written video frame, so video time 0
/// is that frame, and its time is converted to the wall clock -- the same
/// contract as the Android side (`firstFrameEpochUs`).
///
/// Orientation: the capture connection turns the frames to match the screen
/// (videoOrientation follows the interface orientation while not recording),
/// so preview and file are upright as delivered -- unlike Android, the app
/// never turns the texture ("uprightBuffers": true).
final class PreciseRecorderIOS: NSObject, FlutterTexture,
    AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {

    private let textures: FlutterTextureRegistry
    private(set) var textureId: Int64 = -1

    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "record_video_data_sync.capture")
    private var device: AVCaptureDevice?
    private let videoOut = AVCaptureVideoDataOutput()
    private let audioOut = AVCaptureAudioDataOutput()
    private var audioInputAdded = false

    // Latest preview frame for Flutter.
    private var latest: CVPixelBuffer?
    private let latestLock = NSLock()

    // Recording
    private var writer: AVAssetWriter?
    private var videoIn: AVAssetWriterInput?
    private var audioIn: AVAssetWriterInput?
    private var recording = false
    private var sessionStarted = false
    private var firstPts = CMTime.invalid
    private var lastPts = CMTime.invalid
    private var frameCount = 0
    private var audioCount = 0
    private var outputURL: URL?
    private var width = 1280
    private var height = 720
    private var fps: Int32 = 30

    init(textures: FlutterTextureRegistry) {
        self.textures = textures
        super.init()
    }

    // MARK: FlutterTexture
    func copyPixelBuffer() -> Unmanaged<CVPixelBuffer>? {
        latestLock.lock(); defer { latestLock.unlock() }
        guard let pb = latest else { return nil }
        return Unmanaged.passRetained(pb)
    }

    // MARK: Open
    func open(width: Int, height: Int, fps: Int, completion: @escaping (Result<[String: Any], Error>) -> Void) {
        self.width = width
        self.height = height
        self.fps = Int32(fps)
        // Ask for camera (and microphone) access here, so apps don't need
        // a permissions package on iOS.
        AVCaptureDevice.requestAccess(for: .video) { camOk in
            guard camOk else {
                DispatchQueue.main.async {
                    completion(.failure(NSError(domain: "record_video_data_sync", code: 2,
                        userInfo: [NSLocalizedDescriptionKey: "camera permission denied"])))
                }
                return
            }
            AVCaptureDevice.requestAccess(for: .audio) { micOk in
                self.queue.async { self.openOnQueue(micOk: micOk, completion: completion) }
            }
        }
    }

    private func openOnQueue(micOk: Bool, completion: @escaping (Result<[String: Any], Error>) -> Void) {
            do {
                try self.configure(withAudio: micOk)
                self.session.startRunning()
                let id = self.textures.register(self)
                self.textureId = id
                DispatchQueue.main.async {
                    completion(.success([
                        "textureId": id,
                        "width": self.width,
                        "height": self.height,
                        "sensorOrientation": 90,
                        "uprightBuffers": true,
                    ]))
                }
            } catch {
                DispatchQueue.main.async { completion(.failure(error)) }
            }
    }

    private func configure(withAudio: Bool) throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = (width >= 1920) ? .hd1920x1080 : .hd1280x720
        guard let cam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw NSError(domain: "record_video_data_sync", code: 1, userInfo: [NSLocalizedDescriptionKey: "no back camera"])
        }
        device = cam
        let input = try AVCaptureDeviceInput(device: cam)
        if session.canAddInput(input) { session.addInput(input) }
        try cam.lockForConfiguration()
        let frame = CMTime(value: 1, timescale: fps)
        cam.activeVideoMinFrameDuration = frame
        cam.activeVideoMaxFrameDuration = frame
        if cam.isFocusModeSupported(.continuousAutoFocus) { cam.focusMode = .continuousAutoFocus }
        cam.unlockForConfiguration()

        videoOut.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOut.alwaysDiscardsLateVideoFrames = true
        videoOut.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(videoOut) { session.addOutput(videoOut) }
        if let conn = videoOut.connection(with: .video), conn.isVideoOrientationSupported {
            conn.videoOrientation = .landscapeRight // until displayRotation() reports the screen
        }

        if withAudio, let mic = AVCaptureDevice.default(for: .audio),
           let micIn = try? AVCaptureDeviceInput(device: mic), session.canAddInput(micIn) {
            session.addInput(micIn)
            audioOut.setSampleBufferDelegate(self, queue: queue)
            if session.canAddOutput(audioOut) {
                session.addOutput(audioOut)
                audioInputAdded = true
            }
        }
    }

    // MARK: Orientation

    /// The screen's orientation as 0 portrait, 1 landscape (home side right),
    /// 2 upside down, 3 landscape (home side left) -- even = portrait, like
    /// Android's rotation. While not recording, the camera frames are turned
    /// to match it, so the preview and the next recording are upright.
    /// Call on the main thread (method channel calls are).
    func displayRotation() -> Int {
        var io: UIInterfaceOrientation = .landscapeRight
        if #available(iOS 13.0, *) {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            if let ws = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first {
                io = ws.interfaceOrientation
            }
        }
        let video: AVCaptureVideoOrientation
        let rotation: Int
        switch io {
        case .portrait: video = .portrait; rotation = 0
        case .portraitUpsideDown: video = .portraitUpsideDown; rotation = 2
        case .landscapeLeft: video = .landscapeLeft; rotation = 3
        default: video = .landscapeRight; rotation = 1
        }
        queue.async {
            // Never turn the frames mid-recording: the file keeps one size.
            guard !self.recording, let conn = self.videoOut.connection(with: .video),
                  conn.isVideoOrientationSupported, conn.videoOrientation != video else { return }
            conn.videoOrientation = video
        }
        return rotation
    }

    // MARK: Record
    /// [rotationDegrees] is not needed on iOS: the frames already arrive
    /// upright for the screen orientation at the moment Record is pressed.
    func start(path: String, rotationDegrees: Int, withAudio: Bool) throws {
        let url = URL(fileURLWithPath: path)
        try? FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let w = try AVAssetWriter(outputURL: url, fileType: .mp4)
        // Frames are turned to the screen: portrait frames are tall.
        var portrait = false
        queue.sync {
            if let o = self.videoOut.connection(with: .video)?.videoOrientation {
                portrait = o == .portrait || o == .portraitUpsideDown
            }
        }
        let vSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: portrait ? height : width,
            AVVideoHeightKey: portrait ? width : height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: width >= 1920 ? 16_000_000 : 8_000_000,
                AVVideoExpectedSourceFrameRateKey: Int(fps),
                AVVideoMaxKeyFrameIntervalKey: Int(fps),
            ],
        ]
        let v = AVAssetWriterInput(mediaType: .video, outputSettings: vSettings)
        v.expectsMediaDataInRealTime = true
        if w.canAdd(v) { w.add(v) }
        var a: AVAssetWriterInput? = nil
        if withAudio && audioInputAdded {
            let aSettings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey: 1,
                AVSampleRateKey: 48_000,
                AVEncoderBitRateKey: 128_000,
            ]
            let ai = AVAssetWriterInput(mediaType: .audio, outputSettings: aSettings)
            ai.expectsMediaDataInRealTime = true
            if w.canAdd(ai) { w.add(ai); a = ai }
        }
        queue.sync {
            self.writer = w
            self.videoIn = v
            self.audioIn = a
            self.outputURL = url
            self.sessionStarted = false
            self.firstPts = .invalid
            self.lastPts = .invalid
            self.frameCount = 0
            self.audioCount = 0
            w.startWriting()
            self.recording = true
        }
    }

    func stop(completion: @escaping ([String: Any]?) -> Void) {
        queue.async {
            guard self.recording, let w = self.writer else { DispatchQueue.main.async { completion(nil) }; return }
            self.recording = false
            self.videoIn?.markAsFinished()
            self.audioIn?.markAsFinished()
            let first = self.firstPts, last = self.lastPts
            let frames = self.frameCount, audio = self.audioCount
            let url = self.outputURL
            w.finishWriting {
                guard first.isValid, let url = url else { DispatchQueue.main.async { completion(nil) }; return }
                let result: [String: Any] = [
                    "path": url.path,
                    "firstFrameEpochUs": self.epochUs(first),
                    "lastFrameEpochUs": self.epochUs(last),
                    "frameCount": frames,
                    "timestampSource": "host",
                    "width": self.width,
                    "height": self.height,
                    "fps": Int(self.fps),
                    "hasAudio": audio > 0,
                ]
                DispatchQueue.main.async { completion(result) }
            }
            self.writer = nil
            self.videoIn = nil
            self.audioIn = nil
        }
    }

    /// Session-clock time -> microseconds since epoch, reading the host clock
    /// and the wall clock back to back.
    private func epochUs(_ t: CMTime) -> Int64 {
        let clock: CMClock
        if #available(iOS 15.4, *), let c = session.synchronizationClock {
            clock = c
        } else {
            clock = CMClockGetHostTimeClock()
        }
        var best = Double.greatestFiniteMagnitude
        var offset = 0.0
        for _ in 0..<5 {
            let a = CACurrentMediaTime()
            let now = CMTimeGetSeconds(CMClockGetTime(clock))
            let wall = Date().timeIntervalSince1970
            let b = CACurrentMediaTime()
            if b - a < best { best = b - a; offset = wall - now }
        }
        return Int64(((CMTimeGetSeconds(t) + offset) * 1_000_000).rounded())
    }

    // MARK: Capture callbacks
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if output === videoOut {
            if let pb = CMSampleBufferGetImageBuffer(sampleBuffer) {
                latestLock.lock(); latest = pb; latestLock.unlock()
                let id = textureId
                if id >= 0 { DispatchQueue.main.async { self.textures.textureFrameAvailable(id) } }
            }
            guard recording, let w = writer, let v = videoIn, w.status == .writing else { return }
            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            if !sessionStarted {
                // Video time 0 = this frame.
                w.startSession(atSourceTime: pts)
                sessionStarted = true
                firstPts = pts
            }
            if v.isReadyForMoreMediaData && v.append(sampleBuffer) {
                lastPts = pts
                frameCount += 1
            }
        } else if output === audioOut {
            guard recording, sessionStarted, let a = audioIn, writer?.status == .writing else { return }
            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            if CMTimeCompare(pts, firstPts) < 0 { return } // before the first frame
            if a.isReadyForMoreMediaData && a.append(sampleBuffer) { audioCount += 1 }
        }
    }

    func setZoom(_ ratio: Double) {
        guard let cam = device else { return }
        queue.async {
            do {
                try cam.lockForConfiguration()
                cam.videoZoomFactor = max(cam.minAvailableVideoZoomFactor,
                                          min(CGFloat(ratio), cam.maxAvailableVideoZoomFactor))
                cam.unlockForConfiguration()
            } catch {}
        }
    }

    func zoomRange() -> [Double] {
        guard let cam = device else { return [1.0, 1.0] }
        return [Double(cam.minAvailableVideoZoomFactor), Double(min(cam.maxAvailableVideoZoomFactor, 10))]
    }

    func close() {
        queue.sync {
            if recording { recording = false; writer?.cancelWriting() }
            session.stopRunning()
        }
        if textureId >= 0 { textures.unregisterTexture(textureId) }
        textureId = -1
    }
}
