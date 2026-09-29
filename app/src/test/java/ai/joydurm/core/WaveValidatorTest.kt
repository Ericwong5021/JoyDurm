package ai.joydurm.core

import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WaveValidatorTest {
    private fun wav(): ByteArray {
        val b=ByteBuffer.allocate(44+4800*2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(b.capacity()-8); b.put("WAVEfmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(1); b.putInt(48000); b.putInt(96000); b.putShort(2); b.putShort(16); b.put("data".toByteArray()); b.putInt(9600)
        return b.array()
    }
    @Test fun acceptsPcm16OneShot() { WaveValidator.validate(wav()) }
    @Test(expected=IllegalArgumentException::class) fun rejectsTruncatedFile() { WaveValidator.validate(wav().copyOf(100)) }
    @Test(expected=IllegalArgumentException::class) fun rejectsCompressedCodec() { val b=wav(); b[20]=3; WaveValidator.validate(b) }
    @Test(expected=IllegalArgumentException::class) fun rejectsOversizedChunk() { val b=wav(); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(40,Int.MAX_VALUE); WaveValidator.validate(b) }
}
