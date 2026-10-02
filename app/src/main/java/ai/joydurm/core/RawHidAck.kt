package ai.joydurm.core

/** Non-blocking ACK transaction machine. Reader feeds reports; a timer drives writes/retries. */
class RawHidAck(private val timeoutNs: Long = 1_000_000_000L, private val maxAttempts: Int = 3) {
    data class Transaction(val command: Int, val data: IntArray, val optional: Boolean = false)
    private val transactions = listOf(
        Transaction(0x40,intArrayOf(1)), Transaction(0x03,intArrayOf(0x30)),
        Transaction(0x30,intArrayOf(1),true), Transaction(0x10,intArrayOf(0x20,0x60,0,0,24),true))
    @Volatile private var index = 0
    private var attempts = 0; private var counter = 0
    private var deadline = 0L; private var awaiting = false
    @Volatile var failure: String? = null; private set
    @Volatile var scale = JoyConProtocol.ImuScale(); private set
    val ready get() = index >= transactions.size && failure == null

    @Synchronized fun nextWrite(nowNs: Long): ByteArray? {
        if (failure != null || ready) return null
        if (awaiting && nowNs < deadline) return null
        if (attempts >= maxAttempts) {
            if (!transactions[index].optional) { failure = "No matching ACK for 0x${transactions[index].command.toString(16)}"; return null }
            advance(); return nextWrite(nowNs)
        }
        val transaction = transactions[index]; attempts++; awaiting = true; deadline = nowNs + timeoutNs
        return JoyConProtocol.subcommand(counter++,transaction.command,*transaction.data).copyOf(49)
    }

    @Synchronized fun written(actual: Int, expected: Int) {
        if (actual != expected) failure = "Incomplete HID write ($actual/$expected)"
    }

    @Synchronized fun onReport(bytes: ByteArray): Boolean {
        if (failure != null || ready || !awaiting) return false
        val offset = if (bytes.firstOrNull()?.toInt()?.and(255) == 0xa1) 1 else 0
        if (bytes.size < offset + 15 || bytes[offset].toInt().and(255) != 0x21) return false
        val transaction = transactions[index]
        if (bytes[offset + 14].toInt().and(255) != transaction.command) return false
        if (transaction.command == 0x10) {
            if (bytes.size < offset + 20 + transaction.data[4]) return false
            if (transaction.data.indices.any { bytes[offset + 15 + it].toInt().and(255) != transaction.data[it] }) return false
        }
        if (bytes[offset + 13].toInt().and(0x80) == 0) {
            if (transaction.optional) advance() else failure = "Controller NACK for 0x${transaction.command.toString(16)}"
            return true
        }
        if (transaction.command == 0x10) {
            runCatching { JoyConProtocol.factoryScale(bytes.copyOfRange(offset + 20,offset + 44)) }
                .onSuccess { scale = it }
        }
        advance(); return true
    }

    private fun advance() { index++; attempts = 0; awaiting = false; deadline = 0 }
}
