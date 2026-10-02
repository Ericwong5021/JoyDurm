package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test

class RawHidAckTest {
    private fun reply(command: Int, positive: Boolean=true, spiAddress: Int=0x6020, length: Int=24): ByteArray {
        val r=ByteArray(if(command==0x10) 20+length else 15)
        r[0]=0x21; r[13]=if(positive) 0x80.toByte() else 0; r[14]=command.toByte()
        if(command==0x10) {
            (0..3).forEach { r[15+it]=(spiAddress ushr (8*it)).toByte() }; r[19]=length.toByte()
            if(length>=24) (0..2).forEach { k ->
                r[26+k*2]=0; r[27+k*2]=0x40; r[38+k*2]=0x3b; r[39+k*2]=0x34
            }
        }
        return r
    }
    @Test fun onlyMatchedPositiveAckAdvancesInitialization() {
        val ack=RawHidAck()
        assertEquals(0x40,ack.nextWrite(0)!![10].toInt())
        assertFalse(ack.onReport(reply(0x03))); assertNull(ack.nextWrite(1))
        assertTrue(ack.onReport(reply(0x40))); assertEquals(0x03,ack.nextWrite(2)!![10].toInt())
    }
    @Test fun timeoutRetriesAndMandatoryFailureAreBoundedWithoutWaitingInReader() {
        val ack=RawHidAck(timeoutNs=10,maxAttempts=3)
        assertNotNull(ack.nextWrite(0)); assertNull(ack.nextWrite(9))
        assertNotNull(ack.nextWrite(10)); assertNotNull(ack.nextWrite(20)); assertNull(ack.nextWrite(30))
        assertNotNull(ack.failure); assertFalse(ack.ready)
    }
    @Test fun shortWriteAndMandatoryNackFailClosed() {
        val short=RawHidAck(); val packet=short.nextWrite(0)!!; short.written(1,packet.size)
        assertNotNull(short.failure); assertNull(short.nextWrite(1))
        val nack=RawHidAck(); nack.nextWrite(0); nack.onReport(reply(0x40,false)); assertNotNull(nack.failure)
    }
    @Test fun spiAddressLengthAndPayloadMustMatchAndOptionalTimeoutKeepsDefaults() {
        val ack=RawHidAck(timeoutNs=10,maxAttempts=1)
        listOf(0x40,0x03,0x30).forEachIndexed { index,command -> ack.nextWrite(index.toLong()); ack.onReport(reply(command)) }
        assertEquals(0x10,ack.nextWrite(3)!![10].toInt())
        assertFalse(ack.onReport(reply(0x10,spiAddress=0x6030)))
        assertFalse(ack.onReport(reply(0x10,length=12)))
        assertFalse(ack.onReport(reply(0x10).copyOf(30)))
        ack.nextWrite(13); assertTrue(ack.ready); assertNull(ack.failure)
        assertEquals(16384,ack.scale.accSensitivity[0])
    }
    @Test fun validSpiCalibrationAndPrefixedReportsAreAccepted() {
        val ack=RawHidAck()
        listOf(0x40,0x03,0x30).forEachIndexed { index,command -> ack.nextWrite(index.toLong()); ack.onReport(reply(command)) }
        ack.nextWrite(3)
        assertTrue(ack.onReport(byteArrayOf(0xa1.toByte())+reply(0x10)))
        assertTrue(ack.ready); assertEquals(13371,ack.scale.gyroSensitivity[0])
    }
}
