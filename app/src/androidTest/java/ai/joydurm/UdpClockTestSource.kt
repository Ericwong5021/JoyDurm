package ai.joydurm

import android.os.SystemClock
import ai.joydurm.input.ControllerHub
import org.json.JSONObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** One real UDP source endpoint; its responder stays active throughout the test. */
internal class UdpClockTestSource(private val hub: ControllerHub, private val port: Int, private val token: String): Closeable {
    private val socket=DatagramSocket(0,InetAddress.getLoopbackAddress()).apply { soTimeout=100 }
    private val failure=AtomicReference<Throwable?>()
    private val started=CountDownLatch(1)
    private val requests=AtomicInteger()
    @Volatile private var active=true
    private val responder=thread(name="JoyDurm-Test-Clock-Source",isDaemon=true) {
        val buffer=ByteArray(8193)
        started.countDown()
        while(active) try {
            val packet=DatagramPacket(buffer,buffer.size)
            socket.receive(packet)
            // Capture receipt before parsing, assertions, diagnostics or test-thread scheduling.
            val sourceReceive=SystemClock.elapsedRealtimeNanos()
            check(packet.port==port) { "Sync request came from an unexpected listener port" }
            val request=JSONObject(String(packet.data,packet.offset,packet.length,Charsets.UTF_8))
            check(request.getInt("v")==2 && request.getString("type")=="sync" && request.getString("token")==token)
            requests.incrementAndGet()
            val reply=JSONObject().put("v",2).put("type","sync_reply").put("token",token)
                .put("nonce",request.getString("nonce")).put("clientSendNs",request.getLong("clientSendNs"))
                .put("sourceReceiveNs",sourceReceive)
            // Serialization belongs to source processing, before the actual send clock.
            // A timestamp captured before toString() falsely counts cold JSON/JIT work
            // as network RTT and biases an otherwise valid four-clock exchange.
            val marker=Long.MAX_VALUE.toString()
            val text=reply.put("sourceSendNs",Long.MAX_VALUE).toString()
            val bytes=text.toByteArray(Charsets.UTF_8)
            val position=String(bytes,Charsets.US_ASCII).indexOf(marker)
            check(position>=0)
            val datagram=DatagramPacket(bytes,bytes.size,InetAddress.getLoopbackAddress(),port)
            stampJsonLong(bytes,position,SystemClock.elapsedRealtimeNanos())
            socket.send(datagram)
        } catch (_: SocketTimeoutException) { /* bounds shutdown latency even if no requests arrive */ }
        catch (error: Throwable) {
            if(active) failure.compareAndSet(null,error)
            break
        }
    }

    init {
        try { check(started.await(1,TimeUnit.SECONDS)) { "Clock source responder did not start" } }
        catch(error: Throwable) { close(); throw error }
    }

    fun send(json: JSONObject) {
        send(json.toString().toByteArray(Charsets.UTF_8))
    }

    fun send(bytes: ByteArray) {
        checkHealthy()
        socket.send(DatagramPacket(bytes,bytes.size,InetAddress.getLoopbackAddress(),port))
    }

    /** Normal protocol recovery, bounded in time; rejected RTTs remain recorded and are never relaxed. */
    fun awaitVerifiedClock(timeoutMs: Long=8_000) {
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        var nextProbe=0L
        while(SystemClock.elapsedRealtime()<deadline) {
            checkHealthy()
            val now=SystemClock.elapsedRealtime()
            if(now>=nextProbe) {
                // Before synchronization, this is only a bootstrap; it contains no action samples.
                send(JSONObject().put("v",2).put("type","motion").put("token",token))
                nextProbe=now+2_000
            }
            val stats=hub.diagnostics().bridgeStatistics
            if(stats!=null && stats.acceptedClockExchanges>0 && stats.synchronizedClockCount==1) return
            Thread.sleep(10)
        }
        error("No currently valid clock within ${timeoutMs}ms; sourceRequests=${requests.get()}; diagnostics=${hub.diagnostics()}")
    }

    private fun checkHealthy() { failure.get()?.let { throw AssertionError("UDP clock responder failed",it) } }
    override fun close() {
        active=false; socket.close(); responder.interrupt(); responder.join(1_000)
        check(!responder.isAlive) { "Clock responder did not stop within one second" }
        checkHealthy()
    }
}
