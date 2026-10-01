package ai.joydurm

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.joydurm.core.*
import ai.joydurm.ui.SettingsStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNeutralMigrationTest {
    @Test fun legacyNonzeroNeutralIsDiscardedAndNewSaveHasNoSessionOrigin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("joydurm", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("roles", """{"LEFT_HAND":{"device":"physical-test-controller","axis":1,"sign":-1.0,"neutral":[0.7,-0.4,1.0],"bias":[0.01,0.02,0.03],"gravity":[0,0,9.80665],"samples":400}}""").commit()
        try {
            val engine = DrumEngine {}
            val store = SettingsStore(context)
            store.load(engine)
            val state = engine.roles.getValue(Role.LEFT_HAND)
            assertEquals("physical-test-controller", state.device)
            assertEquals(1, state.axis)
            assertEquals(-1.0, state.sign, 0.0)
            assertNotNull(state.calibration)
            assertNull(state.latest)
            assertNull(state.neutralEpoch)
            assertEquals(Quaternion(), state.neutralQuaternion)
            assertTrue(state.needsRecenter)
            assertEquals(OrientationStatus.NEEDS_RECENTER, engine.snapshot().roles.getValue(Role.LEFT_HAND).status)
            store.save(engine.snapshot())
            val stored = JSONObject(preferences.getString("roles", "{}")!!).getJSONObject("LEFT_HAND")
            assertFalse(stored.has("neutral"))
            assertFalse(stored.has("neutralQuaternion"))
            assertFalse(stored.has("neutralEpoch"))
            assertEquals(2, preferences.getInt("settingsSchema", 0))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
