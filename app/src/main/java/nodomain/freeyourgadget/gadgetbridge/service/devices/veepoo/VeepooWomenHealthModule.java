/*  Copyright (C) 2025 Gadgetbridge contributors

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo;

import android.content.SharedPreferences;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

// ---- Women's health (cycle tracking), opcode 0x85 (vendor u2) -----------
// Fixed 20-byte frames on the main channel. Set: [1]=status, [2..5]=LMP year BE + month/day,
// [6]=cycle length, [7]=period length, rest zero. Read request: {0x85, 0x05}. The band acks
// a set with {0x85, result 1|0, status echo} and answers a read with the stored tuple; with
// womenTag==3 the date fields shift (yearLo in [3], status in [4], period in [10]).

/** Feature module for 0x85 women's health (cycle tracking). */
class VeepooWomenHealthModule {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooWomenHealthModule.class);

    private VeepooDeviceSupport support;

    VeepooWomenHealthModule(VeepooDeviceSupport support) {
        this.support = support;
    }

    private final Object womenLock = new Object();
    private boolean womenSetOutstanding = false;
    private boolean womenReadOutstanding = false;
    private final Runnable womenSetWatchdog = () -> {
        synchronized (womenLock) {
            if (!womenSetOutstanding) {
                return;
            }
            womenSetOutstanding = false;
        }
        LOG.warn("Women's health setting timed out without an acknowledgement");
        GB.toast(support.getContext(), R.string.veepoo_women_set_failed, Toast.LENGTH_SHORT, GB.WARN);
    };
    private final Runnable womenReadWatchdog = () -> {
        synchronized (womenLock) {
            if (!womenReadOutstanding) {
                return;
            }
            womenReadOutstanding = false;
        }
        LOG.warn("Women's health read-back timed out without a reply");
    };

    private static boolean isWomenSettingEnabled(SharedPreferences prefs) {
        return prefs.getInt(VeepooConstants.CONFIG_WOMEN_STATUS,
                VeepooConstants.WOMEN_STATUS_NONE) == VeepooConstants.WOMEN_STATUS_MENES;
    }

    /**
     * Pushes the stored women's settings to the band. Called on user-initiated changes and on
     * reconnect (see initializeDevice) so the watch keeps fresh LMP-relative state.
     */
    void applyWomenSettingFromPrefs(SharedPreferences prefs) {
        if (!prefs.getBoolean(VeepooConstants.CONFIG_WOMEN_SUPPORTED, false)) {
            LOG.warn("Skipping women's health push: firmware reported no support");
            return;
        }
        int status = prefs.getInt(VeepooConstants.CONFIG_WOMEN_STATUS, VeepooConstants.WOMEN_STATUS_NONE);
        String lmp = prefs.getString(VeepooConstants.CONFIG_WOMEN_LMP, null);
        int cycleLen = prefs.getInt(VeepooConstants.CONFIG_WOMEN_CYCLE_LENGTH,
                VeepooConstants.WOMEN_CYCLE_LENGTH_DEFAULT);
        int periodLen = prefs.getInt(VeepooConstants.CONFIG_WOMEN_PERIOD_LENGTH,
                VeepooConstants.WOMEN_PERIOD_LENGTH_DEFAULT);
        int year = 0;
        int month = 0;
        int day = 0;
        if (lmp != null) {
            try {
                java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                java.util.Date parsed = parser.parse(lmp);
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(parsed);
                year = cal.get(java.util.Calendar.YEAR);
                month = cal.get(java.util.Calendar.MONTH) + 1;
                day = cal.get(java.util.Calendar.DAY_OF_MONTH);
            } catch (java.text.ParseException e) {
                LOG.error("Stored women's LMP {} is not a valid date", lmp, e);
                return;
            }
        }
        sendWomenSet(status, year, month, day, cycleLen, periodLen);
    }

    /** Connect hook: re-pushes the stored tuple a few seconds after connect (vendor parity). */
    void scheduleConnectPush() {
        support.mainHandler.postDelayed(() -> {
            SharedPreferences womenPrefs = GBApplication.getDeviceSpecificSharedPrefs(support.getDevice().getAddress());
            if (!womenPrefs.getBoolean(VeepooConstants.CONFIG_WOMEN_SUPPORTED, false)
                    || !isWomenSettingEnabled(womenPrefs)) {
                return;
            }
            applyWomenSettingFromPrefs(womenPrefs);
        }, 8000L);
    }

    /**
     * Sends a women's-health set frame. status only supports NONE (0) and MENES (1) in the MVP;
     * PREREADY/PREING/MAMAMI are vendor-app extras and rejected with a warning until implemented.
     */
    private void sendWomenSet(int status, int lmpYear, int lmpMonth, int lmpDay, int cycleLen, int periodLen) {
        if (status != VeepooConstants.WOMEN_STATUS_NONE && status != VeepooConstants.WOMEN_STATUS_MENES) {
            LOG.warn("Women's health status {} not implemented yet; ignoring", status);
            return;
        }
        byte[] frame = new byte[20]; // fixed 20-byte Veepoo main-channel frame
        frame[0] = VeepooConstants.OP_WOMEN;
        frame[1] = (byte) status;
        if (status == VeepooConstants.WOMEN_STATUS_MENES) {
            frame[2] = (byte) ((lmpYear >> 8) & 0xFF);
            frame[3] = (byte) (lmpYear & 0xFF);
            frame[4] = (byte) lmpMonth;
            frame[5] = (byte) lmpDay;
            frame[6] = (byte) cycleLen;
            frame[7] = (byte) periodLen;
        }
        try {
            TransactionBuilder builder = support.performInitialized("set women's health");
            support.writeFrame(builder, frame);
            builder.queue();
        } catch (IOException e) {
            LOG.error("Cannot set women's health: not connected", e);
            GB.toast(support.getContext(), R.string.veepoo_women_set_failed, Toast.LENGTH_SHORT, GB.WARN);
            return;
        }
        synchronized (womenLock) {
            womenSetOutstanding = true;
        }
        support.mainHandler.removeCallbacks(womenSetWatchdog);
        support.mainHandler.postDelayed(womenSetWatchdog, 10000L);
        LOG.info("Women's health set sent (status={} lmp={}-{}-{} cycle={} period={})",
                status, lmpYear, lmpMonth, lmpDay, cycleLen, periodLen);
    }

    /** Requests the band's stored women's tuple ({0x85, 0x05}); one outstanding read at a time. */
    private void sendWomenRead() {
        synchronized (womenLock) {
            if (womenReadOutstanding) {
                return;
            }
            womenReadOutstanding = true;
        }
        try {
            TransactionBuilder builder = support.performInitialized("read women's health");
            support.writeFrame(builder, new byte[]{VeepooConstants.OP_WOMEN, (byte) VeepooConstants.WOMEN_OP_READ});
            builder.queue();
        } catch (IOException e) {
            synchronized (womenLock) {
                womenReadOutstanding = false;
            }
            LOG.error("Cannot read women's health: not connected", e);
            return;
        }
        support.mainHandler.removeCallbacks(womenReadWatchdog);
        support.mainHandler.postDelayed(womenReadWatchdog, 10000L);
    }

    /**
     * Handles a women's-health reply on the F008 main channel. [1] is the result (1 ok / 0 fail)
     * for both set acks and read replies; [2] echoes the op: 5 for the read reply, 0..4 for a set
     * ack (echoing the status). A byte 0x85 on F002 during ECG is a PPG light-channel marker and
     * never reaches this dispatcher (see onCharacteristicChanged), so no cross-talk guard is
     * needed here beyond rejecting short/implausible frames.
     */
    void handleFrame(byte[] data) {
        if (data == null || data.length < 3) {
            LOG.debug("Ignoring short 0x85 women's frame: {} bytes", data == null ? 0 : data.length);
            return;
        }
        int result = data[1] & 0xFF;
        if (result > 1) {
            LOG.warn("Implausible 0x85 women's frame [1]={}; ignoring", result);
            return;
        }
        int op = data[2] & 0xFF;
        synchronized (womenLock) {
            if (op == VeepooConstants.WOMEN_OP_READ) {
                if (!womenReadOutstanding) {
                    LOG.debug("Unexpected women's read reply: {}", VeepooDeviceSupport.hexBytes(data));
                    return;
                }
                womenReadOutstanding = false;
                support.mainHandler.removeCallbacks(womenReadWatchdog);
                handleWomenReadReply(data, result);
            } else if (op <= VeepooConstants.WOMEN_STATUS_MENES) {
                // Set ack: op echoes the requested status (NONE or MENES).
                if (!womenSetOutstanding) {
                    LOG.debug("Unexpected women's set ack: {}", VeepooDeviceSupport.hexBytes(data));
                    return;
                }
                womenSetOutstanding = false;
                support.mainHandler.removeCallbacks(womenSetWatchdog);
                if (result == 1) {
                    LOG.info("Women's health setting acknowledged (status {})", op);
                    sendWomenRead(); // read back the band state after each successful set
                } else {
                    LOG.warn("Band rejected the women's health setting (status {})", op);
                    GB.toast(support.getContext(), R.string.veepoo_women_set_failed, Toast.LENGTH_SHORT, GB.WARN);
                }
            } else {
                LOG.debug("Unhandled 0x85 women's op {}: {}", op, VeepooDeviceSupport.hexBytes(data));
            }
        }
    }

    /** Parses the read reply (>= 19 bytes) with the layout selected by the A7 pkg1 [12] tag. */
    private void handleWomenReadReply(byte[] data, int result) {
        if (data.length < 19) {
            LOG.warn("Women's read reply too short ({} bytes): {}", data.length, VeepooDeviceSupport.hexBytes(data));
            return;
        }
        if (result != 1) {
            LOG.warn("Band NAKed the women's read: {}", VeepooDeviceSupport.hexBytes(data));
            return;
        }
        boolean extended = support.womenTag == 3;
        int status = data[extended ? 4 : 3] & 0xFF;
        int year = extended
                ? (((data[5] & 0xFF) << 8) | (data[3] & 0xFF))
                : (((data[4] & 0xFF) << 8) | (data[5] & 0xFF));
        int month = data[6] & 0xFF;
        int day = data[7] & 0xFF;
        int cycleLen = data[8] & 0xFF;
        int periodLen = data[extended ? 10 : 9] & 0xFF;
        boolean plausible = status <= VeepooConstants.WOMEN_STATUS_MENES
                && year >= 1950 && year <= 2100
                && month >= 1 && month <= 12
                && day >= 1 && day <= 31
                && cycleLen >= VeepooConstants.WOMEN_CYCLE_LENGTH_MIN
                && cycleLen <= VeepooConstants.WOMEN_CYCLE_LENGTH_MAX
                && periodLen >= VeepooConstants.WOMEN_PERIOD_LENGTH_MIN
                && periodLen <= VeepooConstants.WOMEN_PERIOD_LENGTH_MAX;
        LOG.info("Women's band state ({} layout): status={} lmp={}-{}-{} cycle={} period={}{}",
                extended ? "extended" : "standard", status, year, month, day, cycleLen, periodLen,
                plausible ? "" : " [implausible values]");
    }

    void dispose() {
        support.mainHandler.removeCallbacks(womenSetWatchdog);
        support.mainHandler.removeCallbacks(womenReadWatchdog);
    }
}
