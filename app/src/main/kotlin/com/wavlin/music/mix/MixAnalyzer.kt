/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.mix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.wavlin.music.local.LocalSongs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Supplies the same cache-aware data source the player uses, so that analysing a song reuses
 * the downloaded or cached audio when it exists. Registered by the music service.
 */
@UnstableApi
object MixAudioSource {
    @Volatile
    var factory: DataSource.Factory? = null
}

/** Decodes a song to a mono 11 kHz signal and runs [MixSignalAnalysis] on it. */
@UnstableApi
object MixAnalyzer {
    private const val TAG = "MixAnalyzer"
    private val mutex = Mutex()

    /** Returns the cached analysis or computes it. Null when the audio cannot be read. */
    suspend fun analyze(
        context: Context,
        songId: String,
    ): MixTrackAnalysis? {
        MixStore.analysisOf(songId)?.let { return it }
        return mutex.withLock {
            MixStore.analysisOf(songId)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                try {
                    val file = materialize(context, songId) ?: return@withContext null
                    try {
                        val decoded = decodeToMono(file) ?: return@withContext null
                        val durationMs = decoded.size * 1000L / MixSignalAnalysis.SAMPLE_RATE
                        val r = MixSignalAnalysis.analyze(decoded)
                        MixTrackAnalysis(songId, r.bpm, r.firstBeatMs, r.key, durationMs, r.peaks).also { MixStore.putAnalysis(it) }
                    } finally {
                        file.delete()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Analysis failed for $songId")
                    null
                }
            }
        }
    }

    /** Copies the song's audio into a temporary file, going through the player's cache. */
    private fun materialize(
        context: Context,
        songId: String,
    ): File? {
        val out = File(context.cacheDir, "mix_analysis_${songId.hashCode()}.bin")
        val local = LocalSongs.isLocal(songId)
        val uri = if (local) LocalSongs.uriOf(songId) else Uri.parse("https://music.youtube.com/watch?v=$songId")
        val factory = MixAudioSource.factory ?: return null
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uri).setKey(songId).build())
            FileOutputStream(out).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    stream.write(buffer, 0, n)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read audio of $songId")
            out.delete()
            return null
        } finally {
            try {
                source.close()
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun decodeToMono(file: File): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val estimated =
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    (format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 * MixSignalAnalysis.SAMPLE_RATE).toInt() + 4096
                } else {
                    MixSignalAnalysis.SAMPLE_RATE * 300
                }
            var out = FloatArray(estimated)
            var outLen = 0
            val resampler = Resampler()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            val total = shorts.remaining()
                            var i = 0
                            while (i + channels <= total) {
                                var sum = 0f
                                for (c in 0 until channels) sum += shorts.get(i + c) / 32768f
                                i += channels
                                val sample = resampler.push(sum / channels, sampleRate)
                                if (sample != null) {
                                    if (outLen == out.size) out = out.copyOf(out.size + MixSignalAnalysis.SAMPLE_RATE * 60)
                                    out[outLen++] = sample
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            return if (outLen > 0) out.copyOf(outLen) else null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Decoding failed")
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    /** Box-filter downsampler from any input rate to [MixSignalAnalysis.SAMPLE_RATE]. */
    private class Resampler {
        private var acc = 0f
        private var weight = 0f
        private var phase = 0.0

        fun push(
            sample: Float,
            inputRate: Int,
        ): Float? {
            val step = MixSignalAnalysis.SAMPLE_RATE.toDouble() / inputRate
            acc += sample
            weight += 1f
            phase += step
            if (phase >= 1.0) {
                phase -= 1.0
                val v = acc / weight
                acc = 0f
                weight = 0f
                return if (abs(v) < 1e-9f) 0f else v
            }
            return null
        }
    }
}
