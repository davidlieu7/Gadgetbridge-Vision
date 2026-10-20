/*  Copyright (C) 2026 oddballza

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.pairing

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Connecting to a scan-only device only puts it into waiting for scan, so for such a device that is
 * where pairing ends. Any other device keeps waiting for its connection.
 */
class PairingStateMachineScanOnlyTest {
    @Test
    fun aScanOnlyDeviceIsPairedOnceItWaitsForScan() {
        val started = start(scanOnly = true)

        val waiting = PairingStateMachine.reduce(started, PairingEvent.DeviceStateChanged(GBDevice.State.WAITING_FOR_SCAN)).state

        assertEquals(PairingPhase.Initialized, waiting.phase)
        assertEquals(StepStatus.DONE, waiting.steps[PairingStep.CONNECT])
        assertEquals(StepStatus.DONE, waiting.steps[PairingStep.INITIALIZE])
    }

    @Test
    fun aScanOnlyDeviceIsPairedWhenItIsAlreadyScanned() {
        val started = start(scanOnly = true)

        val scanned = PairingStateMachine.reduce(started, PairingEvent.DeviceStateChanged(GBDevice.State.SCANNED)).state

        assertEquals(PairingPhase.Initialized, scanned.phase)
    }

    @Test
    fun anyOtherDeviceKeepsWaitingForItsConnection() {
        val started = start(scanOnly = false)

        val waiting = PairingStateMachine.reduce(started, PairingEvent.DeviceStateChanged(GBDevice.State.WAITING_FOR_SCAN)).state

        assertEquals(PairingPhase.WaitingForDevice, waiting.phase)
        assertEquals(StepStatus.ACTIVE, waiting.steps[PairingStep.CONNECT])
    }

    private fun start(scanOnly: Boolean): PairingState {
        val transition = PairingStateMachine.reduce(PairingState(), PairingEvent.Start(associate = false, bondMode = BondMode.NONE, scanOnly = scanOnly))
        assertEquals(listOf(PairingEffect.Connect(false)), transition.effects)
        return transition.state
    }
}
