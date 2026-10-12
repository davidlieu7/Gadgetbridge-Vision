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

import android.bluetooth.BluetoothGattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.WriteAction;
import org.junit.Test;
import java.util.function.BiPredicate;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TransactionBuilderResponseQueueTest {
    @Test
    public void defaultIsDisabledAndConfigurationIsChainable() {
        TransactionBuilder builder = new TransactionBuilder("queue", mock(AbstractBTLEDeviceSupport.class), 0);
        assertEquals(0, builder.getTransaction().getMessageTimeout());
        assertSame(builder, builder.queueMessages());
        assertEquals(2000, builder.getTransaction().getMessageTimeout());
        assertSame(builder, builder.queueMessages(15));
        assertEquals(15, builder.getTransaction().getMessageTimeout());
        BiPredicate<byte[], byte[]> matcher = (request, response) -> true;
        assertSame(builder, builder.queueMessages(20, matcher));
        assertSame(matcher, builder.getTransaction().getResponseMatcher());
        builder.queueMessages(0);
        assertEquals(0, builder.getTransaction().getMessageTimeout());
        assertNull(builder.getTransaction().getResponseMatcher());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeTimeout() {
        TransactionBuilder builder = new TransactionBuilder("queue", mock(AbstractBTLEDeviceSupport.class), 0);
        builder.queueMessages(-1);
    }

    @Test
    public void chunksWaitOnlyOnFinalFragmentAndMatchWholeMessage() {
        AbstractBTLEDeviceSupport support = mock(AbstractBTLEDeviceSupport.class);
        when(support.getMTU(0)).thenReturn(23);
        TransactionBuilder builder = new TransactionBuilder("chunks", support, 0);
        BluetoothGattCharacteristic characteristic = mock(BluetoothGattCharacteristic.class);
        byte[] message = {1, 2, 3, 4, 5};
        builder.writeChunkedData(characteristic, message, 2).queueMessages();
        java.util.List<BtLEAction> actions = builder.getTransaction().getActions();
        assertEquals(3, actions.size());
        assertNull(((WriteAction) actions.get(0)).getMessage());
        assertNull(((WriteAction) actions.get(1)).getMessage());
        assertArrayEquals(message, ((WriteAction) actions.get(2)).getMessage());
    }
}
