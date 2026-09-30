package ai.joydurm.ui

import android.content.Context
import ai.joydurm.core.*
import ai.joydurm.render.DrumScene
import org.json.JSONArray
import org.json.JSONObject

class SettingsStore(context: Context) {
    val prefs=context.getSharedPreferences("joydurm",Context.MODE_PRIVATE)
    private fun vec(v: Vec3)=JSONArray(listOf(v.x,v.y,v.z))
    private fun finite(value: Double): Double { require(value.isFinite()); return value }
    private fun readVec(a: JSONArray)=Vec3(finite(a.getDouble(0)),finite(a.getDouble(1)),finite(a.getDouble(2)))
    fun save(engine: DrumEngine) {
        val all=JSONObject()
        synchronized(engine) {
            engine.roles.forEach { (r,s) ->
                val o=JSONObject().put("device",s.device).put("axis",s.axis).put("sign",s.sign).put("threshold",s.stroke.threshold).put("cooldown",s.stroke.cooldownNs)
                o.put("neutral",JSONArray(listOf(s.neutral.pitch,s.neutral.roll,s.neutral.yaw)))
                s.calibration?.let { o.put("bias",vec(it.bias)).put("gravity",vec(it.gravity)).put("samples",it.samples) }
                o.put("targets",JSONArray(s.targets.map { JSONObject().put("drum",it.drum.name).put("yaw",it.yaw).put("pitch",it.pitch) }))
                all.put(r.name,o)
            }
        }
        prefs.edit().putString("roles",all.toString()).putFloat("hatRange",engine.hatRange.toFloat()).putFloat("hatSign",engine.hatSign.toFloat()).apply()
    }
    fun load(engine: DrumEngine) {
        val all=runCatching { JSONObject(prefs.getString("roles","{}")!!) }.getOrDefault(JSONObject())
        synchronized(engine) {
            engine.roles.forEach { (r,s) -> all.optJSONObject(r.name)?.let { o -> runCatching {
                val device=o.optString("device").takeIf { it.isNotBlank() && it!="null" }
                val axis=o.optInt("axis",0).coerceIn(0,2)
                val sign=if(finite(o.optDouble("sign",1.0))<0)-1.0 else 1.0
                val threshold=finite(o.optDouble("threshold",2.2)).coerceIn(0.2,30.0)
                val cooldown=o.optLong("cooldown",90_000_000).coerceIn(30_000_000,500_000_000)
                val neutral=o.optJSONArray("neutral")?.let { Attitude(finite(it.getDouble(0)),finite(it.getDouble(1)),finite(it.getDouble(2))) } ?: s.neutral
                val calibration=if(o.has("bias"))Calibration(readVec(o.getJSONArray("bias")),readVec(o.getJSONArray("gravity")),o.optInt("samples",100).coerceAtLeast(1)) else null
                val targets=o.optJSONArray("targets")?.let { list -> (0 until list.length()).map { i -> val t=list.getJSONObject(i); Target(Drum.valueOf(t.getString("drum")),finite(t.getDouble("yaw")),finite(t.getDouble("pitch"))) }.toMutableList() }
                s.device=device; s.axis=axis; s.sign=sign; s.stroke.threshold=threshold; s.stroke.cooldownNs=cooldown
                s.neutral=neutral; s.calibration=calibration; if(targets!=null)s.targets=targets
            } } }
            engine.hatRange=prefs.getFloat("hatRange",0.52f).takeIf { it.isFinite() }?.toDouble()?.coerceIn(0.1,1.5) ?: 0.52
            engine.hatSign=if((prefs.getFloat("hatSign",1f).takeIf { it.isFinite() } ?: 1f)<0)-1.0 else 1.0
        }
    }
    fun saveScene(scene: DrumScene) {
        val o=JSONObject().put("scale",scene.kitScale).put("yaw",scene.kitYaw)
        Drum.entries.filter { it!=Drum.CHICK }.forEach { d -> val p=scene.piecePosition(d); o.put(d.name,JSONArray(listOf(p.x,p.y,p.z))) }
        prefs.edit().putString("layout",o.toString()).apply()
    }
    fun loadScene(scene: DrumScene) {
        runCatching {
            val o=JSONObject(prefs.getString("layout","{}")!!)
            val scale=finite(o.optDouble("scale",1.0)).coerceIn(0.3,2.0).toFloat()
            val yaw=finite(o.optDouble("yaw",0.0)).coerceIn(-360.0,360.0).toFloat()
            val positions=Drum.entries.filter { it!=Drum.CHICK }.mapNotNull { d -> o.optJSONArray(d.name)?.let { a ->
                d to floatArrayOf(finite(a.getDouble(0)).coerceIn(-3.0,3.0).toFloat(),finite(a.getDouble(1)).coerceIn(0.0,3.0).toFloat(),finite(a.getDouble(2)).coerceIn(-3.0,3.0).toFloat())
            } }
            scene.kitScale=scale; scene.kitYaw=yaw
            positions.forEach { (d,p) -> scene.setPiecePosition(d,p[0],p[1],p[2]) }
        }
    }
}
