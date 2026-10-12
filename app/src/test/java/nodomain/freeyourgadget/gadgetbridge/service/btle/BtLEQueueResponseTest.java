/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.btle;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class BtLEQueueResponseTest extends TestBase {
    private BtLEQueue queue;
    private AbstractBTLEDeviceSupport support;
    private BluetoothGatt gatt;
    private BluetoothGattCallback callback;
    private BluetoothGattCharacteristic characteristic;
    private final AtomicInteger writes = new AtomicInteger();
    private final CountDownLatch firstWrite = new CountDownLatch(1);
    private final CountDownLatch secondWrite = new CountDownLatch(1);

    @Before
    public void prepareQueue() throws Exception {
        support = mock(AbstractBTLEDeviceSupport.class);
        when(support.getContext()).thenReturn(getContext());
        when(support.getDevicePrefs()).thenReturn(mock(DevicePrefs.class));
        when(support.getMTU(0)).thenReturn(23);
        GBDevice device = mock(GBDevice.class);
        when(device.getState()).thenReturn(GBDevice.State.CONNECTED);
        queue = new BtLEQueue(device, Collections.emptySet(), support);
        when(support.getQueue(0)).thenReturn(queue);
        gatt = mock(BluetoothGatt.class);
        Field gattField = BtLEQueue.class.getDeclaredField("mBluetoothGatt");
        gattField.setAccessible(true);
        gattField.set(queue, gatt);
        Field callbackField = BtLEQueue.class.getDeclaredField("internalGattCallback");
        callbackField.setAccessible(true);
        callback = (BluetoothGattCallback) callbackField.get(queue);
        characteristic = new BluetoothGattCharacteristic(UUID.randomUUID(),
                BluetoothGattCharacteristic.PROPERTY_WRITE, 0);
        when(gatt.writeCharacteristic(any(BluetoothGattCharacteristic.class))).thenAnswer(invocation -> {
            if (writes.incrementAndGet() == 1) firstWrite.countDown();
            else secondWrite.countDown();
            return true;
        });
    }

    @After
    public void stopQueue() {
        if (queue != null) queue.dispose();
    }

    private TransactionBuilder twoWrites(long timeout) {
        return new TransactionBuilder("writes", support, 0).queueMessages(timeout)
                .write(characteristic, (byte) 1).write(characteristic, (byte) 2);
    }

    private void completeWrite() {
        callback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS);
    }

    private void respond(byte value) {
        callback.onCharacteristicChanged(gatt, characteristic, new byte[]{value});
    }

    @Test
    public void gattCompletionDoesNotCountAsDeviceReply() throws Exception {
        twoWrites(10000).queue();
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        completeWrite();
        assertFalse(secondWrite.await(40, TimeUnit.MILLISECONDS));
        respond((byte) 1);
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void immediateResponseStillWaitsForGattCompletion() throws Exception {
        twoWrites(10000).queue();
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        respond((byte) 1);
        assertFalse(secondWrite.await(40, TimeUnit.MILLISECONDS));
        completeWrite();
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void disabledPacingPreservesGattSerialization() throws Exception {
        twoWrites(0).queue();
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        assertFalse(secondWrite.await(40, TimeUnit.MILLISECONDS));
        completeWrite();
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void timeoutOnFinalMessageBlocksFollowingTransaction() throws Exception {
        new TransactionBuilder("first", support, 0).queueMessages(100)
                .write(characteristic, (byte) 1).queue();
        new TransactionBuilder("next", support, 0).write(characteristic, (byte) 2).queue();
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        completeWrite();
        assertFalse(secondWrite.await(30, TimeUnit.MILLISECONDS));
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
        assertEquals(2, writes.get());
    }

    @Test
    public void rejectedResponseStillReachesDeviceCallback() throws Exception {
        twoWrites(10000).queueMessages(10000, (request, response) -> request[0] == response[0]).queue();
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        completeWrite();
        respond((byte) 9);
        verify(support).onCharacteristicChanged(any(), any(), any());
        assertFalse(secondWrite.await(40, TimeUnit.MILLISECONDS));
        respond((byte) 1);
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void abortUnblocksResponseWaitAndDiscardsRemainingActions() throws Exception {
        twoWrites(10000).queue();
        new TransactionBuilder("after abort", support, 0).write(characteristic, (byte) 3).queue();
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        completeWrite();
        queue.abortCurrentTransaction();
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
        assertArrayEquals(new byte[]{3}, characteristic.getValue());
    }

    @Test
    public void failedWriteDoesNotKeepResponseWaitArmed() throws Exception {
        when(gatt.writeCharacteristic(any(BluetoothGattCharacteristic.class))).thenAnswer(invocation -> {
            if (writes.incrementAndGet() == 1) {
                firstWrite.countDown();
                return false;
            }
            secondWrite.countDown();
            return true;
        });
        twoWrites(10000).queue();
        new TransactionBuilder("after failure", support, 0).write(characteristic, (byte) 3).queue();
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
        assertArrayEquals(new byte[]{3}, characteristic.getValue());
    }

    @Test
    public void chunkFragmentsDoNotWaitAndFinalChunkCorrelatesWholeMessage() throws Exception {
        CountDownLatch afterChunks = new CountDownLatch(1);
        when(gatt.writeCharacteristic(any(BluetoothGattCharacteristic.class))).thenAnswer(invocation -> {
            int count = writes.incrementAndGet();
            if (count == 1) firstWrite.countDown();
            if (count == 2) secondWrite.countDown();
            if (count == 3) {
                respond((byte) 1);
                afterChunks.countDown();
            }
            return true;
        });
        new TransactionBuilder("chunks", support, 0)
                .queueMessages(10000, (request, response) -> request.length == 5 && request[0] == response[0])
                .writeChunkedData(characteristic, new byte[]{1, 2, 3, 4, 5}, 2)
                .run(afterChunks::countDown).queue();
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        completeWrite();
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
        completeWrite();
        assertTrue(afterChunks.await(1, TimeUnit.SECONDS));
        completeWrite();
        CountDownLatch nextTransaction = new CountDownLatch(1);
        new TransactionBuilder("after chunks", support, 0).run(nextTransaction::countDown).queue();
        assertTrue(nextTransaction.await(1, TimeUnit.SECONDS));
    }
}
