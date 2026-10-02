package ai.joydurm.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.joydurm.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.*
import kotlin.random.Random

/** Offline one-shot synthesis + Android's native polyphonic SoundPool. No audio generation on the hit path. */
class DrumAudio(context: Context,private val status: (String)->Unit): AudioSink {
    private val context=context.applicationContext
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val pool=SoundPool.Builder().setMaxStreams(24).setAudioAttributes(attributes).build()
    private val lock=Any()
    private val slots=SampleSlots()
    private val callbacks=mutableMapOf<Int,(Boolean)->Unit>()
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val audioManager=this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val trace=AudioTrace()
    private val playback=VoicePlayback(slots,object: SampleBackend {
        override fun play(sampleId: Int,gain: Float)=pool.play(sampleId,gain,gain,1,0,1f)
        override fun stop(streamId: Int) { pool.stop(streamId) }
    },SystemClock::elapsedRealtimeNanos,trace)
    private var lifecyclePaused=true
    private var focusGranted=false
    private val focusRequest=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            synchronized(lock) {
                if(!closed) {
                    focusGranted=change==AudioManager.AUDIOFOCUS_GAIN
                    if(focusGranted && !lifecyclePaused) playback.resume()
                    else playback.pause("audio-focus-$change")
                }
            }
        },main).build()
    private val noisyReceiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context?,intent: Intent?) {
            if(intent?.action==AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
                notifyStatus("音频输出设备已断开，已止音；返回应用后恢复")
            }
        }
    }
    @Volatile var kit=0
    @Volatile var volume=0.8f
    @Volatile private var closed=false
    private var announcedReady=false
    val kitNames=listOf("Studio · 合成鼓","Electronic · 电子","Lo-fi · 柔和")
    init {
        val noisyFilter=IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if(Build.VERSION.SDK_INT>=33) this.context.registerReceiver(noisyReceiver,noisyFilter,Context.RECEIVER_NOT_EXPORTED)
        else this.context.registerReceiver(noisyReceiver,noisyFilter)
        pool.setOnLoadCompleteListener { _,id,result ->
            var callback: ((Boolean)->Unit)?=null
            var success=false
            var notice: String?=null
            synchronized(lock) {
                if(!closed) {
                    val completion=slots.complete(id,result==0)
                    callback=callbacks.remove(id)
                    success=completion?.activated==true
                    completion?.unload?.let { pool.unload(it) }
                    if(result!=0) notice="音色加载失败 ($result)，保留原有音色"
                    if(!announcedReady && slots.loadedCount==3*SoundVoice.entries.size) { announcedReady=true; notice="音色已就绪" }
                }
            }
            notice?.let(::notifyStatus)
            callback?.let { done -> main.post { done(success) } }
        }
        worker.execute {
            try {
                for(bank in 0..2) for(voice in SoundVoice.entries) {
                    if(closed) return@execute
                    val f=File(this.context.cacheDir,"drum-$bank-${voice.sampleName}.wav")
                    if(!f.exists()) f.writeBytes(synthesize(voice.sampleName,bank))
                    load(f,"$bank:${voice.sampleName}",null)
                }
            } catch(e: Exception) { if(!closed) notifyStatus("音频初始化失败：${e.message}") }
        }
    }
    private fun load(file: File,key: String,onComplete: ((Boolean)->Unit)?) {
        var rejected=false
        synchronized(lock) {
            if(closed) rejected=true
            else {
                val id=pool.load(file.path,1)
                if(id==0) rejected=true
                else { slots.register(key,id); if(onComplete!=null) callbacks[id]=onComplete }
            }
        }
        if(rejected) {
            if(!closed) notifyStatus("音色未能加载，保留原有音色")
            onComplete?.let { main.post { it(false) } }
        }
    }
    override fun play(hit: Hit): Int = synchronized(lock) {
        if(closed) 0 else playback.play(hit,kit,volume)
    }

    fun playVoice(voice: SoundVoice,velocity: Float=0.8f,actionTimeNs: Long=SystemClock.elapsedRealtimeNanos()): Int = synchronized(lock) {
        if(closed) 0 else playback.playVoice(voice,velocity,kit,volume,actionTimeNs)
    }
    fun click(actionTimeNs: Long=SystemClock.elapsedRealtimeNanos()): Int=playVoice(SoundVoice.CLICK,0.5f,actionTimeNs)
    override fun control(control: HatControl) = synchronized(lock) { if(!closed) playback.control(control) }
    override fun chokeAll() = synchronized(lock) { if(!closed) playback.chokeAll() }

    /** Backgrounding and output/focus loss stop all voices; no old sounds are resumed. */
    override fun pause() {
        synchronized(lock) {
            if(closed) return
            lifecyclePaused=true; focusGranted=false
            playback.pause("lifecycle-or-output-pause")
        }
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
    override fun resume(): Boolean {
        synchronized(lock) { if(closed) return false; lifecyclePaused=false }
        val granted=audioManager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val active=synchronized(lock) {
            if(closed || lifecyclePaused) false
            else {
                focusGranted=granted
                if(granted) playback.resume() else playback.pause("focus-request-denied")
                true
            }
        }
        if(!active) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            return false
        }
        if(!granted) notifyStatus("未获得音频焦点，演奏已静音；返回应用后重试")
        return granted
    }

    fun exportTraceCsv(file: File) { trace.export(file) }
    private fun notifyStatus(message: String) { main.post { if(!closed) status(message) } }
    /** Completion is delivered on the main thread after the sample actually decodes. */
    fun importWav(file: File,drum: Drum,bank: Int=kit,onComplete: ((Boolean)->Unit)?=null) {
        importWav(file,SoundVoice.fromDrum(drum),bank,onComplete)
    }
    fun importWav(file: File,voice: SoundVoice,bank: Int=kit,onComplete: ((Boolean)->Unit)?=null) {
        require(bank in 0..2) { "音色库无效" }
        require(file.length() in 44..1_000_000) { "WAV 必须小于 1 MB" }
        WaveValidator.validate(file.readBytes())
        try {
            worker.execute {
                try { load(file,"$bank:${voice.sampleName}",onComplete) }
                catch(e: Exception) {
                    if(!closed) notifyStatus("音色加载失败：${e.message}")
                    onComplete?.let { main.post { it(false) } }
                }
            }
        } catch(_: java.util.concurrent.RejectedExecutionException) { onComplete?.let { main.post { it(false) } } }
    }
    fun close() {
        val canceled=synchronized(lock) {
            if(closed) return
            closed=true
            playback.pause("close")
            val pending=callbacks.values.toList()
            callbacks.clear(); slots.clear()
            pool.setOnLoadCompleteListener(null)
            pool.release()
            pending
        }
        // Drain queued loaders: they see closed and report failure without touching the pool.
        worker.shutdown()
        audioManager.abandonAudioFocusRequest(focusRequest)
        runCatching { context.unregisterReceiver(noisyReceiver) }
        canceled.forEach { done -> main.post { done(false) } }
    }
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
