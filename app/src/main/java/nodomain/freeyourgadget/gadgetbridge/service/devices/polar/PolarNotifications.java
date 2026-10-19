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
package nodomain.freeyourgadget.gadgetbridge.service.devices.polar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventCallControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.proto.polar.PolarProto;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

/**
 * Builds the watch's push notifications (PbPftpPnsHDNotification) and maps the buttons
 * pressed on the watch (PbPftpPnsDHNotificationResponse) back to Gadgetbridge events.
 *
 * <p>The watch shows up to four free action buttons (slots A-D) plus clear, and positive /
 * negative for calls. It only reports which slot was pressed, so this class remembers what each
 * slot of a displayed notification stands for. Reply actions cannot take free text on the watch;
 * they are offered once per configured canned reply instead.
 */
class PolarNotifications {
    static final int CALL_NOTIFICATION_ID = 0x7FFFFFFF;

    private static final PolarProto.PbPftpPnsHDAttributeType[] ACTION_SLOTS = {
            PolarProto.PbPftpPnsHDAttributeType.A_ACTION_LABEL,
            PolarProto.PbPftpPnsHDAttributeType.B_ACTION_LABEL,
            PolarProto.PbPftpPnsHDAttributeType.C_ACTION_LABEL,
            PolarProto.PbPftpPnsHDAttributeType.D_ACTION_LABEL,
    };
    static final int ACTION_SLOT_COUNT = ACTION_SLOTS.length;

    /** What one button of a displayed notification does. */
    private static final class SlotAction {
        final long handle;
        @Nullable
        final String reply;
        @Nullable
        final String phoneNumber;

        SlotAction(final long handle, @Nullable final String reply, @Nullable final String phoneNumber) {
            this.handle = handle;
            this.reply = reply;
            this.phoneNumber = phoneNumber;
        }
    }

    private static final class Displayed {
        final PolarProto.PbLocalDateTime issueTime;
        /** indexed like {@link #ACTION_SLOTS} */
        final List<SlotAction> actions;

        Displayed(final PolarProto.PbLocalDateTime issueTime, final List<SlotAction> actions) {
            this.issueTime = issueTime;
            this.actions = actions;
        }
    }

    private final Map<Integer, Displayed> displayed = new HashMap<>();

