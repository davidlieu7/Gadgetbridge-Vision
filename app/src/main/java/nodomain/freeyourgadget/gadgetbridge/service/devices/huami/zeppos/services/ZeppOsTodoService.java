/* Copyright 2026 Gadgetbridge Contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.services;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.model.Reminder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.AbstractZeppOsService;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.ZeppOsSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.ZeppOsTransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/** Synchronizes only Gadgetbridge-owned To-dos. Each mutation requires ACK and list readback. */
public class ZeppOsTodoService extends AbstractZeppOsService {
    private static final Logger LOG = LoggerFactory.getLogger(ZeppOsTodoService.class);
    public static final short ENDPOINT = 0x0012;
    public static final int MAX_TEXT_BYTES = ZeppOsTodoCodec.MAX_TEXT_BYTES;
    public static final String PREF_VERSION = "zepp_os_todo_version";
    public static final String PREF_SLOT_COUNT = "zepp_os_todo_reminder_slots";
    private static final String PREF_IDS = "zepp_os_todo_reminder_ids";
    private static final String PREF_TIMESTAMPS = "zepp_os_todo_reminder_timestamps";
    private static final long TIMEOUT_MS = 10000;

    private enum Phase { IDLE, CAPABILITIES, BASELINE, ACK, VERIFY }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> fail("Timed out; no automatic retry");
    private final ArrayDeque<Mutation> mutations = new ArrayDeque<>();
    private Phase phase = Phase.IDLE;
    private int version;
    private int maximum;
    private Map<String, Long> ids;
    private Map<String, Long> timestamps;
    private List<Desired> queued;
    private List<Desired> active;
    private Mutation pending;
    private boolean syncOnInitialization;

    private static final class Desired {
        final String key;
        final String text;
        final long timestamp;
        final int repeat;
        Desired(final Reminder reminder) {
            if (reminder == null || reminder.getDate() == null) {
                throw new IllegalArgumentException("Missing To-do reminder or date");
            }
            key = reminder.getReminderId(); text = reminder.getMessage();
            timestamp = reminder.getDate().getTime() / 1000L;
            switch (reminder.getRepetition()) {
                case Reminder.ONCE: repeat = 1; break;
                case Reminder.EVERY_DAY: repeat = 2; break;
                case Reminder.EVERY_WEEK:
                    final Calendar day = Calendar.getInstance();
                    day.setTime(reminder.getDate());
                    final int weekday = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday = 0.
                    repeat = 4 | (32 << weekday); break;
                case Reminder.EVERY_MONTH: repeat = 8; break;
                case Reminder.EVERY_YEAR: repeat = 16; break;
                default: throw new IllegalArgumentException("Unsupported To-do repetition");
            }
            if (key == null || key.isEmpty() || timestamp <= 0 || timestamp > ZeppOsTodoCodec.UINT32_MAX) {
                throw new IllegalArgumentException("Invalid To-do reminder ID or time");
            }
        }

