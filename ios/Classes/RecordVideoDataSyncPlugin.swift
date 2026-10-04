import Flutter
import UIKit

/// Method channel "record_video_data_sync" -> [PreciseRecorderIOS]
/// (same calls and results as the Android side).
public class RecordVideoDataSyncPlugin: NSObject, FlutterPlugin {
  private let textures: FlutterTextureRegistry
  private var recorder: PreciseRecorderIOS?

  init(textures: FlutterTextureRegistry) {
    self.textures = textures
  }

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "record_video_data_sync", binaryMessenger: registrar.messenger())
    let instance = RecordVideoDataSyncPlugin(textures: registrar.textures())
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  private func rec() -> PreciseRecorderIOS {
    if let r = recorder { return r }
    let r = PreciseRecorderIOS(textures: textures)
    recorder = r
    return r
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let args = call.arguments as? [String: Any] ?? [:]
    switch call.method {
    case "open":
      rec().open(width: args["width"] as? Int ?? 1280,
                 height: args["height"] as? Int ?? 720,
                 fps: args["fps"] as? Int ?? 30) { r in
        switch r {
        case .success(let m): result(m)
        case .failure(let e): result(FlutterError(code: "open_failed", message: e.localizedDescription, details: nil))
        }
      }
    case "start":
      guard let path = args["path"] as? String else {
        result(FlutterError(code: "bad_args", message: "path missing", details: nil)); return
      }
      do {
        try rec().start(path: path,
                        rotationDegrees: args["rotationDegrees"] as? Int ?? 90,
                        withAudio: args["withAudio"] as? Bool ?? false)
        result(nil)
      } catch {
        result(FlutterError(code: "start_failed", message: error.localizedDescription, details: nil))
      }
    case "stop":
      rec().stop { m in result(m) }
    case "setZoom":
      rec().setZoom(args["ratio"] as? Double ?? 1.0)
      result(nil)
    case "zoomRange":
      result(rec().zoomRange())
    case "close":
      recorder?.close()
      recorder = nil
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }
}
