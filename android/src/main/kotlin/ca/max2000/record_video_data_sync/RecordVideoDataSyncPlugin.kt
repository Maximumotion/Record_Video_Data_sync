package ca.max2000.record_video_data_sync

import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** Method channel "record_video_data_sync" -> [PreciseRecorder]. */
class RecordVideoDataSyncPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private var recorder: PreciseRecorder? = null
    private lateinit var binding: FlutterPlugin.FlutterPluginBinding
    private val main = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        binding = flutterPluginBinding
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "record_video_data_sync")
        channel.setMethodCallHandler(this)
    }

    private fun rec(): PreciseRecorder =
        recorder ?: PreciseRecorder(binding.applicationContext, binding.textureRegistry).also { recorder = it }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "open" -> {
                val w = call.argument<Int>("width") ?: 1280
                val h = call.argument<Int>("height") ?: 720
                val fps = call.argument<Int>("fps") ?: 30
                var answered = false
                rec().open(w, h, fps,
                    onReady = { id, pw, ph, so ->
                        main.post {
                            if (answered) return@post
                            answered = true
                            result.success(mapOf("textureId" to id, "width" to pw, "height" to ph, "sensorOrientation" to so))
                        }
                    },
                    onError = { msg ->
                        main.post {
                            if (answered) return@post
                            answered = true
                            result.error("open_failed", msg, null)
                        }
                    })
            }
            "start" -> {
                val path = call.argument<String>("path")
                if (path == null) { result.error("bad_args", "path missing", null); return }
                val rotation = call.argument<Int>("rotationDegrees") ?: 0
                val withAudio = call.argument<Boolean>("withAudio") ?: false
                var failed: String? = null
                rec().start(path, rotation, withAudio) { failed = it }
                if (failed != null) result.error("start_failed", failed, null) else result.success(null)
            }
            "stop" -> rec().stop { r ->
                main.post {
                    if (r == null) result.success(null)
                    else result.success(mapOf(
                        "path" to r.path,
                        "firstFrameEpochUs" to r.firstFrameEpochUs,
                        "lastFrameEpochUs" to r.lastFrameEpochUs,
                        "frameCount" to r.frameCount,
                        "timestampSource" to r.timestampSource,
                        "width" to r.width,
                        "height" to r.height,
                        "fps" to r.fps,
                        "hasAudio" to r.hasAudio,
                        "exposureUs" to r.exposureUs,
                        "rollingShutterSkewUs" to r.rollingShutterSkewUs,
                        "sensorMinusFrameUs" to if (r.sensorMinusFrameUs == Long.MIN_VALUE) null else r.sensorMinusFrameUs,
                    ))
                }
            }
            "setZoom" -> { rec().setZoom(call.argument<Double>("ratio") ?: 1.0); result.success(null) }
            "zoomRange" -> result.success(rec().zoomRange())
            "close" -> { recorder?.close(); recorder = null; result.success(null) }
            "displayRotation" -> {
                val dm = binding.applicationContext.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                result.success(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0)
            }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        recorder?.close()
        recorder = null
    }
}
