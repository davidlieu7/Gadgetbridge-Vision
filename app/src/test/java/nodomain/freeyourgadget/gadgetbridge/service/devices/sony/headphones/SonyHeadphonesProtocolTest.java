/*  Copyright (C) 2026 temidaradev

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventSendBytes;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdateDeviceState;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones.protocol.Message;
import nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones.protocol.MessageType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.sony.headphones.protocol.Request;

public class SonyHeadphonesProtocolTest {
    private final GBDevice device = mock(GBDevice.class);
    private final SonyHeadphonesProtocol protocol = new SonyHeadphonesProtocol(device);
    private final Request first = Request.fromHex(MessageType.COMMAND_1, "d6d2");
    private final Request second = Request.fromHex(MessageType.COMMAND_2, "3600");

    @Test
    public void queuedRequestsRemainPendingUntilAcknowledged() {
        when(device.getState()).thenReturn(GBDevice.State.INITIALIZED);
        protocol.enqueueRequests(Arrays.asList(first, second));

        assertArrayEquals(first.encode((byte) 0), protocol.getFromQueue());
        assertEquals(1, protocol.getPendingAcks());

        final GBDeviceEventSendBytes next = (GBDeviceEventSendBytes) acknowledge(1);
        assertArrayEquals(second.encode((byte) 1), next.encodedBytes);
        assertEquals(1, protocol.getPendingAcks());

        assertNull(acknowledge(0));
        assertEquals(0, protocol.getPendingAcks());

        protocol.enqueueRequests(Collections.singletonList(first));
        assertArrayEquals(first.encode((byte) 0), protocol.getFromQueue());
        assertEquals(1, protocol.getPendingAcks());
        assertNull(acknowledge(1));
        assertEquals(0, protocol.getPendingAcks());
    }

    @Test
    public void initializationWaitsForLastQueuedAck() {
        when(device.getState()).thenReturn(GBDevice.State.INITIALIZING);
        protocol.encodeInit();
        protocol.enqueueRequests(Collections.singletonList(first));

        final GBDeviceEventSendBytes next = (GBDeviceEventSendBytes) acknowledge(1);
        assertArrayEquals(first.encode((byte) 1), next.encodedBytes);
        assertEquals(1, protocol.getPendingAcks());

        final GBDeviceEventUpdateDeviceState event = (GBDeviceEventUpdateDeviceState) acknowledge(0);
        assertEquals(GBDevice.State.INITIALIZED, event.state);
        assertEquals(0, protocol.getPendingAcks());
    }

    @Test
    public void queuedRequestsWaitForAllOutstandingAcks() {
        when(device.getState()).thenReturn(GBDevice.State.INITIALIZED);
        protocol.encodeRequest(first);
        protocol.encodeRequest(second);
        protocol.enqueueRequests(Collections.singletonList(first));

        assertNull(acknowledge(1));
        assertEquals(1, protocol.getPendingAcks());

        final GBDeviceEventSendBytes next = (GBDeviceEventSendBytes) acknowledge(0);
        assertArrayEquals(first.encode((byte) 0), next.encodedBytes);
        assertEquals(1, protocol.getPendingAcks());
        assertNull(acknowledge(1));
        assertEquals(0, protocol.getPendingAcks());
    }

    private GBDeviceEvent acknowledge(final int sequenceNumber) {
        final byte[] ack = new Message(MessageType.ACK, (byte) sequenceNumber, new byte[0]).encode();
        final GBDeviceEvent[] events = protocol.decodeResponse(ack);
        assertEquals(1, events.length);
        return events[0];
    }
}
