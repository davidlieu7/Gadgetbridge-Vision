# Zepp OS To-do reminders

Zepp OS devices advertising endpoint `0x0012` use the To-do protocol for
Gadgetbridge reminders. Other devices retain the legacy reminder service on
endpoint `0x0038`.
When both endpoints are advertised, To-do takes precedence. If its version is
unsupported, the legacy service is used when available. Unknown To-do versions
are never written using a guessed packet layout. An unsupported To-do version
without an advertised legacy endpoint sends no reminder mutations. Encryption
follows the watch's advertised service flags.
The existing reminder editor and database remain the source of managed records.
This service does not depend on Zepp Flow or an AI provider.

## Ownership and synchronization

Initialization reads capabilities and the item list before synchronizing saved
Gadgetbridge reminders, including changes made while disconnected.
A device-scoped mapping associates Gadgetbridge reminder UUIDs with watch IDs.
Only mapped records can be edited or deleted; unmanaged watch records are
preserved and deducted from the available capacity.

An explicit synchronization reads a fresh baseline, validates the batch and
capacity, then sends individual mutations serially. Each mutation requires an
acknowledgement and matching full-list readback before the next mutation.
Requests arriving during initialization wait for capabilities and a valid list;
successive queued requests replace the pending batch with the latest state.
Timeouts, rejection, invalid ownership and mismatching readback stop the batch
without automatic mutation retries. Reconnection preserves the existing IDs
and starts a fresh synchronization from the saved database state.

Completion and advanced recurring dates are preserved for unchanged schedules.
Version 1 uses the last verified reminder timestamp because its packets omit
the original timestamp. Existing UUID-to-ID mappings remain compatible.
Synchronization is one-way from Gadgetbridge to the watch. Watch-side changes
are not imported into the Gadgetbridge reminder database; undated tasks and
editing completion status are not supported by the reminder editor.
Saved reminders stay visible when watch-only items reduce available capacity;
an over-capacity batch is rejected without deleting managed or unmanaged items.
Expired one-off reminders missing from the watch are not recreated.

## Protocol

The packet layout was established through logs and packet capture. Integers are
little-endian. To-do service versions 1–3 are supported; these are separate
from the watch's Zepp OS version. The capability reply selects the packet
layout; other versions are rejected. Version 1 omits the original-reminder
timestamp, while versions 2–3 include it.

| Command | Payload |
| --- | --- |
| `01` | Capability request |
| `02` | Version u8; maximum items u16 |
| `03` | List request |
| `04` | Item count u16; consecutive items |
| `05` | Upsert one item |
| `06` | Upsert status u8 (`01` means success) |
| `07` | Delete item ID u32 |
| `08` | Delete status u8 (`01` means success) |
| `0d` | Watch change; request a fresh list |

An item contains an ID, null-terminated UTF-8 text, source, first-reminder
timestamp for versions 2–3, date, time, repetition flags and completion time.
New and edited reminders require nonempty text of at most 150 UTF-8 bytes.
Calendar events use endpoint `0x0007` and do not reserve To-do slots; the legacy
reminder service keeps its existing calendar-slot reservation behavior.
Supported repetitions are once, daily, weekly, monthly and yearly.
Hardware acknowledgement and readback determine
whether a requested synchronization succeeded.

Tests cover encoding, UTF-8 limits, malformed lists, advertised capacity,
stable IDs, ownership, lost responses and verification failures. Creation,
editing and deletion have also been verified on an Amazfit Balance 3. Versions
1–3 are covered by automated tests; other watch models still need hardware
verification.
