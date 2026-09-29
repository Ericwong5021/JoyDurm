package ai.joydurm.ui

import android.content.Context
import ai.joydurm.core.*
import ai.joydurm.render.DrumScene
import org.json.JSONArray
import org.json.JSONObject

class SettingsStore(context: Context) {
    val prefs=context.getSharedPreferences("joydurm",Context.MODE_PRIVATE)
    private fun vec(v: Vec3)=JSONArray(listOf(v.x,v.y,v.z))
    private fun readVec(a: JSONArray)=Vec3(a.getDouble(0),a.getDouble(1),a.getDouble(2))
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
        runCatching {
            val all=JSONObject(prefs.getString("roles","{}")!!)
            engine.roles.forEach { (r,s) -> all.optJSONObject(r.name)?.let { o ->
                s.device=o.optString("device").takeIf { it.isNotBlank() && it!="null" }
                s.axis=o.optInt("axis",0).coerceIn(0,2); s.sign=if(o.optDouble("sign",1.0)<0)-1.0 else 1.0
                s.stroke.threshold=o.optDouble("threshold",2.2).coerceIn(0.2,30.0)
                s.stroke.cooldownNs=o.optLong("cooldown",90_000_000).coerceIn(30_000_000,500_000_000)
                o.optJSONArray("neutral")?.let { s.neutral=Attitude(it.getDouble(0),it.getDouble(1),it.getDouble(2)) }
                if(o.has("bias")) s.calibration=Calibration(readVec(o.getJSONArray("bias")),readVec(o.getJSONArray("gravity")),o.optInt("samples",100))
                o.optJSONArray("targets")?.let { list -> s.targets=(0 until list.length()).mapNotNull { i -> runCatching { val t=list.getJSONObject(i); Target(Drum.valueOf(t.getString("drum")),t.getDouble("yaw"),t.getDouble("pitch")) }.getOrNull() }.toMutableList() }
            } }
            engine.hatRange=prefs.getFloat("hatRange",0.52f).toDouble().coerceIn(0.1,1.5)
            engine.hatSign=prefs.getFloat("hatSign",1f).toDouble()
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
            scene.kitScale=o.optDouble("scale",1.0).toFloat().coerceIn(0.3f,2f)
            scene.kitYaw=o.optDouble("yaw",0.0).toFloat()
            Drum.entries.forEach { d -> o.optJSONArray(d.name)?.let { scene.setPiecePosition(d,it.getDouble(0).toFloat(),it.getDouble(1).toFloat(),it.getDouble(2).toFloat()) } }
        }
    }
}
