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
package nodomain.freeyourgadget.gadgetbridge.service.btbr;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class BtBRQueueResponseTest extends TestBase {
    private BtBRQueue queue;
    private SocketCallback callback;
    private Handler handler;
    private PipedOutputStream responses;
    private Thread writer;
    private Thread reader;
    private final AtomicInteger writes = new AtomicInteger();
    private final CountDownLatch firstWrite = new CountDownLatch(1);
    private final CountDownLatch secondWrite = new CountDownLatch(1);

    @Before
    public void prepareQueue() throws Exception {
        GBDevice device = mock(GBDevice.class);
        when(device.isConnected()).thenReturn(true);
        callback = mock(SocketCallback.class);
        queue = new BtBRQueue(null, device, getContext(), callback, UUID.randomUUID(), 1024, 0, -1);
        BluetoothSocket socket = mock(BluetoothSocket.class);
        when(socket.isConnected()).thenReturn(true);
        responses = new PipedOutputStream();
        PipedInputStream input = new PipedInputStream(responses);
        when(socket.getInputStream()).thenReturn(input);
        when(socket.getOutputStream()).thenReturn(new OutputStream() {
            @Override
            public void write(int value) {
                if (writes.incrementAndGet() == 1) firstWrite.countDown();
                else secondWrite.countDown();
            }
        });
        setField("mBtSocket", socket);
        Field handlerField = BtBRQueue.class.getDeclaredField("mWriteHandler");
        handlerField.setAccessible(true);
        handler = (Handler) handlerField.get(queue);
        Method createReader = BtBRQueue.class.getDeclaredMethod("createReadThread");
        createReader.setAccessible(true);
        reader = (Thread) createReader.invoke(queue);
        setField("readThread", reader);
        reader.start();
    }

    private void setField(String name, Object value) throws Exception {
        Field field = BtBRQueue.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(queue, value);
    }

    @After
    public void stopQueue() throws Exception {
        if (queue != null) queue.dispose();
        if (responses != null) responses.close();
        if (writer != null) writer.join(1000);
        if (reader != null) reader.join(1000);
    }

    private void execute(Transaction... transactions) {
        writer = new Thread(() -> {
            for (Transaction transaction : transactions) {
                Message message = Message.obtain();
                message.what = BtBRQueue.HANDLER_SUBJECT_PERFORM_TRANSACTION;
                message.obj = transaction;
                handler.handleMessage(message);
                message.recycle();
            }
        });
        writer.start();
    }

    private TransactionBuilder builder() {
        return new TransactionBuilder("socket writes", mock(AbstractBTBRDeviceSupport.class));
    }

    private void respond(int value) throws Exception {
        responses.write(value);
        responses.flush();
    }

    @Test
    public void matchingResponseAdvancesAndStillReachesSocketCallback() throws Exception {
        execute(builder().queueMessages(10000, (request, response) -> request[0] == response[0])
                .write((byte) 1).write((byte) 2).getTransaction());
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        respond(9);
        verify(callback, timeout(1000)).onSocketRead(any(), eq(-1));
        assertFalse(secondWrite.await(40, TimeUnit.MILLISECONDS));
        respond(1);
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void lastMessageWaitPreventsFollowingTransactionOvertaking() throws Exception {
        execute(builder().queueMessages(100).write((byte) 1).getTransaction(),
                builder().write((byte) 2).getTransaction());
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        assertFalse(secondWrite.await(30, TimeUnit.MILLISECONDS));
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
        writer.join(1000);
        assertFalse(writer.isAlive());
        assertEquals(2, writes.get());
    }

    @Test
    public void disabledPacingWritesWithoutIncomingResponse() throws Exception {
        execute(builder().write((byte) 1).write((byte) 2).getTransaction());
        assertTrue(secondWrite.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void disposeUnblocksWaitingWriterAndSkipsRemainingWrite() throws Exception {
        execute(builder().queueMessages(10000).write((byte) 1).write((byte) 2).getTransaction());
        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        queue.dispose();
        writer.join(1000);
        assertFalse(writer.isAlive());
        assertEquals(1, writes.get());
    }
}
