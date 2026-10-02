package ai.joydurm

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.joydurm.ui.MainActivity
import ai.joydurm.ui.StagePage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Real native navigation and negative paths; no controller samples or AR tracking are fabricated. */
@RunWith(AndroidJUnit4::class)
class StageFlowIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("joydurm", Context.MODE_PRIVATE)

    @Before fun prepare() {
        preferences.edit().clear().putBoolean("onboarded", true).putBoolean("bridge", false).commit()
        instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        instrumentation.uiAutomation.executeShellCommand(
            "pm clear-permission-flags ${context.packageName} android.permission.CAMERA user-set user-fixed"
        ).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun setupBackNavigationAndMissingImuRemainHonestBeforeTouchPlaying() {
        preferences.edit().putBoolean("onboarded", false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitPage(scenario, StagePage.WELCOME)
            screenshot(StagePage.WELCOME)
            click(scenario, "start-setup")
            awaitPage(scenario, StagePage.CONNECT)
            awaitText(scenario, "运动手柄 0 / 4")
            awaitText(scenario, "未分配运动设备")
            assertNoText(scenario, "已连接 ✓")
            screenshot(StagePage.CONNECT)
            back()
            awaitPage(scenario, StagePage.WELCOME)
            click(scenario, "start-setup")

            val transitions = listOf(
                Triple(StagePage.CONNECT, "continue-CONNECT", StagePage.ROLES),
                Triple(StagePage.ROLES, "continue-ROLES", StagePage.HANDS),
                Triple(StagePage.HANDS, "continue-HANDS", StagePage.HAT),
                Triple(StagePage.HAT, "continue-HAT", StagePage.PLACE),
                Triple(StagePage.PLACE, "continue-PLACE", StagePage.SOUND_CHECK),
            )
            transitions.forEach { (previous, control, next) ->
                click(scenario, control)
                awaitPage(scenario, next)
                back()
                awaitPage(scenario, previous)
                click(scenario, control)
                awaitPage(scenario, next)
                when (next) {
                    StagePage.ROLES -> assertNoText(scenario, "已连接 ✓")
                    StagePage.HANDS -> {
                        awaitText(scenario, "等待运动输入")
                        listOf("LEFT_HAND", "RIGHT_HAND").forEach { role ->
                            assertDisabled(scenario, "calibrate-$role")
                            assertDisabled(scenario, "recenter-$role")
                            assertDisabled(scenario, "bind-target-$role")
                        }
                        assertNoText(scenario, "已校准 ·")
                        assertNoText(scenario, "已归中 ·")
                    }
                    StagePage.HAT -> {
                        assertDisabled(scenario, "calibrate-LEFT_FOOT")
                        assertDisabled(scenario, "capture-hat-closed")
                        assertDisabled(scenario, "capture-hat-open")
                        awaitText(scenario, "尚未记录闭合姿势")
                        awaitText(scenario, "尚未完成开合标定")
                        assertNoText(scenario, "✓ 已记录闭合姿势")
                        assertNoText(scenario, "✓ 开合范围已标定")
                    }
                    else -> Unit
                }
                screenshot(next)
            }
            assertFalse("Visiting setup must not mark it complete", preferences.getBoolean("onboarded", true))
            click(scenario, "军鼓")
            awaitText(scenario, "军鼓 · 力度")
            click(scenario, "complete-onboarding")
            awaitPage(scenario, StagePage.PLAY)
            assertTrue(preferences.getBoolean("onboarded", false))
            click(scenario, "地鼓")
            awaitText(scenario, "地鼓 · 力度")
            screenshot(StagePage.PLAY)
            // Capture actual sustained touch playing, so a slow compositor still sees
            // a current hit instead of the previous frame after the flash has expired.
            scenario.onActivity { activity ->
                val pad=views(activity.window.decorView).filterIsInstance<Button>().first { it.text=="军鼓" }
                val handler=android.os.Handler(android.os.Looper.getMainLooper())
                repeat(8) { index -> handler.postDelayed({ pad.performClick() },index*160L) }
            }
            awaitText(scenario, "军鼓 · 力度")
            screenshot(StagePage.PLAY, "play-hit")
            android.os.SystemClock.sleep(1200)
        }
    }

    @Test fun welcomeCanSkipToSoundCheckAndReturnToTouchPlaying() {
        preferences.edit().putBoolean("onboarded", false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitPage(scenario, StagePage.WELCOME)
            click(scenario, "skip-to-touch")
            awaitPage(scenario, StagePage.SOUND_CHECK)
            assertFalse(preferences.getBoolean("onboarded", true))
            click(scenario, "军鼓")
            awaitText(scenario, "军鼓 · 力度")
            click(scenario, "complete-onboarding")
            awaitPage(scenario, StagePage.PLAY)
            assertTrue(preferences.getBoolean("onboarded", false))
            scenario.recreate()
            awaitPage(scenario, StagePage.PLAY)
            click(scenario, "军鼓")
            awaitText(scenario, "军鼓 · 力度")
        }
    }

    @Test fun denyingCameraKeepsPlayPageAndNativeTouchPadsAvailable() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitPage(scenario, StagePage.PLAY)
            click(scenario, "AR 相机")
            assertTrue("Android camera permission dialog must expose a deny action", waitUntil {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitUntil false
                val deny = accessibilityNodes(root).firstOrNull {
                    it.viewIdResourceName?.endsWith("/permission_deny_button") == true
                }
                deny?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            })
            awaitText(scenario, "摄像头权限未授予")
            awaitPage(scenario, StagePage.PLAY)
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
            click(scenario, "军鼓")
            awaitText(scenario, "军鼓 · 力度")
            screenshot(StagePage.PLAY)
        }
    }

    @Test fun devicesKitAndSettingsPreserveAudioControlsAcrossRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitPage(scenario, StagePage.PLAY)
            click(scenario, "设备")
            awaitPage(scenario, StagePage.DEVICES)
            awaitText(scenario, "0 个运动输入设备")
            awaitText(scenario, "尚未绑定设备")
            assertNoText(scenario, "● 实时数据")
            screenshot(StagePage.DEVICES)
            click(scenario, "打开鼓组页面")
            awaitPage(scenario, StagePage.KIT)
            click(scenario, "选择 Electronic")
            awaitText(scenario, "已选择 Electronic")
            assertEquals(1, preferences.getInt("kit", -1))
            screenshot(StagePage.KIT)
            click(scenario, "打开设置页面")
            awaitPage(scenario, StagePage.SETTINGS)
            setVolume(scenario, 63)
            editTempo(scenario, "123")
            click(scenario, "设置 BPM")
            assertTrue("Volume and BPM must reach the persisted audio settings", waitUntil {
                preferences.getInt("bpm", -1) == 123 &&
                    kotlin.math.abs(preferences.getFloat("volume", -1f) - 0.63f) < 0.001f
            })
            click(scenario, "开启节拍器")
            awaitText(scenario, "停止节拍器")
            click(scenario, "停止节拍器")
            awaitText(scenario, "开启节拍器")
            screenshot(StagePage.SETTINGS)
            click(scenario, "返回演奏")
            awaitPage(scenario, StagePage.PLAY)
            click(scenario, "军鼓")
            awaitText(scenario, "军鼓 · 力度")
            scenario.recreate()
            awaitPage(scenario, StagePage.PLAY)
            click(scenario, "设置")
            awaitPage(scenario, StagePage.SETTINGS)
            scenario.onActivity { activity ->
                val volume = views(activity.window.decorView).filterIsInstance<SeekBar>()
                    .single { it.contentDescription?.toString() == "主音量" }
                val tempo = views(activity.window.decorView).filterIsInstance<EditText>()
                    .single { it.contentDescription?.toString() == "节拍器 BPM" }
                assertEquals(63, volume.progress)
                assertEquals("123", tempo.text.toString())
            }
            back()
            awaitPage(scenario, StagePage.PLAY)
            click(scenario, "军鼓")
            awaitText(scenario, "军鼓 · 力度")
        }
    }

    private fun awaitPage(scenario: ActivityScenario<MainActivity>, page: StagePage) {
        val marker = "screen-${page.name}"
        assertTrue("Expected native page $marker", waitUntil {
            var found = false
            scenario.onActivity { activity ->
                found = views(activity.window.decorView).any {
                    it.isShown && (it.tag == marker || it.contentDescription?.toString() == marker)
                }
            }
            found
        })
    }

    private fun click(scenario: ActivityScenario<MainActivity>, label: String) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val candidates = views(activity.window.decorView).filter {
                it.isShown && it.isClickable &&
                    ((it is TextView && it.text.toString() == label) || it.contentDescription?.toString() == label)
            }
            assertEquals("Expected one native control: $label", 1, candidates.size)
            val control = candidates.single()
            assertTrue("Disabled native control: $label", control.isEnabled)
            control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
            assertTrue("Native control did not handle click: $label", control.performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertDisabled(scenario: ActivityScenario<MainActivity>, description: String) {
        scenario.onActivity { activity ->
            val controls = views(activity.window.decorView).filterIsInstance<Button>().filter {
                it.isShown && it.contentDescription?.toString() == description
            }
            assertEquals("Expected one calibration control: $description", 1, controls.size)
            assertFalse("Missing IMU must not enable $description", controls.single().isEnabled)
        }
    }

    private fun setVolume(scenario: ActivityScenario<MainActivity>, percent: Int) {
        scenario.onActivity { activity ->
            val volume = views(activity.window.decorView).filterIsInstance<SeekBar>()
                .single { it.contentDescription?.toString() == "主音量" }
            volume.requestRectangleOnScreen(Rect(0, 0, volume.width, volume.height), true)
            // Accessibility progress is a user action and exercises the fromUser listener.
            val arguments = Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, percent.toFloat())
            }
            assertTrue("Volume slider must accept a user progress action", volume.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments
            ))
        }
        instrumentation.waitForIdleSync()
        awaitText(scenario, "主音量 · $percent%")
    }

    private fun editTempo(scenario: ActivityScenario<MainActivity>, value: String) {
        scenario.onActivity { activity ->
            val tempo = views(activity.window.decorView).filterIsInstance<EditText>()
                .single { it.contentDescription?.toString() == "节拍器 BPM" }
            tempo.requestRectangleOnScreen(Rect(0, 0, tempo.width, tempo.height), true)
            assertTrue(tempo.requestFocus())
            tempo.setText(value)
        }
    }

    private fun back() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
    }

    private fun awaitText(scenario: ActivityScenario<MainActivity>, text: String) {
        assertTrue("Expected native status: $text", waitUntil {
            var found = false
            scenario.onActivity { activity ->
                found = texts(activity.window.decorView).any { it.contains(text) }
            }
            found
        })
    }

    private fun assertNoText(scenario: ActivityScenario<MainActivity>, text: String) {
        scenario.onActivity { activity ->
            assertFalse("Unexpected success status: $text", texts(activity.window.decorView).any { it.contains(text) })
        }
    }

    private fun screenshot(page: StagePage, name: String=page.name.lowercase(Locale.ROOT), settleMs: Long=600) {
        instrumentation.waitForIdleSync()
        // Window/SurfaceView composition runs after the view tree becomes idle.
        // Retain actual display pixels, including the real hit pulse on the same page.
        android.os.SystemClock.sleep(settleMs)
        var bitmap: Bitmap?=null
        val scenePage=page in listOf(StagePage.WELCOME,StagePage.PLACE,StagePage.SOUND_CHECK,StagePage.PLAY)
        val renderProbe=if(scenePage && name!="play-hit")RenderProbe.attach() else null
        var lastWarm=0
        val visible=waitUntil {
            bitmap?.recycle()
            bitmap=instrumentation.uiAutomation.takeScreenshot()
            val frame=bitmap ?: return@waitUntil false
            if(!scenePage || name=="play-hit")return@waitUntil true
            // The center excludes controller art, controls and Android system bars.
            var warm=0
            for(y in frame.height*30/100 until frame.height*65/100 step 4)
                for(x in frame.width/20 until frame.width*19/20 step 4) {
                    val p=frame.getPixel(x,y)
                    val r=(p shr 16) and 255; val g=(p shr 8) and 255; val b=p and 255
                    if(r>=60 && g>=30 && r>=b+25 && g>=b+8 && r>g)warm++
                }
            lastWarm=warm
            warm>=64
        }
        try {
            assertNotNull("No composited display screenshot for ${page.name}", bitmap)
            val report = File(context.filesDir, "test-reports").apply { mkdirs() }
            val outputName=if(visible) "ui-$name.png" else "ui-failed-${page.name.lowercase(Locale.ROOT)}.png"
            File(report, outputName).outputStream().use {
                assertTrue(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            renderProbe?.capture(report,"ui-${page.name.lowercase(Locale.ROOT)}")
        } finally { renderProbe?.close(); bitmap?.recycle() }
        assertTrue("Composited kit must finish loading for ${page.name}; lastWarm=$lastWarm required=64", visible)
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun texts(root: View): List<String> = views(root).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text.toString() }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else emptyList()

    private fun accessibilityNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap(::accessibilityNodes)
}
