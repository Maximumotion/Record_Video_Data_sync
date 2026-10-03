package ca.max2000.record_video_data_sync

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import io.flutter.view.TextureRegistry
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Camera2 + MediaCodec + MediaMuxer video recorder that knows exactly when
 * its first video frame was captured.
 *
 * Why: the usual Flutter camera plugin reports "recording started" when its
 * call returns, 140-470 ms after the camera really began filming (measured
 * with LED flashes on a Galaxy S24), so video and sensor data could only be
 * lined up to about one frame. Here every frame keeps the camera sensor's
 * capture timestamp all the way into the MP4 (the encoder takes the
 * timestamp of each buffer from its input Surface), and the sensor clock is
 * converted to the phone's wall clock. [Result.firstFrameEpochUs] is the
 * phone time at which the file's first frame was exposed.
 *
 * Session layout: the capture session always holds two outputs -- the
 * Flutter preview texture and a persistent encoder input surface. While idle
 * the repeating request targets only the preview; Record switches the
 * repeating request to preview + encoder (no session reconfiguration).
 * Frames the encoder receives before it is ready are simply not written --
 * the first frame written is whatever comes first, and its exact time is
 * reported, so start-up latency never affects precision.
 */
class PreciseRecorder(
    private val context: Context,
    private val textures: TextureRegistry,
) {
    data class Result(
        val path: String,
        val firstFrameEpochUs: Long,
        val lastFrameEpochUs: Long,
        val frameCount: Int,
        val timestampSource: String,
        val width: Int,
        val height: Int,
        val fps: Int,
    )

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var producer: TextureRegistry.SurfaceProducer? = null
    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null

    private var cameraId: String = ""
    private var sensorOrientation = 90
    private var timestampRealtime = false
    private var timestampMonotonic = false // TIMESTAMP_BASE_MONOTONIC set (API 33+)
    private var startWallUs = 0L
    private var videoSize = Size(1280, 720)
    private var fps = 30
    private var zoomRatio = 1f

    // Recording state
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var outputPath = ""
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var frameCount = 0
    private var recording = false
    private var drainThread: Thread? = null

    val textureId: Long get() = producer?.id() ?: -1

    // ── Open / preview ───────────────────────────────────────────────────
    @SuppressLint("MissingPermission") // the app asks for CAMERA before opening
    fun open(
        width: Int,
        height: Int,
        requestedFps: Int,
        onReady: (textureId: Long, previewW: Int, previewH: Int, sensorOrientation: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            close()
            thread = HandlerThread("PreciseRecorder").also { it.start() }
            handler = Handler(thread!!.looper)

            cameraId = cameraManager.cameraIdList.first { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }
            val ch = cameraManager.getCameraCharacteristics(cameraId)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            timestampRealtime = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(MediaCodec::class.java).toList()
            videoSize = sizes.firstOrNull { it.width == width && it.height == height }
                ?: sizes.filter { it.width * 9 == it.height * 16 && it.width <= width }
                    .maxByOrNull { it.width } ?: sizes.first()
            fps = requestedFps

            val p = textures.createSurfaceProducer()
            p.setSize(videoSize.width, videoSize.height)
            producer = p
            previewSurface = p.surface

            // Persistent encoder surface: part of the session from the start.
            encoderSurface = MediaCodec.createPersistentInputSurface()

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    startSession(
                        { onReady(p.id(), videoSize.width, videoSize.height, sensorOrientation) },
                        onError,
                    )
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); camera = null; onError("camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            onError(e.toString())
        }
    }

    private fun startSession(onReady: () -> Unit, onError: (String) -> Unit) {
        val device = camera ?: return onError("no camera")
        val preview = previewSurface ?: return onError("no preview surface")
        val encoder = encoderSurface ?: return onError("no encoder surface")
        // The persistent surface must be configured before the session uses it.
        val tmp = configureCodec()
        tmp.setInputSurface(encoder)
        tmp.release()

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    s.setRepeatingRequest(buildRequest(recordTarget = false), null, handler)
                    onReady()
                } catch (e: Exception) { onError(e.toString()) }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { onError("session configure failed") }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val exec = Executor { r -> handler?.post(r) }
            val outPreview = OutputConfiguration(preview)
            val outEncoder = OutputConfiguration(encoder)
            if (Build.VERSION.SDK_INT >= 33) {
                // Android stamps encoder frames in the MONOTONIC clock by
                // default (to match audio), whatever the sensor's own clock
                // is (Galaxy S24: sensor REALTIME, encoder MONOTONIC -- 5 days
                // of deep sleep apart). Say it explicitly.
                outPreview.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC)
                outEncoder.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC)
                timestampMonotonic = true
            }
            device.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outPreview, outEncoder),
                exec, callback))
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(preview, encoder), callback, handler)
        }
    }

    private fun buildRequest(recordTarget: Boolean): CaptureRequest {
        val device = camera!!
        val b = device.createCaptureRequest(
            if (recordTarget) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(previewSurface!!)
        if (recordTarget) b.addTarget(encoderSurface!!)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        return b.build()
    }

    fun setZoom(ratio: Double) {
        zoomRatio = ratio.toFloat()
        try { session?.setRepeatingRequest(buildRequest(recording), null, handler) } catch (_: Exception) {}
    }

    fun zoomRange(): List<Double> {
        if (Build.VERSION.SDK_INT < 30 || cameraId.isEmpty()) return listOf(1.0, 1.0)
        val r = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return listOf(1.0, 1.0)
        return listOf(r.lower.toDouble(), r.upper.toDouble())
    }

    // ── Recording ────────────────────────────────────────────────────────
    private fun configureCodec(): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoSize.width, videoSize.height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, if (videoSize.width >= 1920) 16_000_000 else 8_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return c
    }

    /** [rotationDegrees]: how the phone is held (0/90/180/270, Surface.ROTATION_* x 90). */
    fun start(path: String, rotationDegrees: Int, onError: (String) -> Unit) {
        if (recording) return
        try {
            outputPath = path
            File(path).parentFile?.mkdirs()
            val c = configureCodec()
            c.setInputSurface(encoderSurface!!)
            c.start()
            codec = c
            muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                it.setOrientationHint((sensorOrientation - rotationDegrees + 360) % 360)
            }
            trackIndex = -1
            muxerStarted = false
            firstPtsUs = -1L
            lastPtsUs = -1L
            frameCount = 0
            recording = true
            startWallUs = System.currentTimeMillis() * 1000L
            drainThread = Thread { drainLoop() }.also { it.start() }
            session?.setRepeatingRequest(buildRequest(recordTarget = true), null, handler)
        } catch (e: Exception) {
            recording = false
            onError(e.toString())
        }
    }

    private fun drainLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer!!.addTrack(c.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && muxerStarted && buf != null) {
                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                        lastPtsUs = info.presentationTimeUs
                        frameCount++
                        muxer!!.writeSampleData(trackIndex, buf, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (!recording && idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // stop() signalled end of stream; keep draining until EOS arrives
            }
        }
    }

    fun stop(onDone: (Result?) -> Unit) {
        if (!recording) return onDone(null)
        try {
            session?.setRepeatingRequest(buildRequest(recordTarget = false), null, handler)
        } catch (_: Exception) {}
        recording = false
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        Thread {
            try { drainThread?.join(3000) } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            if (firstPtsUs < 0) { onDone(null); return@Thread }
            val useRealtime = pickRealtime(firstPtsUs)
            onDone(Result(
                path = outputPath,
                firstFrameEpochUs = toEpochUs(firstPtsUs, useRealtime),
                lastFrameEpochUs = toEpochUs(lastPtsUs, useRealtime),
                frameCount = frameCount,
                timestampSource = if (useRealtime) "realtime" else "monotonic",
                width = videoSize.width,
                height = videoSize.height,
                fps = fps,
            ))
        }.start()
    }

    /**
     * Which clock the frame timestamps are in. With TIMESTAMP_BASE_MONOTONIC
     * (API 33+) it is System.nanoTime. Otherwise: the clock under which the
     * first frame falls closest to when Record was pressed (realtime and
     * monotonic differ by all the deep sleep since boot -- usually hours or
     * days -- so the wrong one is never close).
     */
    private fun pickRealtime(ptsUs: Long): Boolean {
        if (timestampMonotonic) return false
        val viaRt = abs(toEpochUs(ptsUs, true) - startWallUs)
        val viaMono = abs(toEpochUs(ptsUs, false) - startWallUs)
        return viaRt < viaMono
    }

    /** Frame time -> wall clock, reading that clock and the wall clock back to back. */
    private fun toEpochUs(ptsUs: Long, realtime: Boolean): Long {
        var best = Long.MAX_VALUE
        var offsetUs = 0L
        repeat(5) {
            val a = System.nanoTime()
            val clockNs = if (realtime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
            val wallUs = System.currentTimeMillis() * 1000L
            val b = System.nanoTime()
            if (b - a < best) {
                best = b - a
                offsetUs = wallUs - clockNs / 1000L
            }
        }
        return ptsUs + offsetUs
    }

    fun close() {
        try { if (recording) { recording = false; codec?.signalEndOfInputStream() } } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
        try { producer?.release() } catch (_: Exception) {}
        producer = null
        previewSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
