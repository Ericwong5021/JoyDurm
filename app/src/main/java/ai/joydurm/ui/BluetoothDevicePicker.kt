package ai.joydurm.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.joydurm.core.Role

/** Discovery/bonding only. No private HID host API and no fabricated motion source. */
data class BluetoothChoice(val address: String, val name: String, val bond: Int) {
    val label get() = "$name\n$address · ${when(bond) {
        BluetoothDevice.BOND_BONDED -> "已配对"
        BluetoothDevice.BOND_BONDING -> "正在配对"
        else -> "未配对"
    }}"
}
data class BluetoothInventory(val available: Boolean, val permitted: Boolean, val enabled: Boolean,
    val scanning: Boolean, val devices: List<BluetoothChoice>, val notice: String = "", val canScan: Boolean = true)
interface BluetoothPlatform {
    fun inventory(): BluetoothInventory
    fun watch(changed: () -> Unit)
    fun discover(): String?
    fun pair(address: String): String?
    fun close()
}

@SuppressLint("MissingPermission")
class AndroidBluetoothPlatform(private val context: Context): BluetoothPlatform {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = linkedMapOf<String,BluetoothChoice>()
    private var changed: (() -> Unit)? = null
    private var registered = false
    private var notice = ""
    private var ownsDiscovery = false
    private fun permitted() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun scanPermitted() = context.checkSelfPermission(if(Build.VERSION.SDK_INT >= 31)
        Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun choice(device: BluetoothDevice) = BluetoothChoice(device.address, device.name ?: "未命名蓝牙设备", device.bondState)
    private val receiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if(!permitted()) { changed?.invoke(); return }
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            runCatching {
                if(device != null) {
                    val item = choice(device); found[item.address] = item
                    if(intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                        notice = when(item.bond) {
                            BluetoothDevice.BOND_BONDED -> "${item.name} 已配对；连接由 Android 系统管理，配对不代表 IMU 就绪。"
                            BluetoothDevice.BOND_BONDING -> "正在配对 ${item.name}，请确认系统配对窗口。"
                            else -> if(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING)
                                "${item.name} 配对失败或已取消，请重试或打开系统蓝牙。" else "${item.name} 尚未配对。"
                        }
                    }
                }
            }.onFailure { notice = "蓝牙状态读取失败，请刷新或打开系统蓝牙。" }
            changed?.invoke()
        }
    }
    override fun watch(changed: () -> Unit) {
        this.changed = changed
        if(registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED); addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Bluetooth broadcasts can originate from the privileged Bluetooth process.
        if(Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver,filter)
        registered = true
    }
    override fun inventory(): BluetoothInventory {
        val radio = adapter ?: return BluetoothInventory(false,permitted(),false,false,emptyList())
        if(!permitted()) return BluetoothInventory(true,false,false,false,emptyList())
        return runCatching {
            radio.bondedDevices.forEach { found[it.address] = choice(it) }
            // Re-read bonds: never retain a stale "paired" claim after unpairing.
            val items = found.keys.map { choice(radio.getRemoteDevice(it)) }.sortedWith(
                compareByDescending<BluetoothChoice> { it.bond == BluetoothDevice.BOND_BONDED }.thenBy { it.name }.thenBy { it.address })
            BluetoothInventory(true,true,radio.isEnabled,scanPermitted() && radio.isDiscovering,items,notice,scanPermitted())
        }.getOrElse { BluetoothInventory(true,false,false,false,emptyList(),"蓝牙访问失败：请重新授权附近设备。") }
    }
    override fun discover(): String? = runCatching {
        val radio = adapter ?: return "此设备没有可用蓝牙适配器。"
        if(!permitted() || !scanPermitted()) return "需要附近设备权限；Android 11 及更早版本搜索还需位置权限。"
        if(!radio.isEnabled) return "蓝牙已关闭，请先打开蓝牙。"
        if(radio.isDiscovering) return null
        found.entries.removeAll { it.value.bond == BluetoothDevice.BOND_NONE }
        notice = "请按手柄 SYNC 键进入配对模式，等待设备出现。"
        ownsDiscovery=radio.startDiscovery()
        if(!ownsDiscovery) "搜索未启动，请检查系统蓝牙与位置开关后重试。" else null
    }.getOrElse { "搜索失败：${it.javaClass.simpleName}，请打开系统蓝牙检查。" }
    override fun pair(address: String): String? = runCatching {
        val radio = adapter ?: return "此设备没有可用蓝牙适配器。"
        if(!permitted()) return "附近设备权限未授予，尚未开始配对。"
        if(!radio.isEnabled) return "蓝牙已关闭，尚未开始配对。"
        if(scanPermitted() && radio.isDiscovering) radio.cancelDiscovery()
        val device = radio.getRemoteDevice(address)
        when(device.bondState) {
            BluetoothDevice.BOND_BONDED -> notice = "已配对；请在系统蓝牙中确认连接，再关联真实控制器输入。"
            BluetoothDevice.BOND_BONDING -> notice = "系统正在配对，请确认配对窗口；关闭清单不会取消系统配对。"
            else -> {
                if(!device.createBond()) return "系统未能开始配对，请重试或打开系统蓝牙。"
                notice = "已向系统申请配对，请确认配对窗口；IMU 尚未就绪。"
            }
        }
        null
    }.getOrElse { "配对失败：${it.javaClass.simpleName}，请重新授权或打开系统蓝牙。" }
    override fun close() {
        runCatching { if(ownsDiscovery && scanPermitted() && adapter?.isDiscovering == true) adapter?.cancelDiscovery() }
        ownsDiscovery=false
        if(registered) { context.unregisterReceiver(receiver); registered = false }
        changed = null
    }
}

