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
package nodomain.freeyourgadget.gadgetbridge.service;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ResponseWaitTest {
    @Test
    public void capturesImmediateResponseBeforeAwait() throws Exception {
        ResponseWait wait = new ResponseWait();
        ResponseWait.Pending pending = wait.arm(new byte[]{1}, null);
        wait.onResponse(pending, new byte[]{2});
        assertTrue(wait.await(pending, 0));
        assertNull(wait.getPending());
    }

    @Test
    public void timeoutClearsWaitWithoutRetry() throws Exception {
        ResponseWait wait = new ResponseWait();
        ResponseWait.Pending pending = wait.arm(new byte[]{1}, null);
        assertFalse(wait.await(pending, 1));
        assertNull(wait.getPending());
    }

    @Test
    public void matchesResponseAgainstCopiedOutgoingMessage() throws Exception {
        ResponseWait wait = new ResponseWait();
        byte[] outgoing = {1};
        ResponseWait.Pending pending = wait.arm(outgoing, (request, response) -> request[0] == response[0]);
        outgoing[0] = 2;
        wait.onResponse(pending, new byte[]{2});
        assertEquals(1, pending.latch.getCount());
        wait.onResponse(pending, new byte[]{1});
        assertTrue(wait.await(pending, 0));
    }

    @Test
    public void staleOrDuplicateResponseDoesNotReleaseNextWrite() throws Exception {
        ResponseWait wait = new ResponseWait();
        ResponseWait.Pending first = wait.arm(new byte[]{1}, null);
        wait.onResponse(first, new byte[]{1});
        assertTrue(wait.await(first, 0));
        ResponseWait.Pending second = wait.arm(new byte[]{2}, null);
        wait.onResponse(first, new byte[]{1});
        assertFalse(wait.await(second, 1));
    }

    @Test
    public void matcherExceptionLeavesWaitArmed() throws Exception {
        ResponseWait wait = new ResponseWait();
        ResponseWait.Pending pending = wait.arm(new byte[]{1}, (request, response) -> {
            throw new IllegalArgumentException("bad packet");
        });
        wait.onResponse(pending, new byte[]{1});
        assertFalse(wait.await(pending, 1));
    }

    @Test
    public void cancellationUnblocksWaitingThread() throws Exception {
        ResponseWait wait = new ResponseWait();
        ResponseWait.Pending pending = wait.arm(new byte[]{1}, null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            started.countDown();
            try {
                wait.await(pending, 10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        thread.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        wait.cancel();
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        thread.join(1000);
        assertNull(wait.getPending());
    }

    @Test
    public void interruptedWaitClearsPendingMessage() throws Exception {
        ResponseWait wait = new ResponseWait();
        ResponseWait.Pending pending = wait.arm(new byte[]{1}, null);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                wait.await(pending, 10000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        thread.start();
        thread.join(1000);
        assertTrue(interrupted.get());
        assertNull(wait.getPending());
    }
}
