/*  Copyright (C) 2026 Vitaliy Tomin

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

package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.requests.Fit3HealthCycle;

public class Fit3HealthCycleTest {

    /** The capture was taken at UTC+8. */
    private static final ZoneId ZONE = ZoneOffset.ofHours(8);

    /**
     * Period logged Oct 1-5, 26-day cycle: flows, five predictions, profile.
     * Two things are normalised so one update time fits the whole message:
     * the device-info times (c0/c0/d1 -> cd) and the predictions' update time
     * (d0 -> cd); the builder stamps everything with "now".
     */
    private static final String FIRST_PUSH = ""
            + "8205e0a0000519010002000003043304c067284505c067284506d1672845070171012400000075adc2e9feb2fe929b91"
            + "49270d2a87bbf105cd6728450180941e45030074b70110011100012400000075ce874864131976bc9145122e21b3d30e"
            + "05cd6728450100e61f45030074b70110011100012400000075bd99c7afc491f49fbc42c35dd1b8901805cd6728450180"
            + "372145030074b701100111000124000000753aa360d7e14c188e6545a5955a6be75905cd6728450100892245030074b7"
            + "011001110000240000007547f359e7997be798ea4a7e8b064e21a105cd6728450180da2345030074b701100111007701"
            + "54000000752511be475d4951b7a4404f923323c59b05d067284501000000001005000000111a0000001200da85451300"
            + "208b4514006690451580fd96451680fd96451700000000180419010000001a010000001b00000000015400000075dbf3"
            + "9f506f255aa1ab4bb3e0f1714b9705d067284501000000001005000000111a000000120021a845130067ad451400adb2"
            + "45158044b945168044b9451700000000180419010000001a010000001b000000000154000000755354ff7df8a9c599bd"
            + "41850f2bd3ba0805d067284501000000001005000000111a00000012004c414513009246451400d84b4515806f524516"
            + "806f524517004c4145180419010000001a010000001b00000000015400000075416f27dfc90439bc384f4677d6595504"
            + "05d067284501000000001005000000111a00000012009363451300d9684514001f6e451580b674451680b67445170000"
            + "0000180419010000001a010000001b000000000054000000755231cd7f6c05e2b1ee487f35e6d85c4905d06728450100"
            + "0000001005000000111a0000001200051f4513004b244514009129451580283045168028304517000000001804190100"
            + "00001a010000001b0000000074002500000075677392757b58f88b914ea0f28e2b6c3805cd6728451080941e45110500"
            + "0000121a000000";

    /** Five predictions deleted, factor set to menopause. Device-info times normalised to e6. */
    private static final String DELETE_AND_FACTOR = ""
            + "8204e0a3000519010002000003043304e567284505e467284506e7672845070118017563c7d2b241da9e82bf4eee7fc7"
            + "621e5c760d05e66728450175a0fdb39b43ee96b6b14cfd63edebe297760d05e667284501750d5bde236d5607974e4103"
            + "a623ac852c760d05e66728450175e07498ac12f10eb3f4417d54f035419e760d05e66728450075cc2818ec1ebb1fb1bc"
            + "4a4ae00f3fe07d760d05e66728457b00290000007587b60b1ab02b4d9afc4e06258cf398f605e667284501e667284502"
            + "00000000030074b701100311ff";

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static Deque<byte[]> uuids(String... hexes) {
        Deque<byte[]> q = new ArrayDeque<>();
        for (String h : hexes) {
            q.add(hex(h));
        }
        return q;
    }

    private static String devInfo(String time) {
        return "0519010002000003043304" + time + "05" + time + "06" + time + "0701";
    }

    @Test
    public void firstPushMatchesTheCapture() {
        String expected = FIRST_PUSH
                .replace("0519010002000003043304c067284505c067284506d16728450701", devInfo("cd672845"))
                .replace("05d0672845", "05cd672845");

        Fit3HealthCycle.Push push = new Fit3HealthCycle.Push();
        push.periodDays = 5;
        push.cycleDays = 26;
        push.lastPeriodStart = LocalDate.of(2026, 10, 1);
        push.profileUuid = hex("677392757b58f88b914ea0f28e2b6c38");
        Deque<byte[]> flowUuids = uuids(
                "adc2e9feb2fe929b9149270d2a87bbf1", "ce874864131976bc9145122e21b3d30e",
                "bd99c7afc491f49fbc42c35dd1b89018", "3aa360d7e14c188e6545a5955a6be759",
                "47f359e7997be798ea4a7e8b064e21a1");
        for (int d = 0; d < 5; d++) {
            push.flows.add(new Fit3HealthCycle.Flow(flowUuids.poll(), LocalDate.of(2026, 10, 1 + d)));
        }
        // Predicted in date order, sent in the capture's (unordered) order.
        Deque<byte[]> predictionUuids = uuids(
                "5231cd7f6c05e2b1ee487f35e6d85c49", "5354ff7df8a9c599bd41850f2bd3ba08",
                "416f27dfc90439bc384f4677d6595504", "2511be475d4951b7a4404f923323c59b",
                "dbf39f506f255aa1ab4bb3e0f1714b97");
        List<Fit3HealthCycle.Prediction> p = Fit3HealthCycle.predict(
                push.lastPeriodStart, 5, 26, LocalDate.of(2026, 10, 8), predictionUuids::poll);
        push.predictions.addAll(Arrays.asList(p.get(3), p.get(4), p.get(1), p.get(2), p.get(0)));

        byte[] built = Fit3HealthCycle.buildRequest(0xa0, 0x452867cd, ZONE, push);
        assertEquals(expected, toHex(built));
    }

    @Test
    public void predictionsFollowTheCapturedDates() {
        List<Fit3HealthCycle.Prediction> p = Fit3HealthCycle.predict(
                LocalDate.of(2026, 10, 1), 5, 26, LocalDate.of(2026, 10, 8), () -> new byte[16]);
        assertEquals(Fit3HealthCycle.PREDICTION_COUNT, p.size());
        Fit3HealthCycle.Prediction first = p.get(0);
        assertEquals(LocalDate.of(2026, 10, 1), first.periodStart);
        assertEquals(LocalDate.of(2026, 10, 5), first.periodEnd);
        assertEquals(LocalDate.of(2026, 10, 9), first.fertileStart);
        assertEquals(LocalDate.of(2026, 10, 14), first.fertileEnd);
        assertEquals(LocalDate.of(2026, 10, 14), first.ovulation);
        assertNull(first.originalPrediction);
        assertEquals(LocalDate.of(2026, 10, 27), p.get(1).originalPrediction);
        assertEquals(LocalDate.of(2027, 1, 13), p.get(4).periodStart);
    }

    @Test
    public void predictionsStartAtTheCycleContainingToday() {
        List<Fit3HealthCycle.Prediction> p = Fit3HealthCycle.predict(
                LocalDate.of(2026, 10, 1), 5, 26, LocalDate.of(2026, 11, 30), () -> new byte[16]);
        assertEquals(LocalDate.of(2026, 11, 22), p.get(0).periodStart);
    }

    @Test
    public void deletionsAndFactorMatchTheCapture() {
        String expected = DELETE_AND_FACTOR
                .replace("0519010002000003043304e567284505e467284506e76728450701", devInfo("e6672845"));

        Fit3HealthCycle.Push push = new Fit3HealthCycle.Push();
        for (String uuid : new String[]{
                "63c7d2b241da9e82bf4eee7fc7621e5c", "a0fdb39b43ee96b6b14cfd63edebe297",
                "0d5bde236d5607974e4103a623ac852c", "e07498ac12f10eb3f4417d54f035419e",
                "cc2818ec1ebb1fb1bc4a4ae00f3fe07d"}) {
            push.deletions.add(new Fit3HealthCycle.Deletion(hex(uuid), Fit3HealthCycle.TYPE_PREDICTION));
        }
        push.factorUuid = hex("87b60b1ab02b4d9afc4e06258cf398f6");
        push.factor = Fit3HealthCycle.FACTOR_MENOPAUSE;

        byte[] built = Fit3HealthCycle.buildRequest(0xa3, 0x452867e6, ZONE, push);
        assertEquals(expected, toHex(built));
    }

    @Test
    public void onlyNoneAndBreastfeedingKeepPredictions() {
        assertTrue(Fit3HealthCycle.factorAllowsPredictions(Fit3HealthCycle.FACTOR_NONE));
        assertTrue(Fit3HealthCycle.factorAllowsPredictions(Fit3HealthCycle.FACTOR_BREASTFEEDING));
        assertFalse(Fit3HealthCycle.factorAllowsPredictions(Fit3HealthCycle.FACTOR_PREGNANCY));
        assertFalse(Fit3HealthCycle.factorAllowsPredictions(Fit3HealthCycle.FACTOR_MENOPAUSE));
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
