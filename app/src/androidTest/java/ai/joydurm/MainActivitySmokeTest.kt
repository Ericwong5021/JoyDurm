package ai.joydurm

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.joydurm.ui.MainActivity
import io.github.sceneview.SceneView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Emulator smoke coverage; it does not measure audible latency, controllers or AR tracking. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun prepare() {
        context.getSharedPreferences("joydurm", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarded", true).putBoolean("bridge", false).commit()
        instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        instrumentation.uiAutomation.executeShellCommand("pm clear-permission-flags ${context.packageName} android.permission.CAMERA user-set user-fixed")
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
    }

    @Test fun launchWithoutCameraPermissionAndTouchPadRemainsPlayable() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click(scenario, "军鼓")
            awaitText(scenario, "军鼓 · 力度")
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isShown)
                assertEquals(PackageManager.PERMISSION_DENIED, activity.checkSelfPermission(Manifest.permission.CAMERA))
            }
        }
    }

    @Test fun ordinary3DKitIsVisibleInCompositedScreen() {
        // Use the authored kit, independently of layouts or imports saved by other tests.
        context.getSharedPreferences("joydurm",Context.MODE_PRIVATE).edit()
            .remove("layout").remove("customModel").remove("modelPath").commit()
        val reports=File(context.filesDir,"test-reports").apply { mkdirs() }
        val screenFile=File(reports,"ordinary-3d-screen.png").apply { delete() }
        val metricsFile=File(reports,"ordinary-3d-visibility.json").apply { delete() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var diagnostic="Scene has not produced a screenshot"
            val renderProbe=RenderProbe.attach()
            var lastScreenshot: Bitmap?=null
            val metrics=JSONObject()
                .put("runId",InstrumentationRegistry.getArguments().getString("joydurmRunId") ?: "manual")
                .put("test","ordinary3DKitIsVisibleInCompositedScreen")
                .put("abi",android.os.Build.SUPPORTED_ABIS.firstOrNull())
                .put("api",android.os.Build.VERSION.SDK_INT)
            try {
                val visible=waitUntil {
                    var bounds: Rect?=null
                    scenario.onActivity { activity ->
                        val scene=views(activity.window.decorView).filterIsInstance<SceneView>().singleOrNull()
                        if(scene!=null && scene.isShown && scene.width>0 && scene.height>0) {
                            val location=IntArray(2); scene.getLocationOnScreen(location)
                            // The scene now fills the screen behind the HUD. Its controller
                            // illustrations are warm too, so sample only the unobstructed center
                            // between the top status strip and the bottom controls.
                            val inset=scene.width/20
                            bounds=Rect(location[0]+inset,location[1]+scene.height*30/100,
                                location[0]+scene.width-inset,location[1]+scene.height*65/100)
                            diagnostic="scene=$bounds viewport=${scene.view.viewport.width}x${scene.view.viewport.height}"
                            metrics.put("sceneBounds",bounds.toString())
                                .put("viewportWidth",scene.view.viewport.width).put("viewportHeight",scene.view.viewport.height)
                                .put("surfaceFrame",scene.holder.surfaceFrame.toString())
                                .put("surfaceValid",scene.holder.surface.isValid).put("readyToRender",scene.uiHelper.isReadyToRender)
                                .put("renderables",scene.scene.renderableCount).put("lights",scene.scene.lightCount)
                                .put("dynamicResolution",scene.view.dynamicResolutionOptions.enabled)
                                .put("hdrColorBuffer",scene.view.renderQuality.hdrColorBuffer.toString())
                        }
                    }
                    val region=bounds ?: return@waitUntil false
                    // A Surface-only PixelCopy can see geometry hidden behind an opaque View background.
                    // UiAutomation captures the actual composed display, matching what the user sees.
                    val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    lastScreenshot?.recycle(); lastScreenshot=bitmap
                    if(!region.intersect(0,0,bitmap.width,bitmap.height) || region.isEmpty) return@waitUntil false
                    val pixels=IntArray(region.width()*region.height())
                    bitmap.getPixels(pixels,0,region.width(),region.left,region.top,region.width(),region.height())
                    var warm=0; var sampled=0
                    var sumR=0L; var sumG=0L; var sumB=0L; var brightest=0
                    for(y in 0 until region.height() step 2) for(x in 0 until region.width() step 2) {
                        val pixel=pixels[y*region.width()+x]
                        val r=(pixel shr 16) and 255; val g=(pixel shr 8) and 255; val b=pixel and 255
                        // The built-in coral shells and brass cymbals are warm; the empty dark
                        // viewport and central gray/white UI cannot satisfy this mask. The HUD
                        // controller artwork is outside the sample region above.
                        if(r>=60 && g>=30 && r>=b+25 && g>=b+8 && r>g)warm++
                        sumR+=r; sumG+=g; sumB+=b; brightest=maxOf(brightest,r,g,b)
                        sampled++
                    }
                    val required=maxOf(64,sampled/1000)
                    diagnostic+=" warmModelPixels=$warm required=$required sampled=$sampled"
                    metrics.put("screenshotWidth",bitmap.width).put("screenshotHeight",bitmap.height)
                        .put("sampleBounds",region.toString()).put("warmModelPixels",warm)
                        .put("required",required).put("sampled",sampled).put("brightestChannel",brightest)
                        .put("meanR",sumR.toDouble()/sampled).put("meanG",sumG.toDouble()/sampled).put("meanB",sumB.toDouble()/sampled)
                    warm>=required
                }
                // Export the final actual display on either outcome, before the Activity closes.
                lastScreenshot?.let { bitmap -> screenFile.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
                metrics.put("visible",visible).put("diagnostic",diagnostic)
                metricsFile.writeText(metrics.toString(2))
                renderProbe?.capture(reports,"ordinary-3d")
                assertTrue("3D kit is not visible in the displayed scene: $diagnostic",visible)
            } finally { renderProbe?.close(); lastScreenshot?.recycle() }
        }
    }

    @Test fun deniedArCameraPermissionKeepsOrdinary3DAndTouchPadsAvailable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click(scenario, "AR 相机")
            val denied = waitUntil {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitUntil false
                val button = accessibilityNodes(root).firstOrNull {
                    it.viewIdResourceName?.endsWith("/permission_deny_button") == true
                }
                button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            assertTrue("Android permission dialog did not expose the deny action", denied)
            awaitText(scenario, "摄像头权限未授予")
            click(scenario, "军鼓")
            awaitText(scenario, "军鼓 · 力度")
        }
    }

    @Test fun pauseResumeAndRecreationRetainSettingsAndInputUi() {
        context.getSharedPreferences("joydurm", Context.MODE_PRIVATE).edit().putInt("bpm", 123).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(3) {
                scenario.moveToState(Lifecycle.State.CREATED)
                assertEquals(Lifecycle.State.CREATED, scenario.state)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                click(scenario, "军鼓")
                awaitText(scenario, "军鼓 · 力度")
            }
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertEquals(123, context.getSharedPreferences("joydurm", Context.MODE_PRIVATE).getInt("bpm", 0))
            click(scenario, "军鼓")
            awaitText(scenario, "军鼓 · 力度")
        }
    }

    private fun click(scenario: ActivityScenario<MainActivity>, label: String) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val button = views(activity.window.decorView).filterIsInstance<Button>().firstOrNull { it.text.toString() == label }
            assertNotNull("Missing UI button: $label", button)
            assertTrue(button!!.performClick())
        }
    }

    private fun awaitText(scenario: ActivityScenario<MainActivity>, text: String) {
        assertTrue("Expected visible status: $text", waitUntil {
            var found = false
            scenario.onActivity { activity -> found = views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains(text) } }
            found
        })
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else emptyList()

    private fun accessibilityNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap(::accessibilityNodes)
}
