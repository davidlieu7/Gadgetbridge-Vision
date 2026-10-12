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

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

/**
 * A response gate for an existing transport queue, not a separate message scheduler.
 * The transport arms it before writing and awaits it only after transport completion.
 */
public final class ResponseWait {
    private static final Logger LOG = LoggerFactory.getLogger(ResponseWait.class);
    private volatile Pending pending;

    public static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        private final byte[] request;
        private final BiPredicate<byte[], byte[]> matcher;

        private Pending(byte[] request, @Nullable BiPredicate<byte[], byte[]> matcher) {
            this.request = request.clone();
            this.matcher = matcher;
        }
    }

    public synchronized Pending arm(byte[] request, @Nullable BiPredicate<byte[], byte[]> matcher) {
        cancel();
        pending = new Pending(request, matcher);
        return pending;
    }

    @Nullable
    public Pending getPending() {
        return pending;
    }

    /**
     * Capture the pending ticket before invoking device callbacks, then notify that same ticket
     * afterward. A delayed callback must never release a newer write.
     */
    public void onResponse(@Nullable Pending ticket, byte[] response) {
        if (ticket == null || ticket != pending) {
            return;
        }
        try {
            if (ticket.matcher == null || ticket.matcher.test(ticket.request, response)) {
                ticket.latch.countDown();
            }
        } catch (RuntimeException e) {
            LOG.warn("Response matcher failed, keeping the message pending until timeout", e);
        }
    }

    public boolean await(Pending ticket, long timeoutMillis) throws InterruptedException {
        try {
            return ticket.latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            synchronized (this) {
                if (pending == ticket) {
                    pending = null;
                }
            }
        }
    }

    public synchronized void cancel() {
        Pending ticket = pending;
        pending = null;
        if (ticket != null) {
            ticket.latch.countDown();
        }
    }
}
