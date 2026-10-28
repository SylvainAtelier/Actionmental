package com.actionmental.platform

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10

/** Thrown when a recording never rose above the noise floor; uploading it would only cost money. */
internal class SilentAudioException : IllegalStateException("No speech level captured")

/** Foreground-only, bounded PCM capture. No network or service is involved. */
internal class WavSpeechRecorder(
    private val file: File,
    private val onComplete: (Result<File>) -> Unit,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val discarded = AtomicBoolean(false)
    @Volatile private var audio: AudioRecord? = null

    /** Latest input level in 0..1, written by the capture thread and sampled by the UI per frame. */
    @Volatile var level: Float = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Unsupported audio format" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 16_384))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Microphone unavailable")
        }
        audio = recorder
        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (error: Exception) {
            audio = null
            recorder.release()
            throw error
        }
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            capture(recorder)
        }, "speech-wav-capture").start()
    }

    private fun capture(recorder: AudioRecord) {
        val result = runCatching {
            var size = 0
            var loudest = 0
            // Buffered writes: ~16 short reads per second become a handful of disk writes.
            BufferedOutputStream(FileOutputStream(file), 64 * 1024).use { output ->
                output.write(ByteArray(HEADER_BYTES))
                val buffer = ByteArray(READ_BYTES)
                while (!stopped.get() && size < MAX_DATA_BYTES) {
                    val count = recorder.read(buffer, 0, minOf(buffer.size, MAX_DATA_BYTES - size))
                    if (count < 0 && !stopped.get()) error("Microphone read failed")
                    if (count <= 0) break
                    val aligned = count - count % 2
                    val peak = peak(buffer, aligned)
                    if (peak > loudest) loudest = peak
                    level = levelOf(peak)
                    output.write(buffer, 0, aligned)
                    size += aligned
                }
            }
            check(size > 0) { "No audio captured" }
            if (loudest < SILENCE_PEAK) throw SilentAudioException()
            RandomAccessFile(file, "rw").use { it.write(wavHeader(size)) }
            file
        }
        level = 0f
        audio = null
        runCatching { recorder.stop() }
        recorder.release()
        Handler(Looper.getMainLooper()).post {
            if (discarded.get()) file.delete()
            else {
                if (result.isFailure) file.delete()
                onComplete(result)
            }
        }
    }

    fun stop() {
        stopped.set(true)
        runCatching { audio?.stop() }
    }

    override fun close() {
        discarded.set(true)
        stop()
        // The writer owns deletion until its file handle has closed.
        if (audio == null) file.delete()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 120
        const val MAX_DATA_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS
        private const val HEADER_BYTES = 44
        /** 64 ms per read: smooth enough for a level meter, cheap enough to never matter. */
        private const val READ_BYTES = SAMPLE_RATE * 2 * 64 / 1000
        /** About -54 dBFS; below this for the whole take there was nothing to transcribe. */
        internal const val SILENCE_PEAK = 64
        private const val FLOOR_DB = -50f

        /** Largest absolute 16-bit little-endian sample in the first [count] bytes. */
        internal fun peak(buffer: ByteArray, count: Int): Int {
            var max = 0
            var i = 0
            while (i + 1 < count) {
                val sample = abs((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8))
                if (sample > max) max = sample
                i += 2
            }
            return max
        }

        /** Peak to a perceptual 0..1 scale: -50 dBFS and below is 0, full scale is 1. */
        internal fun levelOf(peak: Int): Float {
            if (peak <= 0) return 0f
            val db = 20f * log10(peak.coerceAtMost(32_767) / 32_767f)
            return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }

        internal fun wavHeader(dataBytes: Int): ByteArray {
            require(dataBytes in 0..MAX_DATA_BYTES && dataBytes % 2 == 0)
            return java.nio.ByteBuffer.allocate(HEADER_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
                .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
                .putShort(1.toShort()).putShort(1.toShort()).putInt(SAMPLE_RATE)
                .putInt(SAMPLE_RATE * 2).putShort(2.toShort()).putShort(16.toShort())
                .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes).array()
        }
    }
}
