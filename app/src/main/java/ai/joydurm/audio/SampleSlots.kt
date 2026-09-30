package ai.joydurm.audio

/** Keeps the last playable sound until its replacement has actually decoded. */
internal class SampleSlots {
    data class Completion(val key: String, val activated: Boolean, val unload: Int?)
    private val active=mutableMapOf<String,Int>()
    private val pending=mutableMapOf<Int,String>()
    private val latest=mutableMapOf<String,Int>()
    val loadedCount get()=active.size
    val pendingCount get()=pending.size
    fun register(key: String,id: Int) {
        require(id>0 && id !in pending && id !in active.values)
        pending[id]=key; latest[key]=id
    }
    fun complete(id: Int,success: Boolean): Completion? {
        val key=pending.remove(id) ?: return null
        if(!success || latest[key]!=id) return Completion(key,false,id)
        val old=active.put(key,id)
        return Completion(key,true,old)
    }
    fun id(key: String)=active[key]
    fun clear() { active.clear(); pending.clear(); latest.clear() }
}
