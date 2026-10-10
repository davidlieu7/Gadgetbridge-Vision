/*  Copyright (C) 2026 David Giron

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

package nodomain.freeyourgadget.gadgetbridge.service.devices.fitpro;

import static nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions.toUint16;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.FunctionAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.WriteAction;

final class FitProCommandQueue {
    private static final long TIMEOUT_MS = 2000;
    private final Object monitor;
    private final BooleanSupplier connected;
    private final Supplier<BluetoothGattCharacteristic> characteristic;
    private final Supplier<TransactionBuilder> transaction;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private Entry pending;
    private int generation;
    private boolean ready;

    private static final class Entry {
        final byte[] data;
        final Consumer<TransactionBuilder> completion;
        Runnable timeout;
        boolean started;

        Entry(byte[] data, Consumer<TransactionBuilder> completion) {
            this.data = data;
            this.completion = completion;
        }

        static Entry finishInitialization() {
            return new Entry(null, builder -> builder.setDeviceState(GBDevice.State.INITIALIZED));
        }
    }

    FitProCommandQueue(Object monitor, BooleanSupplier connected,
                       Supplier<BluetoothGattCharacteristic> characteristic,
                       Supplier<TransactionBuilder> transaction) {
        this.monitor = monitor;
        this.connected = connected;
        this.characteristic = characteristic;
        this.transaction = transaction;
    }

    void initialize(TransactionBuilder builder, List<byte[]> commands) {
        synchronized (monitor) {
            clear();
            for (byte[] command : commands) {
                entries.add(commandEntry(command));
            }
            entries.add(Entry.finishInitialization());
            final int currentGeneration = generation;
            builder.add(new FunctionAction(() -> {
                synchronized (monitor) {
                    if (generation == currentGeneration) {
                        ready = true;
                        sendNext();
                    }
                }
            }));
        }
    }

    void enqueue(byte[] command) {
        enqueueAll(Collections.singletonList(command));
    }

    void enqueueAll(List<byte[]> commands) {
        synchronized (monitor) {
            if (!canWrite()) {
                return;
            }
            for (byte[] command : commands) {
                entries.add(commandEntry(command));
            }
            sendNext();
        }
    }

    private Entry commandEntry(byte[] command) {
        if (command == null || command.length < 8
                || command[0] != FitProConstants.DATA_HEADER
                || toUint16(command[2], command[1]) != command.length - 3
                || toUint16(command[7], command[6]) != command.length - 8) {
            throw new IllegalArgumentException("Invalid FitPro command frame");
        }
        return new Entry(command.clone(), null);
    }

    void acceptReply(UUID uuid, byte[] data) {
        synchronized (monitor) {
            if (!FitProConstants.UUID_CHARACTERISTIC_RX.equals(uuid)
                    || data == null || data.length < 8 || pending == null || !pending.started) {
                return;
            }
            final byte command;
            if (data[0] == FitProConstants.DATA_HEADER_ACK) {
                if (data.length != 8 || toUint16(data[2], data[1]) != 5 || data[7] != 1) {
                    return;
                }
                command = data[4];
            } else if (data[0] == FitProConstants.DATA_HEADER && data[4] == 1
                    && toUint16(data[2], data[1]) == data.length - 3
                    && toUint16(data[7], data[6]) == data.length - 8) {
                command = data[5];
            } else {
                return;
            }
            if (pending.data[3] == data[3] && pending.data[5] == command) {
                complete(pending);
            }
        }
    }

    void clear() {
        synchronized (monitor) {
            generation++;
            ready = false;
            if (pending != null && pending.timeout != null) {
                handler.removeCallbacks(pending.timeout);
            }
            pending = null;
            entries.clear();
        }
    }

    private boolean canWrite() {
        return connected.getAsBoolean() && characteristic.get() != null;
    }

    private void sendNext() {
        if (!ready || pending != null || entries.isEmpty()) {
            return;
        }
        if (!canWrite()) {
            clear();
            return;
        }
        final Entry entry = entries.remove();
        final TransactionBuilder builder = transaction.get();
        if (entry.completion != null) {
            final int currentGeneration = generation;
            builder.add(new FunctionAction(gatt -> {
                synchronized (monitor) {
                    return generation == currentGeneration && canWrite();
                }
            }));
            entry.completion.accept(builder);
            builder.queue();
            sendNext();
            return;
        }
        pending = entry;
        builder.add(new WriteAction(characteristic.get(), entry.data) {
            @Override
            public boolean run(BluetoothGatt gatt) {
                synchronized (monitor) {
                    if (pending != entry) {
                        return false;
                    }
                    if (!canWrite()) {
                        clear();
                        return false;
                    }
                    entry.started = true;
                    entry.timeout = () -> {
                        synchronized (monitor) {
                            complete(entry);
                        }
                    };
                    handler.postDelayed(entry.timeout, TIMEOUT_MS);
                    if (!super.run(gatt)) {
                        clear();
                        return false;
                    }
                    return true;
                }
            }
        });
        builder.queue();
    }

    private void complete(Entry entry) {
        if (pending != entry) {
            return;
        }
        handler.removeCallbacks(entry.timeout);
        pending = null;
        sendNext();
    }
}
