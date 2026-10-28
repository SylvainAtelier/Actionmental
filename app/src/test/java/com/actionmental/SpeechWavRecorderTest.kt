package com.actionmental

import com.actionmental.platform.WavSpeechRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechWavRecorderTest {
    @Test fun headerDescribesMono16KhzPcm16() {
        val header = WavSpeechRecorder.wavHeader(32_000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32_036, bytes.getInt(4))
        assertEquals(16, bytes.getInt(16))
        assertEquals(1, bytes.getShort(20).toInt())
        assertEquals(1, bytes.getShort(22).toInt())
        assertEquals(16_000, bytes.getInt(24))
        assertEquals(32_000, bytes.getInt(28))
        assertEquals(2, bytes.getShort(32).toInt())
        assertEquals(16, bytes.getShort(34).toInt())
        assertEquals(32_000, bytes.getInt(40))
    }

    @Test fun maxDurationIsBoundedTo120SecondsAndUnderFourMegabytes() {
        assertEquals(120, WavSpeechRecorder.MAX_SECONDS)
        assertEquals(3_840_000, WavSpeechRecorder.MAX_DATA_BYTES)
        val bytes = ByteBuffer.wrap(WavSpeechRecorder.wavHeader(WavSpeechRecorder.MAX_DATA_BYTES))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3_840_000, bytes.getInt(40))
    }

    @Test fun peakReadsSignedLittleEndianSamples() {
        val samples = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(100).putShort((-32_768).toShort()).putShort(-5).array()
        assertEquals(32_768, WavSpeechRecorder.peak(samples, 6))
        assertEquals(100, WavSpeechRecorder.peak(samples, 3))
        assertEquals(0, WavSpeechRecorder.peak(samples, 0))
    }

    @Test fun levelMapsSilenceToZeroAndFullScaleToOne() {
        assertEquals(0f, WavSpeechRecorder.levelOf(0), 0f)
        assertEquals(0f, WavSpeechRecorder.levelOf(30), 0f)
        assertEquals(1f, WavSpeechRecorder.levelOf(32_768), 0.0001f)
        val mid = WavSpeechRecorder.levelOf(1_036) // about -30 dBFS
        assertEquals(0.4f, mid, 0.02f)
        assert(WavSpeechRecorder.levelOf(WavSpeechRecorder.SILENCE_PEAK) < 0.1f)
    }

    @Test fun rejectsOversizeNegativeAndUnalignedPayloads() {
        listOf(-2, 1, WavSpeechRecorder.MAX_DATA_BYTES + 2).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) { WavSpeechRecorder.wavHeader(size) }
        }
    }
}
