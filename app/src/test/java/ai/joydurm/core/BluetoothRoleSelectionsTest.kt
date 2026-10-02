package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test

class BluetoothRoleSelectionsTest {
    private val left=SelectedBluetoothDevice("11:22:33:44:55:AA","Joy-Con (L) −")
    private val right=SelectedBluetoothDevice("11:22:33:44:55:BB","Joy-Con (R) +")
    @Test fun allFourRolesKeepDistinctRealAddressesAndOriginalNames() {
        val roles=BluetoothRoleSelections()
        Role.entries.forEachIndexed { index,role -> roles.select(role,left.copy(address="11:22:33:44:55:A$index")) }
        assertEquals(4,roles.all().size)
        assertEquals(4,roles.all().values.map { it.address }.distinct().size)
        assertTrue(roles.all().values.all { it.name==left.name })
    }
    @Test fun sameNamedDevicesAreNeverMerged() {
        val roles=BluetoothRoleSelections(); roles.select(Role.LEFT_HAND,left)
        roles.select(Role.RIGHT_HAND,right.copy(name=left.name))
        assertEquals(Role.LEFT_HAND,roles.owner(left.address)); assertEquals(Role.RIGHT_HAND,roles.owner(right.address))
    }
    @Test fun explicitTransferRemovesOnlyPreviousRole() {
        val roles=BluetoothRoleSelections(); roles.select(Role.LEFT_HAND,left); roles.select(Role.LEFT_FOOT,right)
        assertEquals(Role.LEFT_HAND,roles.select(Role.RIGHT_HAND,left.copy(address=left.address.lowercase())))
        assertNull(roles.get(Role.LEFT_HAND)); assertEquals(right,roles.get(Role.LEFT_FOOT))
        assertEquals(Role.RIGHT_HAND,roles.owner(left.address))
    }
    @Test fun repeatedSelectionAndRestartKeepStableChoice() {
        val roles=BluetoothRoleSelections(); roles.select(Role.LEFT_HAND,left)
        assertNull(roles.select(Role.LEFT_HAND,left))
        assertEquals(left,BluetoothRoleSelections(roles.all()).get(Role.LEFT_HAND))
    }
    @Test(expected=IllegalArgumentException::class) fun invalidAddressIsRejected() {
        BluetoothRoleSelections().select(Role.LEFT_HAND,left.copy(address="same-name"))
    }
    @Test fun choiceAndUnassignmentDoNotCreateMotionOrCalibration() {
        val events=mutableListOf<Hit>(); val engine=DrumEngine { events.add(it) }
        engine.assign(Role.LEFT_HAND,"bluetooth:${left.address}")
        assertNull(engine.snapshot().roles.getValue(Role.LEFT_HAND).latestTimeNs)
        assertNull(engine.snapshot().roles.getValue(Role.LEFT_HAND).calibration)
        engine.startCalibration(Role.LEFT_HAND); engine.unassign(Role.LEFT_HAND)
        assertNull(engine.snapshot().roles.getValue(Role.LEFT_HAND).device)
        assertEquals(0,engine.calibrationCount(Role.LEFT_HAND)); assertTrue(events.isEmpty())
    }
}
