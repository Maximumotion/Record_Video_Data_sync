package ca.max2000.record_video_data_sync

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder

/**
 * Microphone -> AAC, with every buffer timestamped in the MONOTONIC clock
 * (System.nanoTime) -- the same clock the video frames carry -- using
 * AudioRecord.getTimestamp(TIMEBASE_MONOTONIC), which says exactly when a
 * given audio frame was captured. Encoded samples go to [sink]; [onFormat]
 * delivers the AAC track format once.
 */
class AudioTrackRecorder(
    private val onFormat: (MediaFormat) -> Unit,
    private val sink: (java.nio.ByteBuffer, MediaCodec.BufferInfo) -> Unit,
) {
    private val sampleRate = 48_000
    private val channels = 1
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** False when the microphone could not be opened (no permission, busy). */
    @SuppressLint("MissingPermission") // the app asks for RECORD_AUDIO first
    fun start(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 4, 8192))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            r.startRecording()
            record = r
            codec = c
            running = true
            thread = Thread { loop(r, c) }.also { it.start() }
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    private fun loop(r: AudioRecord, c: MediaCodec) {
        val chunk = 1024 * 2 // 1024 mono 16-bit frames
        val pcm = ByteArray(chunk)
        val ts = AudioTimestamp()
        var framesRead = 0L
        var haveTs = false
        var tsFrame = 0L
        var tsNs = 0L
        val info = MediaCodec.BufferInfo()
        var eosSent = false
        while (true) {
            if (running) {
                val n = r.read(pcm, 0, chunk)
                if (n > 0) {
                    // Exact capture time of this chunk's first frame.
                    if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        haveTs = true; tsFrame = ts.framePosition; tsNs = ts.nanoTime
                    }
                    val ptsUs = if (haveTs) {
                        (tsNs + (framesRead - tsFrame) * 1_000_000_000L / sampleRate) / 1000L
                    } else {
                        // No timestamp yet: the chunk just ended "now".
                        (System.nanoTime() - (n / 2) * 1_000_000_000L / sampleRate) / 1000L
                    }
                    framesRead += n / 2
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        inBuf.put(pcm, 0, n)
                        c.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                    }
                }
            } else if (!eosSent) {
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosSent = true
                }
            }
            // Drain whatever is encoded.
            while (true) {
                val outIdx = c.dequeueOutputBuffer(info, 0)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { onFormat(c.outputFormat); continue }
                if (outIdx < 0) break
                val out = c.getOutputBuffer(outIdx)
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!config && info.size > 0 && out != null) sink(out, info)
                c.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
            if (eosSent) Thread.sleep(2)
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { thread?.join(2000) } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        codec = null
        record = null
        thread = null
    }
}
