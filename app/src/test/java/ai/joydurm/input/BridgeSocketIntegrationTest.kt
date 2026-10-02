package ai.joydurm.input

import ai.joydurm.core.InputHealth
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

/** Actual UDP datagrams, ephemeral endpoints and JSON serialization; deterministic injected clocks. */
class BridgeSocketIntegrationTest {
    @Test fun socketSyncAndBurstPreserveSixSamplesAndRejectDelayedReplay() {
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { app ->
            DatagramSocket(0,InetAddress.getLoopbackAddress()).use { bridge ->
                app.soTimeout=1000; bridge.soTimeout=1000
                val decoder=BridgePacketDecoder(TOKEN)
                fun send(bytes: ByteArray) = bridge.send(DatagramPacket(bytes,bytes.size,app.localAddress,app.localPort))
                fun receive(now: Long): BridgeDecodeResult {
                    val packet=DatagramPacket(ByteArray(8193),8193); app.receive(packet)
                    return decoder.receive(packet.data.copyOf(packet.length),"${packet.address.hostAddress}:${packet.port}",now)
                }
                send(motion(0,listOf(BASE+OFFSET)))
                val request=receive(BASE).syncRequests.single()
                val bytes=request.json.toByteArray()
                app.send(DatagramPacket(bytes,bytes.size,bridge.localAddress,bridge.localPort))
                val receivedSync=DatagramPacket(ByteArray(8193),8193); bridge.receive(receivedSync)
                assertEquals(app.localPort,receivedSync.port)
                send(syncReply(String(receivedSync.data,0,receivedSync.length))); receive(BASE)
                val times=(0..5).map { BASE+OFFSET-25_000_000L+it*5_000_000L }
                send(motion(0,times.take(3))); send(motion(1,times.drop(3)))
                receive(BASE); receive(BASE+1_000_000L)
                val frames=decoder.poll(BASE+21_000_000L).batches.flatMap { it.frames }
                val health=InputHealth(); assertEquals(6,frames.count { health.accept(it.sample,BASE+21_000_000L) })
                assertEquals(times,frames.map { it.sourceTimeNs })
                send(motion(2,listOf(BASE+OFFSET-500_000_000L))); receive(BASE+25_000_000L)
                assertTrue(decoder.poll(BASE+50_000_000L).batches.isEmpty())
                assertEquals(6L,decoder.statistics().deliveredSamples)
            }
        }
    }
}