        ZeppOsTodoCodec.Item item(final long id, final int version) {
            final Calendar date = Calendar.getInstance();
            date.setTimeInMillis(timestamp * 1000L);
            date.set(Calendar.HOUR_OF_DAY, 0); date.set(Calendar.MINUTE, 0);
            date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0);
            return new ZeppOsTodoCodec.Item(id, text, 0, version >= 2 ? timestamp : 0,
                    date.getTimeInMillis() / 1000L, timestamp, repeat, 0);
        }
    }

    private static final class Mutation {
        final String key;
        final long id;
        final ZeppOsTodoCodec.Item item;
        final long timestamp;
        Mutation(final String key, final long id, final ZeppOsTodoCodec.Item item, final long timestamp) {
            this.key = key; this.id = id; this.item = item;
            this.timestamp = timestamp;
        }
    }

    public ZeppOsTodoService(final ZeppOsSupport support) { super(support, false); }
    @Override public short getEndpoint() { return ENDPOINT; }

    public static int getSlotCount(final Prefs prefs) {
        return hasSupportedVersion(prefs)
                ? Math.max(0, prefs.getInt(PREF_SLOT_COUNT, 0)) : 0;
    }

    public static boolean hasSupportedVersion(final Prefs prefs) {
        return ZeppOsTodoCodec.supportedVersion(prefs.getInt(PREF_VERSION, 0));
    }

    public static boolean usesTodoService(final Prefs prefs) {
        // -1 means the endpoint is present but its version is unknown.
        return prefs.getInt(PREF_VERSION, 0) != 0;
    }

    public synchronized boolean hasUnsupportedVersion() {
        return version != 0 && !ZeppOsTodoCodec.supportedVersion(version);
    }

    @Override public synchronized void initialize(final ZeppOsTransactionBuilder builder) {
        reset();
        version = 0; maximum = 0;
        syncOnInitialization = true;
        evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences(PREF_VERSION, -1));
        evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences(PREF_SLOT_COUNT, 0));
        requestCapabilities(builder);
    }

    private void requestCapabilities(final ZeppOsTransactionBuilder builder) {
        phase = Phase.CAPABILITIES;
        armTimeout();
        write(builder, ZeppOsTodoCodec.CAPABILITIES_REQUEST);
    }

    public synchronized void sendReminders(final ZeppOsTransactionBuilder builder, final List<? extends Reminder> reminders) {
        if (hasUnsupportedVersion()) {
            LOG.warn("To-do service is not ready; reminder sync not sent"); return;
        }
        try {
            queued = validateReminders(reminders);
        } catch (final IllegalArgumentException e) { LOG.warn("Invalid To-do reminder batch: {}", e.getMessage()); return; }
        if (version == 0) {
            if (phase == Phase.IDLE) { requestCapabilities(builder); }
            return;
        }
        if (phase == Phase.IDLE) {
            active = queued; queued = null;
            phase = Phase.BASELINE;
            armTimeout();
            write(builder, ZeppOsTodoCodec.LIST_REQUEST);
        }
    }

    private List<Desired> validateReminders(final List<? extends Reminder> reminders) {
        if (reminders == null) { throw new IllegalArgumentException("Missing reminder list"); }
        final List<Desired> desired = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        final int validationVersion = version == 0 ? 1 : version;
        for (final Reminder reminder : reminders) {
            final Desired entry = new Desired(reminder);
            if (!keys.add(entry.key)) { throw new IllegalArgumentException("Duplicate reminder ID"); }
            ZeppOsTodoCodec.upsert(entry.item(1, validationVersion), validationVersion);
            desired.add(entry);
        }
        return desired;
    }

    @Override public synchronized void handlePayload(final byte[] payload) {
        if (payload.length == 0) { return; }
        try {
            switch (payload[0]) {
                case ZeppOsTodoCodec.CAPABILITIES_RESPONSE:
                    if (phase != Phase.CAPABILITIES) { return; }
                    if (payload.length != 4) { throw new IllegalArgumentException("Invalid To-do capability length"); }
                    version = payload[1] & 0xff;
                    if (version == 0) { version = -1; }
                    maximum = (payload[2] & 0xff) | ((payload[3] & 0xff) << 8);
                    evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences(PREF_VERSION, version));
                    if (hasUnsupportedVersion()) {
                        fail("Unsupported To-do version " + version);
                        getSupport().onTodoCapabilitiesUnsupported();
                        return;
                    }
                    LOG.info("To-do capability: version={}, maximum={}", version, maximum);
                    loadIds();
                    phase = Phase.BASELINE; armTimeout();
                    write("read To-dos", ZeppOsTodoCodec.LIST_REQUEST);
                    break;
                case ZeppOsTodoCodec.LIST_RESPONSE:
                    final Map<Long, ZeppOsTodoCodec.Item> items = ZeppOsTodoCodec.list(payload, version, maximum);
                    LOG.info("To-do list: {} items, phase={}", items.size(), phase);
                    loadIds();
                    publishSlots(items);
                    if (phase == Phase.BASELINE) {
                        handler.removeCallbacks(timeout);
                        if (active == null) { active = queued; queued = null; }
                        if (syncOnInitialization) {
                            syncOnInitialization = false;
                            if (active == null) {
                                try (DBHandler db = GBApplication.acquireDB()) {
                                    active = validateReminders(DBHelper.getReminders(getSupport().getDevice(), db.getDaoSession()));
                                }
                            }
                        }
                        if (active == null) { finish(); break; }
                        plan(items);
                        sendNext();
                    } else if (phase == Phase.VERIFY) {
                        final ZeppOsTodoCodec.Item actual = items.get(pending.id);
                        if (pending.item == null ? actual != null : !pending.item.equals(actual)) {
                            fail("To-do readback did not match; no automatic retry"); return;
                        }
                        LOG.info("To-do {} verified, id={}", pending.item == null ? "delete" : "update", pending.id);
                        if (pending.item == null) {
                            ids.remove(pending.key);
                            timestamps.remove(pending.key);
                        } else {
                            timestamps.put(pending.key, pending.timestamp);
                        }
                        saveIds();
                        pending = null; sendNext();
                    }
                    break;
                case ZeppOsTodoCodec.UPSERT_ACK:
                case ZeppOsTodoCodec.DELETE_ACK:
                    if (phase != Phase.ACK || pending == null
                            || payload[0] != (pending.item == null ? ZeppOsTodoCodec.DELETE_ACK : ZeppOsTodoCodec.UPSERT_ACK)) { return; }
                    if (payload.length != 2 || payload[1] != 1) { fail("Watch rejected To-do mutation"); return; }
                    phase = Phase.VERIFY; armTimeout();
                    write("verify To-do", ZeppOsTodoCodec.LIST_REQUEST);
                    break;
                case ZeppOsTodoCodec.CHANGED:
                    // Refresh counts, but never turn a watch notification into an unsolicited mutation.
                    if (phase == Phase.IDLE && ZeppOsTodoCodec.supportedVersion(version)) {
                        phase = Phase.BASELINE; armTimeout();
                        write("refresh changed To-dos", ZeppOsTodoCodec.LIST_REQUEST);
                    }
                    break;
                default: LOG.warn("Unexpected To-do command {}", payload[0] & 0xff);
            }
        } catch (final Exception e) { fail(e.getMessage()); }
    }

    private void loadIds() throws JSONException {
        final JSONObject stored = new JSONObject(getDevicePrefs().getString(PREF_IDS, "{}"));
        final Map<String, Long> loaded = new LinkedHashMap<>();
        final Set<Long> unique = new HashSet<>();
        final Iterator<String> keys = stored.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final Object value = stored.get(key);
            if (!(value instanceof Integer) && !(value instanceof Long)) {
                throw new IllegalArgumentException("Managed To-do ID must be an integer");
            }
            final long id = ((Number) value).longValue();
            if (key.isEmpty() || id <= 0 || id > ZeppOsTodoCodec.UINT32_MAX || !unique.add(id)) {
                throw new IllegalArgumentException("Invalid managed To-do IDs; synchronization stopped");
            }
            loaded.put(key, id);
        }
        ids = loaded;
        final JSONObject storedTimes = new JSONObject(getDevicePrefs().getString(PREF_TIMESTAMPS, "{}"));
        timestamps = new LinkedHashMap<>();
        for (final String key : ids.keySet()) {
            if (!storedTimes.has(key)) { continue; }
            final Object value = storedTimes.get(key);
            if (!(value instanceof Integer) && !(value instanceof Long)
                    || ((Number) value).longValue() <= 0 || ((Number) value).longValue() > ZeppOsTodoCodec.UINT32_MAX) {
                throw new IllegalArgumentException("Invalid managed To-do timestamp");
            }
            timestamps.put(key, ((Number) value).longValue());
        }
    }

    private void saveIds() {
        // Persist before sending a create so reconnects reuse its ID even if its ACK was lost.
        // Synchronous persistence is required for ownership; preferences events are asynchronous.
        timestamps.keySet().retainAll(ids.keySet());
        if (!getDevicePrefs().getPreferences().edit()
                .putString(PREF_IDS, new JSONObject(ids).toString())
                .putString(PREF_TIMESTAMPS, new JSONObject(timestamps).toString()).commit()) {
            throw new IllegalArgumentException("Could not persist managed To-do IDs");
        }
    }

    private void publishSlots(final Map<Long, ZeppOsTodoCodec.Item> items) {
        if (ids == null) { throw new IllegalArgumentException("Managed To-do IDs not loaded"); }
        final Set<Long> managed = new HashSet<>(ids.values());
        int unmanaged = 0;
        for (final long id : items.keySet()) { if (!managed.contains(id)) { unmanaged++; } }
        evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences(PREF_SLOT_COUNT, Math.max(0, maximum - unmanaged)));
    }

    private void plan(final Map<Long, ZeppOsTodoCodec.Item> items) {
        final Map<String, Long> plannedIds = new LinkedHashMap<>(ids);
        final Map<String, Desired> wanted = new LinkedHashMap<>();
        for (final Desired entry : active) { wanted.put(entry.key, entry); }
        final Set<Long> used = new HashSet<>(items.keySet()); used.addAll(plannedIds.values());
        int projected = items.size();
        for (final Map.Entry<String, Long> entry : ids.entrySet()) {
            final ZeppOsTodoCodec.Item existing = items.get(entry.getValue());
            if (existing != null && existing.source != 0) {
                throw new IllegalArgumentException("Managed To-do ID belongs to another source");
            }
            if (!wanted.containsKey(entry.getKey()) && items.containsKey(entry.getValue())) {
                mutations.add(new Mutation(entry.getKey(), entry.getValue(), null, 0)); projected--;
            }
        }
        long nextId = System.currentTimeMillis() / 1000L;
        for (final Desired desired : active) {
            Long id = ids.get(desired.key);
            // Expired one-off reminders must not be recreated after the watch removes them.
            if (desired.repeat == 1 && desired.timestamp < System.currentTimeMillis() / 1000L
                    && (id == null || !items.containsKey(id))) { continue; }
            if (id == null) {
                while (used.contains(nextId)) { nextId++; }
                if (nextId > ZeppOsTodoCodec.UINT32_MAX) { throw new IllegalArgumentException("To-do ID space exhausted"); }
                id = nextId++; plannedIds.put(desired.key, id); used.add(id);
            }
            final ZeppOsTodoCodec.Item existing = items.get(id);
            final ZeppOsTodoCodec.Item planned = desired.item(id, version);
            if (existing == null) { projected++; }
            // Preserve completion and recurring dates when the requested schedule is unchanged.
            // Version 1 lacks firstRemind, so use the last verified timestamp instead.
            final boolean sameSchedule = existing != null
                    && planned.repeat == existing.repeat && existing.source == planned.source
                    && (version >= 2 ? planned.firstRemind == existing.firstRemind
                    : Long.valueOf(desired.timestamp).equals(timestamps.get(desired.key))
                    || planned.date == existing.date && planned.time == existing.time);
            if (sameSchedule && planned.text.equals(existing.text)) {
                timestamps.put(desired.key, desired.timestamp);
            } else {
                final ZeppOsTodoCodec.Item item = sameSchedule
                        ? new ZeppOsTodoCodec.Item(id, desired.text, existing.source, existing.firstRemind,
                        existing.date, existing.time, existing.repeat, existing.completeTime) : planned;
                mutations.add(new Mutation(desired.key, id, item, desired.timestamp));
            }
        }
        if (projected > maximum) { throw new IllegalArgumentException("Watch To-do capacity is full; unmanaged items preserved"); }
        final Iterator<Map.Entry<String, Long>> stale = plannedIds.entrySet().iterator();
        while (stale.hasNext()) {
            final Map.Entry<String, Long> entry = stale.next();
            if (!wanted.containsKey(entry.getKey()) && !items.containsKey(entry.getValue())) { stale.remove(); }
        }
        ids = plannedIds;
        saveIds();
        LOG.info("To-do sync planned: {} individual mutations; existing watch-only items preserved", mutations.size());
    }

    private void sendNext() {
        handler.removeCallbacks(timeout);
        pending = mutations.poll();
        if (pending == null) { finish(); return; }
        phase = Phase.ACK; armTimeout();
        LOG.info("Sending To-do {} id={}", pending.item == null ? "delete" : "upsert", pending.id);
        write("sync one To-do", pending.item == null ? ZeppOsTodoCodec.delete(pending.id)
                : ZeppOsTodoCodec.upsert(pending.item, version));
    }

    private void finish() {
        handler.removeCallbacks(timeout);
        active = null; pending = null; phase = Phase.IDLE;
        if (queued != null) {
            active = queued; queued = null; phase = Phase.BASELINE; armTimeout();
            write("read To-dos before queued sync", ZeppOsTodoCodec.LIST_REQUEST);
        }
    }

    private void armTimeout() { handler.removeCallbacks(timeout); handler.postDelayed(timeout, TIMEOUT_MS); }
    private synchronized void fail(final String reason) { LOG.warn("To-do sync stopped: {}", reason); reset(); }
    private void reset() {
        handler.removeCallbacksAndMessages(null); mutations.clear(); pending = null;
        queued = null; active = null; phase = Phase.IDLE; syncOnInitialization = false;
    }
    @Override public synchronized void dispose() {
        reset(); version = 0; maximum = 0; ids = null; timestamps = null;
    }
}
