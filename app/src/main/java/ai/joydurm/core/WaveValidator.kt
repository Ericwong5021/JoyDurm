package ai.joydurm.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Accept only bounded PCM16 WAV one-shots that SoundPool can decode predictably. */
object WaveValidator {
    fun validate(bytes: ByteArray) {
        require(bytes.size in 44..1_000_000) { "WAV 必须为 44 B–1 MB" }
        fun tag(p: Int)=String(bytes,p,4,Charsets.US_ASCII)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(tag(0)=="RIFF" && tag(8)=="WAVE" && b.getInt(4).toLong()+8==bytes.size.toLong()) { "WAV 文件头损坏或不完整" }
        var p=12; var format=false; var channels=0; var rate=0; var data=0
        while(p+8<=bytes.size) {
            val size=b.getInt(p+4)
            require(size>=0 && p.toLong()+8+size<=bytes.size) { "WAV 数据块越界" }
            when(tag(p)) {
                "fmt " -> {
                    require(size>=16) { "WAV 格式块不完整" }
                    val encoding=b.getShort(p+8).toInt(); channels=b.getShort(p+10).toInt(); rate=b.getInt(p+12)
                    val bits=b.getShort(p+22).toInt()
                    require(encoding==1 && channels in 1..2 && bits==16 && rate in 8000..96000) { "仅支持 8–96 kHz 单/双声道 PCM16 WAV" }
                    require(b.getShort(p+20).toInt()==channels*2) { "WAV 对齐参数无效" }
                    format=true
                }
                "data" -> data=size
            }
            p += 8+size+(size and 1)
        }
        require(format && data>0 && data%(channels*2)==0) { "WAV 缺少有效音频数据" }
        require(data.toDouble()/(channels*2*rate)<=3.0) { "鼓音色应为不超过 3 秒的单次采样" }
    }
}