/** One owned window. Refresh, permission return, pairing and failures update it in place. */
class BluetoothDevicePicker(private val context: Context, val role: Role, private val platform: BluetoothPlatform,
    private val selected: () -> String?, private val owner: (String) -> Role?,
    private val select: (BluetoothChoice) -> Unit, private val permission: () -> Unit,
    private val enable: () -> Unit, private val settings: () -> Unit,
    private val inputs: () -> Unit, private val diagnostics: () -> Unit, private val closed: () -> Unit) {
    private val theme = StageTheme(context)
    private val handler = Handler(Looper.getMainLooper())
    private val heading = theme.text("",14,color=StageTheme.MUTED).apply { accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val feedback = theme.text("",14,color=StageTheme.RED).apply { accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val rows = theme.column()
    private val authorize = theme.primary("授权附近设备") { permission() }
    private val power = theme.primary("打开蓝牙") { enable() }
    private val search = theme.secondary("搜索附近设备") {
        if(!platform.inventory().canScan) permission()
        else { val error=platform.discover(); refresh(); error?.let { feedback.text=it } }
    }
    private val refreshButton = theme.secondary("刷新设备清单") { refresh() }
    private var signature: List<BluetoothChoice>? = null
    private var renderedSelection: String? = null
    private var confirmation: String? = null
    private var lastNotice = ""
    private val confirm = theme.primary("确认转移到${role.label}") {
        val address = confirmation ?: return@primary
        val item = platform.inventory().devices.firstOrNull { it.address==address }
        confirmation = null
        if(item!=null) commit(item) else { feedback.text="设备已不在清单中，请刷新后重新选择。"; refresh() }
    }
    val dialog: AlertDialog
    init {
        val body = theme.column().apply { setPadding(theme.dp(16),theme.dp(8),theme.dp(16),theme.dp(12)) }
        body.addView(heading); body.addView(feedback); body.addView(confirm); body.addView(rows)
        body.addView(authorize); body.addView(power); body.addView(search); body.addView(refreshButton)
        body.addView(theme.secondary("系统蓝牙连接") { settings() })
        body.addView(theme.secondary("关联控制器输入") { inputs() })
        body.addView(theme.secondary("连接详情") { diagnostics() })
        body.addView(theme.text("选择角色、蓝牙配对和 IMU 就绪分别确认。已配对设备也可能没有运动数据。系统未开放手柄 IMU 时不能校准或体感演奏；仍可使用触摸鼓垫。",13,color=StageTheme.MUTED))
        dialog = AlertDialog.Builder(context).setTitle("分配${role.label} · 蓝牙设备")
            .setView(ScrollView(context).apply { addView(body) }).setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null); platform.close(); closed() }
        platform.watch { handler.post { if(dialog.isShowing) refresh() } }
    }
    fun show() {
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(StageTheme.BG))
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels*.95f).toInt(),(context.resources.displayMetrics.heightPixels*.85f).toInt())
        refresh()
    }
    fun refresh() {
        val state=platform.inventory()
        heading.text=when {
            !state.available -> "此设备没有可用蓝牙适配器。可查看已连接输入与连接详情。"
            !state.permitted -> "附近设备权限未授予，无法读取真实蓝牙清单。请授权后继续，或在系统蓝牙中配对。"
            !state.enabled -> "蓝牙已关闭。打开蓝牙后可选择已配对设备或搜索附近手柄。"
            state.devices.isEmpty() -> if(state.scanning) "正在搜索附近蓝牙设备…请按手柄 SYNC 键进入配对模式。" else "暂无蓝牙设备。请按手柄 SYNC 键进入配对模式，再搜索附近设备。"
            else -> "${state.devices.size} 个真实蓝牙设备${if(state.scanning) " · 搜索中" else ""}\n点击名称开始分配；名称中的 (L)/(R)、−/+ 与地址原样保留。"
        }
        if(state.notice!=lastNotice && confirmation==null) { feedback.text=state.notice; lastNotice=state.notice }
        authorize.visibility=if(state.available && !state.permitted) android.view.View.VISIBLE else android.view.View.GONE
        power.visibility=if(state.available && state.permitted && !state.enabled) android.view.View.VISIBLE else android.view.View.GONE
        search.isEnabled=state.available && state.permitted && state.enabled
        confirm.visibility=if(confirmation!=null) android.view.View.VISIBLE else android.view.View.GONE
        val current=selected()
        if(signature!=state.devices || renderedSelection!=current) {
            signature=state.devices; renderedSelection=current; rows.removeAllViews()
            state.devices.forEach { item -> rows.addView(theme.secondary(item.label+if(item.address==current)"\n已选择给${role.label} · IMU 单独确认" else "") {
                val previous=owner(item.address)
                if(previous!=null && previous!=role) {
                    confirmation=item.address; feedback.text="此设备已分配给${previous.label}。确认后转移到${role.label}，原角色将解除分配。"; refresh()
                } else commit(item)
            }.apply { isEnabled=state.enabled && state.permitted; contentDescription="蓝牙设备 ${item.name} ${item.address}" }) }
        }
    }
    private fun commit(item: BluetoothChoice) {
        select(item)
        val error=platform.pair(item.address)
        refresh()
        if(error!=null) feedback.text=error
        else if(platform.inventory().notice.isBlank()) feedback.text="已选择 ${item.name} 给${role.label}；蓝牙配对状态与 IMU 状态请分别确认。"
    }
    fun dismiss() = dialog.dismiss()
}
