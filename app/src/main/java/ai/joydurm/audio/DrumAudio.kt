package ai.joydurm.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import ai.joydurm.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.*
import kotlin.random.Random

/** Offline one-shot synthesis + Android's native polyphonic SoundPool. No audio generation on the hit path. */
class DrumAudio(private val context: Context,private val status: (String)->Unit) {
    private val pool=SoundPool.Builder().setMaxStreams(24).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build()
    private val ids=ConcurrentHashMap<String,Int>()
    private val ready=ConcurrentHashMap.newKeySet<Int>()
    private val worker=Executors.newSingleThreadExecutor()
    private val openStreams=ConcurrentHashMap<Int,Long>()
    @Volatile var kit=0
    @Volatile var volume=0.8f
    @Volatile private var closed=false
    val kitNames=listOf("Studio · 合成鼓","Electronic · 电子","Lo-fi · 柔和")
    init {
        pool.setOnLoadCompleteListener { _,id,result -> if(result==0) { ready.add(id); if(ready.size==ids.size) status("音色已就绪") } else status("音色加载失败 ($result)") }
        worker.execute {
            try {
                for(bank in 0..2) for(name in Drum.entries.map { it.name }+listOf("HAT_HALF","HAT_OPEN","CLICK")) {
                    if(closed) break
                    val f=File(context.cacheDir,"drum-$bank-$name.wav")
                    if(!f.exists()) f.writeBytes(synthesize(name,bank))
                    val id=pool.load(f.path,1); ids["$bank:$name"]=id
                }
            } catch(e: Exception) { if(!closed) status("音频初始化失败：${e.message}") }
        }
    }
    @Synchronized fun play(hit: Hit) {
        if(closed) return
        if(hit.drum==Drum.CHICK) { openStreams.keys.forEach { pool.stop(it) }; openStreams.clear() }
        val name=if(hit.drum==Drum.HAT) when { hit.openness>0.65f -> "HAT_OPEN"; hit.openness>0.15f -> "HAT_HALF"; else -> "HAT" } else hit.drum.name
        playName(name,hit.velocity)
    }
    fun click() { playName("CLICK",0.5f) }
    private fun playName(name: String,velocity: Float) {
        val id=ids["$kit:$name"] ?: return
        if(id !in ready) return
        val gain=(velocity*volume).coerceIn(0f,1f)
        val stream=pool.play(id,gain,gain,1,0,1f)
        if(name=="HAT_OPEN" || name=="HAT_HALF") {
            val now=SystemClock.elapsedRealtimeNanos()
            openStreams.entries.removeAll { now-it.value>3_000_000_000L }
            if(stream!=0)openStreams[stream]=now
        }
    }
    fun importWav(file: File,drum: Drum,bank: Int=kit) {
        require(file.length() in 44..1_000_000) { "WAV 必须小于 1 MB" }
        WaveValidator.validate(file.readBytes())
        worker.execute { if(!closed) ids["$bank:${drum.name}"]=pool.load(file.path,1) }
    }
    fun close() { closed=true; worker.shutdownNow(); pool.release() }
    companion object {
        fun synthesize(name: String,bank: Int): ByteArray {
            val rate=48000
            val duration=when(name) { "CRASH" -> 1.8; "RIDE" -> 1.1; "HAT_OPEN" -> 0.65; "HAT_HALF" -> 0.24; "CLICK" -> 0.025; else -> 0.45 }
            val n=(duration*rate).toInt(); val out=ByteBuffer.allocate(44+n*2).order(ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray()); out.putInt(36+n*2); out.put("WAVEfmt ".toByteArray()); out.putInt(16)
            out.putShort(1); out.putShort(1); out.putInt(rate); out.putInt(rate*2); out.putShort(2); out.putShort(16); out.put("data".toByteArray()); out.putInt(n*2)
            val random=Random(name.hashCode()+bank*91); var noisePrev=0.0; var phase=0.0
            val pitch=when(bank) { 1 -> 1.25; 2 -> 0.78; else -> 1.0 }
            repeat(n) { i ->
                val t=i.toDouble()/rate; val noise=random.nextDouble(-1.0,1.0); val high=noise-noisePrev; noisePrev=noise
                val signal=when(name) {
                    "KICK" -> { phase+=2*PI*(48+110*exp(-t*32))*pitch/rate; sin(phase)*exp(-t*13)+high*exp(-t*180)*0.12 }
                    "SNARE" -> (sin(2*PI*185*pitch*t)*0.35+noise*0.65)*exp(-t*19)
                    "TOM1","TOM2","FLOOR" -> { val f=when(name){"TOM1"->220;"TOM2"->165;else->110}; sin(2*PI*f*pitch*t+2*(1-exp(-t*15)))*exp(-t*11)+noise*0.06*exp(-t*45) }
                    "CLICK" -> sin(2*PI*1800*t)*exp(-t*180)
                    else -> {
                        val decay=when(name){"HAT","CHICK"->70;"HAT_HALF"->25;"HAT_OPEN"->8;"CRASH"->3;else->5}
                        val metallic=(sin(2*PI*3311*t)+sin(2*PI*4877*t)+sin(2*PI*6317*t))*0.1
                        (high*0.26+metallic)*exp(-t*decay)
                    }
                }
                val softened=if(bank==2) signal*0.75 else signal
                out.putShort((tanh(softened*1.3)*28000).toInt().toShort())
            }
            return out.array()
        }
    }
}
