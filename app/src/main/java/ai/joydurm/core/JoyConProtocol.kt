package ai.joydurm.core

/** Original Switch Joy-Con Bluetooth report parser, not Joy-Con 2 BLE. */
object JoyConProtocol {
    data class ImuScale(val accOrigin: IntArray=IntArray(3), val accSensitivity: IntArray=IntArray(3){16384},
        val gyroOrigin: IntArray=IntArray(3), val gyroSensitivity: IntArray=IntArray(3){13371})
    private fun s16(b: ByteArray,i: Int): Int = ((b[i].toInt() and 255) or (b[i+1].toInt() shl 8)).toShort().toInt()
    fun parse(report: ByteArray,device: String,timeNs: Long,scale: ImuScale=ImuScale()): List<ImuSample> {
        val offset=if(report.isNotEmpty() && (report[0].toInt() and 255)==0xa1) 1 else 0
        if(report.size<offset+49 || (report[offset].toInt() and 255)!=0x30) return emptyList()
        return (0..2).map { index ->
            val p=offset+13+index*12
            // Factory accOrigin is a coefficient origin, NOT a zero-g offset.
            fun acc(k: Int) = s16(report,p+k*2)*4.0*9.80665/(scale.accSensitivity[k]-scale.accOrigin[k]).takeIf { it!=0 }.let { it?:16384 }
            fun gyro(k: Int) = (s16(report,p+6+k*2)-scale.gyroOrigin[k])*Math.toRadians(936.0)/(scale.gyroSensitivity[k]-scale.gyroOrigin[k]).takeIf { it!=0 }.let { it?:13371 }
            ImuSample(device,timeNs-(2-index)*5_000_000L,Vec3(acc(0),acc(1),acc(2)),Vec3(gyro(0),gyro(1),gyro(2)))
        }
    }
    fun subcommand(counter: Int, command: Int, vararg data: Int): ByteArray {
        val packet=ByteArray(11+data.size)
        packet[0]=1; packet[1]=(counter and 15).toByte()
        byteArrayOf(0,1,0x40,0x40,0,1,0x40,0x40).copyInto(packet,2)
        packet[10]=command.toByte(); data.forEachIndexed { i,v -> packet[11+i]=v.toByte() }
        return packet
    }
    fun factoryScale(bytes: ByteArray): ImuScale {
        require(bytes.size>=24)
        fun group(p: Int)=IntArray(3){s16(bytes,p+it*2)}
        val scale=ImuScale(group(0),group(6),group(12),group(18))
        require((0..2).all { kotlin.math.abs(scale.accSensitivity[it]-scale.accOrigin[it])>=100 && kotlin.math.abs(scale.gyroSensitivity[it]-scale.gyroOrigin[it])>=100 }) { "Invalid factory IMU coefficients" }
        return scale
    }
}
