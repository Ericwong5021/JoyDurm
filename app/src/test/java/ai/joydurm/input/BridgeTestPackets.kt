package ai.joydurm.input

import org.json.JSONArray
import org.json.JSONObject

    const val TOKEN = "0123456789abcdef0123456789abcdef"
    const val ENDPOINT = "127.0.0.1:41234"
    const val SESSION = "4acbfba3-9036-4c46-9a77-58f8c50fb346"
    const val NEW_SESSION = "08c66f68-d0b0-4b0b-9f26-f25e72d6e399"
    const val BASE = 10_000_000_000L
    const val OFFSET = 40_000_000_000L
    fun motion(seq: Long, times: List<Long>, session: String=SESSION, stable: Boolean=true): ByteArray = JSONObject()
        .put("v",2).put("token",TOKEN).put("device","serial-unit-1").put("name","Joy-Con L")
        .put("identityStable",stable).put("identitySource",if(stable) "serial" else "simulator")
        .put("vendorId",0x057e).put("productId",0x2006).put("role","LEFT_HAND")
        .put("sessionId",session).put("seq",seq).put("sourceReadNs",times.last())
        .put("timer",(seq*3).and(255)).put("samples",JSONArray().also { array -> times.forEach { time ->
            array.put(JSONObject().put("sourceTimeNs",time).put("ax",0.0).put("ay",0.0).put("az",9.80665)
                .put("gx",0.0).put("gy",0.0).put("gz",0.0))
        } }).toString().toByteArray()
    fun syncReply(request: String, sourceOffset: Long=OFFSET): ByteArray {
        val r = JSONObject(request); val send=r.getLong("clientSendNs")
        return JSONObject().put("v",2).put("type","sync_reply").put("token",TOKEN)
            .put("nonce",r.getString("nonce")).put("clientSendNs",send)
            .put("sourceReceiveNs",send+sourceOffset).put("sourceSendNs",send+sourceOffset).toString().toByteArray()
    }
    fun synchronize(decoder: BridgePacketDecoder, now: Long=BASE, endpoint: String=ENDPOINT) {
        val request=decoder.receive(motion(0,listOf(now+OFFSET)),endpoint,now).syncRequests.single()
        decoder.receive(syncReply(request.json),endpoint,now)
    }
