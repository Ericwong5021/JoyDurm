package ai.joydurm

import org.json.JSONObject

/** Encode test setup before sampling; stamp only real source times on the sending path. */
internal class PreparedMotionPacket(json: JSONObject) {
    private val samples = json.getJSONArray("samples").length()
    private val bytes: ByteArray
    private val positions: List<Int>

    init {
        json.put("sourceReadNs", Long.MAX_VALUE)
        repeat(samples) { index ->
            json.getJSONArray("samples").getJSONObject(index).put("sourceTimeNs", Long.MAX_VALUE - index - 1)
        }
        val text = json.toString()
        bytes = text.toByteArray(Charsets.UTF_8)
        // All headers in these fixtures are ASCII. Locate byte offsets, not UTF-16 indices.
        val encoded = String(bytes, Charsets.US_ASCII)
        positions = (0..samples).map { index ->
            val marker = (Long.MAX_VALUE - index).toString()
            encoded.indexOf(marker).also { check(it >= 0 && encoded.indexOf(marker, it + 1) < 0) }
        }
    }

    fun stamp(sourceReadNs: Long, offsetsNs: LongArray): ByteArray {
        require(sourceReadNs >= 0 && offsetsNs.size == samples)
        positions.forEachIndexed { index, position ->
            val value = if (index == 0) sourceReadNs else sourceReadNs + offsetsNs[index - 1]
            require(value in 0..sourceReadNs)
            // JSON permits whitespace before a number. Fixed-width fields avoid JSON
            // allocation/serialization after acquisition without inventing arrival times.
            bytes.fill(' '.code.toByte(), position, position + 19)
            val number = value.toString().toByteArray(Charsets.US_ASCII)
            number.copyInto(bytes, position + 19 - number.size)
        }
        return bytes
    }
}
