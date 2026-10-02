package ai.joydurm.core

/** A physical Bluetooth choice is not proof of connection or of a motion stream. */
data class SelectedBluetoothDevice(val address: String, val name: String)
class BluetoothRoleSelections(initial: Map<Role,SelectedBluetoothDevice> = emptyMap()) {
    private val selections = linkedMapOf<Role,SelectedBluetoothDevice>()
    init { initial.forEach { (role,device) -> select(role,device) } }
    fun get(role: Role) = selections[role]
    fun owner(address: String) = selections.entries.firstOrNull { it.value.address.equals(address,true) }?.key
    fun all(): Map<Role,SelectedBluetoothDevice> = selections.toMap()
    fun select(role: Role,device: SelectedBluetoothDevice): Role? {
        require(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").matches(device.address))
        val displaced = owner(device.address)?.takeUnless { it==role }
        displaced?.let { selections.remove(it) }
        selections[role] = device.copy(address=device.address.uppercase(java.util.Locale.ROOT))
        return displaced
    }
    fun remove(role: Role) { selections.remove(role) }
}
