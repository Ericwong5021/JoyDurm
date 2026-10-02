package ai.joydurm

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.joydurm.core.Role
import ai.joydurm.core.EngineExecutor
import ai.joydurm.input.ControllerHub
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import ai.joydurm.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Injected radio results exercise UI state only; no Bluetooth/IMU hardware pass is claimed. */
@RunWith(AndroidJUnit4::class)
class BluetoothAssignmentIntegrationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val prefs get()=context.getSharedPreferences("joydurm",Context.MODE_PRIVATE)
    private val left=BluetoothChoice("11:22:33:44:55:AA","Joy-Con (L) −",BluetoothDevice.BOND_NONE)
    private val right=BluetoothChoice("11:22:33:44:55:BB","Joy-Con (R) +",BluetoothDevice.BOND_BONDED)
    private class Radio(var state: BluetoothInventory): BluetoothPlatform {
        var callback: (() -> Unit)?=null; var pairError: String?=null
        val requests=mutableListOf<String>(); var searches=0; var closes=0
        override fun inventory()=state
        override fun watch(changed: () -> Unit) { callback=changed }
        override fun discover(): String? { searches++; state=state.copy(scanning=true); callback?.invoke(); return null }
        override fun pair(address: String): String? { requests+=address; return pairError }
        override fun close() { closes++; callback=null }
        fun update(value: BluetoothInventory) { state=value; callback?.invoke() }
    }
    @Before fun prepare() {
        prefs.edit().clear().putBoolean("onboarded",true).putBoolean("bridge",false).commit()
        if(Build.VERSION.SDK_INT>=31) {
            instrumentation.uiAutomation.revokeRuntimePermission(context.packageName,Manifest.permission.BLUETOOTH_CONNECT)
            instrumentation.uiAutomation.revokeRuntimePermission(context.packageName,Manifest.permission.BLUETOOTH_SCAN)
            listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN).forEach { permission ->
                instrumentation.uiAutomation.executeShellCommand("pm clear-permission-flags ${context.packageName} $permission user-set user-fixed").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
            }
        }
    }
    @Test fun actualZeroDeviceClickShowsOwnedPickerAndDeniedPermissionFeedback() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            clickActivity(scenario,"设备"); clickActivity(scenario,"分配左手设备")
            awaitPicker(scenario)
            assertVisible(scenario,"附近设备权限未授予")
            clickPicker(scenario,"授权附近设备")
            assertTrue(waitUntil {
                val root=instrumentation.uiAutomation.rootInActiveWindow ?: return@waitUntil false
                nodes(root).firstOrNull { it.viewIdResourceName?.endsWith("/permission_deny_button")==true }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
            })
            assertVisible(scenario,"附近设备权限未授予")
            scenario.onActivity { activity ->
                val original=picker(activity)
                activity.showRolePicker(Role.RIGHT_HAND,AndroidBluetoothPlatform(activity))
                assertSame("Repeated role commands must retain one picker",original,picker(activity))
            }
            screenshot("assignment-permission-denied")
            dismiss(scenario)
            assertNull(prefs.getString("bluetoothRoles",null))
            clickActivity(scenario,"分配左手设备"); awaitPicker(scenario); dismiss(scenario)
        }
    }
    @Test fun realAddressSelectionPairFailureTransferCancelAndRestartRemainHonest() {
        val radio=Radio(BluetoothInventory(true,true,true,false,listOf(left,right)))
        radio.pairError="系统未能开始配对，请重试或打开系统蓝牙。"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            clickActivity(scenario,"设备")
            scenario.onActivity { it.showRolePicker(Role.LEFT_HAND,radio) }; awaitPicker(scenario)
            clickPicker(scenario,left.name)
            assertEquals(listOf(left.address),radio.requests)
            assertVisible(scenario,"系统未能开始配对")
            assertTrue(prefs.getString("bluetoothRoles","")!!.contains(left.address))
            assertTrue(waitUntil { prefs.getString("roles","")!!.contains("bluetooth:${left.address}") })
            scenario.onActivity { activity ->
                val ignored=Radio(radio.state); val original=picker(activity)
                activity.showRolePicker(Role.RIGHT_HAND,ignored)
                assertSame(original,picker(activity)); assertEquals(1,ignored.closes)
            }
            dismiss(scenario)
            assertEquals(1,radio.closes)
            scenario.recreate()
            assertActivityText(scenario,left.name)
            assertActivityText(scenario,"IMU 未就绪")
            assertDisabled(scenario,"校准左手"); assertDisabled(scenario,"归中左手")
            val next=Radio(radio.state)
            scenario.onActivity { it.showRolePicker(Role.RIGHT_HAND,next) }; awaitPicker(scenario)
            clickPicker(scenario,left.name)
            assertVisible(scenario,"此设备已分配给左手")
            assertTrue(next.requests.isEmpty())
            dismiss(scenario)
            assertTrue(prefs.getString("bluetoothRoles","")!!.contains("LEFT_HAND"))
            val transfer=Radio(radio.state)
            scenario.onActivity { it.showRolePicker(Role.RIGHT_HAND,transfer) }; awaitPicker(scenario)
            clickPicker(scenario,left.name); clickPicker(scenario,"确认转移到右手")
            assertTrue(prefs.getString("bluetoothRoles","")!!.contains("RIGHT_HAND"))
            assertFalse(prefs.getString("bluetoothRoles","")!!.contains("LEFT_HAND"))
            screenshot("assignment-address-selected-no-imu")
            dismiss(scenario)
            assertDisabled(scenario,"校准右手")
        }
    }
    @Test fun disabledEmptySearchAndPairingResultsRefreshOneWindow() {
        val radio=Radio(BluetoothInventory(true,true,false,false,emptyList()))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.showRolePicker(Role.LEFT_FOOT,radio) }; awaitPicker(scenario)
            assertVisible(scenario,"蓝牙已关闭")
            scenario.onActivity { radio.update(radio.state.copy(enabled=true)) }
            assertVisible(scenario,"暂无蓝牙设备")
            clickPicker(scenario,"搜索附近设备"); assertEquals(1,radio.searches)
            assertVisible(scenario,"正在搜索附近")
            scenario.onActivity { radio.update(radio.state.copy(devices=listOf(left))) }
            clickPicker(scenario,left.name)
            scenario.onActivity { radio.update(radio.state.copy(devices=listOf(left.copy(bond=BluetoothDevice.BOND_BONDING)),notice="正在配对 Joy-Con (L) −，请确认系统配对窗口。")) }
            assertVisible(scenario,"正在配对 Joy-Con")
            scenario.onActivity { radio.update(radio.state.copy(devices=listOf(left.copy(bond=BluetoothDevice.BOND_BONDED)),notice="Joy-Con (L) − 已配对；配对不代表 IMU 就绪。")) }
            assertVisible(scenario,"配对不代表 IMU 就绪")
            assertTrue(prefs.getString("roles","")!!.contains("bluetooth:${left.address}"))
            screenshot("assignment-test-fixture-paired-no-imu")
            dismiss(scenario)
            assertEquals(1,radio.closes)
        }
    }
    @Test fun verifiedUdpInputRequiresExplicitAssociationAndFreshMotion() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var hub: ControllerHub
            lateinit var engine: EngineExecutor
            scenario.onActivity { activity ->
                fun field(name: String): Any { val f=MainActivity::class.java.getDeclaredField(name); f.isAccessible=true; return f.get(activity) }
                hub=field("hub") as ControllerHub; engine=field("engineExecutor") as EngineExecutor
            }
            val token="assignment-regression-source-token"
            hub.listenBridgeOnAvailablePort(token)
            UdpClockTestSource(hub,hub.diagnostics().bridgePort!!,token).use { source ->
                source.awaitVerifiedClock()
                val session=UUID.randomUUID().toString()
                var seq=0L
                fun send() {
                    val frames=JSONArray()
                    repeat(3) { frames.put(JSONObject().put("sourceTimeNs",0L).put("ax",0.0).put("ay",0.0).put("az",9.80665).put("gx",0.0).put("gy",0.0).put("gz",0.0)) }
                    val packet=PreparedMotionPacket(JSONObject().put("v",2).put("type","motion").put("token",token)
                        .put("device","assignment-udp-simulator").put("name","assignment-udp-simulator").put("sessionId",session).put("identityStable",false)
                        .put("identitySource","simulator").put("seq",seq).put("timer",(seq++%256).toInt()).put("sourceReadNs",0L).put("samples",frames))
                    source.send(packet.stamp(android.os.SystemClock.elapsedRealtimeNanos(),longArrayOf(-10_000_000,-5_000_000,0)))
                }
                send(); val delivered=waitUntil { hub.devices.values.any { it.motion } }; assertTrue("UDP sample not delivered: ${hub.diagnostics()}",delivered)
                val candidate=hub.devices.values.single { it.name.contains("assignment-udp-simulator") }
                clickActivity(scenario,"设备")
                val radio=Radio(BluetoothInventory(true,true,true,false,listOf(left)))
                scenario.onActivity { it.showRolePicker(Role.LEFT_HAND,radio) }; awaitPicker(scenario)
                clickPicker(scenario,left.name)
                assertNull(engine.snapshot().roles.getValue(Role.LEFT_HAND).latestTimeNs)
                clickPicker(scenario,"关联控制器输入")
                scenario.onActivity { activity ->
                    val f=MainActivity::class.java.getDeclaredField("activeInputDialog"); f.isAccessible=true
                    val dialog=f.get(activity) as android.app.AlertDialog
                    val button=views(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.contains(candidate.id) }
                    assertTrue(button.performClick())
                }
                assertTrue(waitUntil { candidate.id==engine.snapshot().roles.getValue(Role.LEFT_HAND).device })
                send(); assertTrue(waitUntil { engine.snapshot().roles.getValue(Role.LEFT_HAND).latestTimeNs!=null })
                assertNull(engine.snapshot().roles.getValue(Role.LEFT_HAND).calibration)
                assertEquals("HARDWARE_PENDING",JSONObject(hub.capabilityReport()).getString("status"))
                // Same Bluetooth selection must not erase an explicitly confirmed input.
                scenario.onActivity { it.showRolePicker(Role.LEFT_HAND,Radio(radio.state)) }; awaitPicker(scenario)
                clickPicker(scenario,left.name)
                assertEquals(candidate.id,engine.snapshot().roles.getValue(Role.LEFT_HAND).device)
                dismiss(scenario)
                hub.closeBridge()
                assertTrue(waitUntil { engine.snapshot().roles.getValue(Role.LEFT_HAND).latestTimeNs==null })
                assertActivityText(scenario,"IMU 未就绪")
                assertDisabled(scenario,"校准左手")
            }
        }
    }
    private fun picker(activity: MainActivity): BluetoothDevicePicker? {
        val field=MainActivity::class.java.getDeclaredField("activeBluetoothPicker"); field.isAccessible=true
        return field.get(activity) as BluetoothDevicePicker?
    }
    private fun awaitPicker(scenario: ActivityScenario<MainActivity>) {
        assertTrue(waitUntil { var showing=false; scenario.onActivity { showing=picker(it)?.dialog?.isShowing==true }; showing })
        instrumentation.waitForIdleSync()
    }
    private fun clickActivity(scenario: ActivityScenario<MainActivity>,label: String) {
        scenario.onActivity { activity ->
            val button=views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString()==label && it.isEnabled }
            button.requestRectangleOnScreen(Rect(0,0,button.width,button.height),true); assertTrue(button.performClick())
        }; instrumentation.waitForIdleSync()
    }
    private fun clickPicker(scenario: ActivityScenario<MainActivity>,label: String) {
        scenario.onActivity { activity ->
            val view=views(requireNotNull(picker(activity)?.dialog?.window).decorView).filterIsInstance<Button>().first { it.text.toString().startsWith(label) && it.isEnabled && it.isShown }
            view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true); assertTrue(view.performClick())
        }; instrumentation.waitForIdleSync()
    }
    private fun assertVisible(scenario: ActivityScenario<MainActivity>,label: String) {
        assertTrue("Visible dialog feedback missing: $label",waitUntil {
            var found=false; scenario.onActivity { activity ->
                val root=picker(activity)?.dialog?.window?.decorView
                if(root!=null)found=views(root).filterIsInstance<TextView>().any { it.text.contains(label) && it.isShown && it.getGlobalVisibleRect(Rect()) }
            }; found
        })
    }
    private fun assertActivityText(scenario: ActivityScenario<MainActivity>,label: String) {
        assertTrue(waitUntil { var found=false; scenario.onActivity { found=views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.contains(label) } }; found })
    }
    private fun assertDisabled(scenario: ActivityScenario<MainActivity>,label: String) {
        scenario.onActivity { activity -> assertFalse(views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString()==label }.isEnabled) }
    }
    private fun dismiss(scenario: ActivityScenario<MainActivity>) { scenario.onActivity { picker(it)?.dismiss() }; instrumentation.waitForIdleSync() }
    private fun views(view: View): List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node)+(0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap(::nodes)
    private fun waitUntil(block: () -> Boolean): Boolean { val limit=android.os.SystemClock.uptimeMillis()+8000; while(android.os.SystemClock.uptimeMillis()<limit) { if(block())return true; android.os.SystemClock.sleep(40) }; return false }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(300)
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.filesDir,"test-reports/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
}
