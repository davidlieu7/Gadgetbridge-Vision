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
package nodomain.freeyourgadget.gadgetbridge.service;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanResult;

import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;

import nodomain.freeyourgadget.gadgetbridge.GBException;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

/**
 * How {@link DeviceCommunicationService#handleScanResult} hands advertisements to a scan-only
 * device: only to a device that already has a struct, through a support that is created once
 * and never replaced under a live one, and without letting a failing support or factory out.
 */
public class DeviceCommunicationServiceScanResultTest extends TestBase {
    private static final String ADDRESS = "00:11:22:33:44:55";

    private DeviceCommunicationService service;
    private DeviceSupportFactory factory;
    private GBDevice device;

    @Before
    public void setUpService() {
        service = Robolectric.buildService(DeviceCommunicationService.class).get();
        factory = mock(DeviceSupportFactory.class);
        service.setDeviceSupportFactory(factory);
        device = createDummyGDevice(ADDRESS);
    }

    @Test
    public void anAdvertisementForADeviceWithoutAStructIsDropped() {
        service.handleScanResult(device, advertisement());

        verifyNoMoreInteractions(factory);
        assertThrows(DeviceCommunicationService.DeviceNotFoundException.class, () -> service.getDeviceStruct(device));
    }

    @Test
    public void aWaitingDeviceGetsOneSupportWhichReceivesEveryAdvertisement() throws Exception {
        final DeviceSupport support = mock(DeviceSupport.class);
        when(factory.createDeviceSupport(device)).thenReturn(support);
        service.createDeviceStruct(device);
        final ScanResult first = advertisement();
        final ScanResult second = advertisement();

        service.handleScanResult(device, first);
        service.handleScanResult(device, second);

        verify(factory, times(1)).createDeviceSupport(device);
        verify(support).onScanResult(first);
        verify(support).onScanResult(second);
        verify(support, never()).connect();
        assertSame(support, service.getDeviceStruct(device).getDeviceSupport());
    }

    @Test
    public void anExistingSupportIsNotReplaced() throws Exception {
        final DeviceSupport existing = mock(DeviceSupport.class);
        service.createDeviceStruct(device).setDeviceSupport(existing);
        final ScanResult result = advertisement();

        service.handleScanResult(device, result);

        verifyNoMoreInteractions(factory);
        verify(existing).onScanResult(result);
        assertSame(existing, service.getDeviceStruct(device).getDeviceSupport());
    }

    @Test
    public void aRemovedSupportHearsNothingMoreAndItsSuccessorStartsAfresh() throws Exception {
        final DeviceSupport first = mock(DeviceSupport.class);
        final DeviceSupport second = mock(DeviceSupport.class);
        when(factory.createDeviceSupport(device)).thenReturn(first, second);
        final DeviceCommunicationService.DeviceStruct struct = service.createDeviceStruct(device);
        service.handleScanResult(device, advertisement());

        // what removeDeviceSupport leaves behind after disposing the support
        struct.setDeviceSupport(null);
        final ScanResult later = advertisement();
        service.handleScanResult(device, later);

        verify(first, times(1)).onScanResult(any());
        verify(first, never()).onScanResult(later);
        verify(second).onScanResult(later);
        assertSame(second, struct.getDeviceSupport());
    }

    @Test
    public void aFactoryThatFailsLeavesTheDeviceWithoutASupport() throws Exception {
        when(factory.createDeviceSupport(device)).thenThrow(new GBException("no support"));
        service.createDeviceStruct(device);

        service.handleScanResult(device, advertisement());

        assertNull(service.getDeviceStruct(device).getDeviceSupport());
    }

    @Test
    public void aSupportThatThrowsDoesNotTakeTheServiceDownNorLoseTheNextAdvertisement() throws Exception {
        final DeviceSupport support = mock(DeviceSupport.class);
        when(factory.createDeviceSupport(device)).thenReturn(support);
        service.createDeviceStruct(device);
        final ScanResult bad = advertisement();
        final ScanResult good = advertisement();
        doThrow(new IllegalStateException("garbled")).when(support).onScanResult(bad);

        service.handleScanResult(device, bad);
        service.handleScanResult(device, good);

        verify(support).onScanResult(good);
        assertSame(support, service.getDeviceStruct(device).getDeviceSupport());
    }

    private static ScanResult advertisement() {
        return new ScanResult(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(ADDRESS), null, -60, System.nanoTime());
    }
}