    /**
     * @param cannedReplies the user's canned replies, offered for reply actions
     * @param clearLabel    label of the button that dismisses the notification
     */
    synchronized PolarProto.PbPftpPnsHDNotification create(@NonNull final NotificationSpec spec,
                                                           @NonNull final List<String> cannedReplies,
                                                           @NonNull final String clearLabel) {
        final PolarProto.PbLocalDateTime issueTime = localDateTime(spec.getWhen() > 0 ? spec.getWhen() : System.currentTimeMillis());
        final List<SlotAction> actions = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        if (spec.getAttachedActions() != null) {
            // Plain actions first, then one button per canned reply for the reply action
            NotificationSpec.Action replyAction = null;
            for (final NotificationSpec.Action action : spec.getAttachedActions()) {
                if (action == null) {
                    continue;
                }
                if (action.isReply()) {
                    if (replyAction == null) {
                        replyAction = action;
                    }
                } else if (action.getType() == NotificationSpec.Action.TYPE_WEARABLE_SIMPLE
                        || action.getType() == NotificationSpec.Action.TYPE_CUSTOM_SIMPLE) {
                    if (actions.size() < ACTION_SLOT_COUNT && !StringUtils.isNullOrEmpty(action.getTitle())) {
                        actions.add(new SlotAction(action.getHandle(), null, null));
                        labels.add(action.getTitle());
                    }
                }
            }
            if (replyAction != null) {
                // GB answers SMS itself; that synthetic reply action carries no handle
                final String phoneNumber = replyAction.getType() == NotificationSpec.Action.TYPE_SYNTHETIC_REPLY_PHONENR
                        ? spec.getPhoneNumber() : null;
                for (final String reply : cannedReplies) {
                    if (actions.size() >= ACTION_SLOT_COUNT) {
                        break;
                    }
                    actions.add(new SlotAction(replyAction.getHandle(), reply, phoneNumber));
                    labels.add(reply);
                }
            }
        }
        displayed.put(spec.getId(), new Displayed(issueTime, actions));

        final PolarProto.PbPftpPnsHDNotification.Builder builder = PolarProto.PbPftpPnsHDNotification.newBuilder()
                .setNotificationId(spec.getId())
                .setCategoryId(category(spec.getType()))
                .setAction(PolarProto.Action.CREATED)
                .setIssueTime(issueTime);
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.APPLICATION_NAME, spec.getSourceName());
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.TITLE,
                StringUtils.isNullOrEmpty(spec.getTitle()) ? spec.getSender() : spec.getTitle());
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.SUBTITLE, spec.getSubject());
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.MESSAGE, spec.getBody());
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.CLEAR_ACTION_LABEL, clearLabel);
        for (int i = 0; i < labels.size(); i++) {
            addAttribute(builder, ACTION_SLOTS[i], labels.get(i));
        }
        return builder.build();
    }

    synchronized PolarProto.PbPftpPnsHDNotification incomingCall(@NonNull final CallSpec call,
                                                                 @NonNull final String acceptLabel,
                                                                 @NonNull final String rejectLabel) {
        final PolarProto.PbLocalDateTime issueTime = localDateTime(System.currentTimeMillis());
        displayed.put(CALL_NOTIFICATION_ID, new Displayed(issueTime, new ArrayList<>()));

        final PolarProto.PbPftpPnsHDNotification.Builder builder = PolarProto.PbPftpPnsHDNotification.newBuilder()
                .setNotificationId(CALL_NOTIFICATION_ID)
                .setCategoryId(PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_INCOMINGCALL)
                .setAction(PolarProto.Action.CREATED)
                .setIssueTime(issueTime);
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.APPLICATION_NAME, call.getSourceName());
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.TITLE,
                StringUtils.isNullOrEmpty(call.getName()) ? call.getNumber() : call.getName());
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.MESSAGE,
                StringUtils.isNullOrEmpty(call.getName()) ? null : call.getNumber());
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.POSITIVE_ACTION_LABEL, acceptLabel);
        addAttribute(builder, PolarProto.PbPftpPnsHDAttributeType.NEGATIVE_ACTION_LABEL, rejectLabel);
        return builder.build();
    }

    /**
     * @param evenIfUnknown also remove notifications this instance did not send, e.g. ones
     *                      left on the watch by a previous connection or by Polar Flow
     * @return the removal message, or null if the watch is not showing that notification
     */
    @Nullable
    synchronized PolarProto.PbPftpPnsHDNotification remove(final int id, final boolean evenIfUnknown) {
        final Displayed shown = displayed.remove(id);
        if (shown == null && !evenIfUnknown) {
            return null;
        }
        return PolarProto.PbPftpPnsHDNotification.newBuilder()
                .setNotificationId(id)
                .setCategoryId(PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_OTHER)
                .setAction(PolarProto.Action.REMOVED)
                .setIssueTime(shown != null ? shown.issueTime : localDateTime(System.currentTimeMillis()))
                .build();
    }

    synchronized void clear() {
        displayed.clear();
    }

    /** @return the event for a button pressed on the watch, or null if there is nothing to do */
    @Nullable
    synchronized GBDeviceEvent onResponse(@NonNull final PolarProto.PbPftpPnsDHNotificationResponse response) {
        if (response.getAttributesCount() == 0) {
            return null;
        }
        final int id = response.getNotificationId();
        final PolarProto.PbPftpPnsDHAttributeType pressed = response.getAttributes(0).getType();

        if (id == CALL_NOTIFICATION_ID) {
            final GBDeviceEventCallControl callControl = new GBDeviceEventCallControl();
            switch (pressed) {
                case POSITIVE_ACTION:
                    callControl.event = GBDeviceEventCallControl.Event.ACCEPT;
                    return callControl;
                case NEGATIVE_ACTION:
                case CLEAR_ACTION:
                    callControl.event = GBDeviceEventCallControl.Event.REJECT;
                    return callControl;
                default:
                    return null;
            }
        }

        final GBDeviceEventNotificationControl control = new GBDeviceEventNotificationControl();
        if (pressed == PolarProto.PbPftpPnsDHAttributeType.CLEAR_ACTION) {
            control.handle = id;
            control.event = GBDeviceEventNotificationControl.Event.DISMISS;
            return control;
        }

        final int slot = pressed.getNumber() - PolarProto.PbPftpPnsDHAttributeType.A_ACTION.getNumber();
        final Displayed shown = displayed.get(id);
        if (shown == null || slot < 0 || slot >= shown.actions.size()) {
            return null;
        }
        final SlotAction action = shown.actions.get(slot);
        control.handle = action.handle;
        control.reply = action.reply;
        control.phoneNumber = action.phoneNumber;
        control.event = GBDeviceEventNotificationControl.Event.REPLY;
        return control;
    }

    private static void addAttribute(final PolarProto.PbPftpPnsHDNotification.Builder builder,
                                     final PolarProto.PbPftpPnsHDAttributeType type,
                                     @Nullable final String value) {
        if (StringUtils.isNullOrEmpty(value)) {
            return;
        }
        builder.addAttributes(PolarProto.PbPftpPnsHDAttribute.newBuilder()
                .setType(type)
                .setData(value)
                .setAttributeFullSize(value.length()));
    }

    private static PolarProto.PbPftpPnsHDCategoryID category(@Nullable final NotificationType type) {
        if (type == null) {
            return PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_OTHER;
        }
        switch (type) {
            case GENERIC_PHONE:
                return PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_MISSEDCALL;
            case GENERIC_CALENDAR:
                return PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_SCHEDULE;
            case GENERIC_ALARM_CLOCK:
                return PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_ALARM;
            case GENERIC_NAVIGATION:
                return PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_LOCATION;
            default:
                break;
        }
        switch (type.getGenericType()) {
            case "generic_email":
                return PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_EMAIL;
            case "generic_sms":
            case "generic_chat":
            case "generic_social":
                return PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_SOCIAL;
            default:
                return PolarProto.PbPftpPnsHDCategoryID.CATEGORY_ID_OTHER;
        }
    }

    static PolarProto.PbLocalDateTime localDateTime(final long timestamp) {
        final GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTimeInMillis(timestamp);
        return PolarProto.PbLocalDateTime.newBuilder()
                .setDate(PolarProto.PbDate.newBuilder()
                        .setYear(calendar.get(Calendar.YEAR))
                        .setMonth(calendar.get(Calendar.MONTH) + 1)
                        .setDay(calendar.get(Calendar.DAY_OF_MONTH)))
                .setTime(PolarProto.PbTime.newBuilder()
                        .setHour(calendar.get(Calendar.HOUR_OF_DAY))
                        .setMinute(calendar.get(Calendar.MINUTE))
                        .setSeconds(calendar.get(Calendar.SECOND))
                        .setMillis(calendar.get(Calendar.MILLISECOND)))
                .setOBSOLETETrusted(true)
                .setTimeZoneOffset(TimeZone.getDefault().getOffset(timestamp) / 60_000)
                .build();
    }
}
