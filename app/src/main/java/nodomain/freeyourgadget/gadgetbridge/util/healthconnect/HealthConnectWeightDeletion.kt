package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectSyncStateDao
import nodomain.freeyourgadget.gadgetbridge.entities.ManualWeightSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.WeightSyncer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId

object HealthConnectWeightDeletion {
    private val LOG = LoggerFactory.getLogger(HealthConnectWeightDeletion::class.java)

    @JvmStatic
    fun deleteManualSample(context: Context, sample: ManualWeightSample) {
        val clientRecordId = WeightSyncer.manualClientRecordId(sample)
        withClient(context, "delete the manual weight $clientRecordId") { client ->
            client.deleteRecords(
                WeightRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(clientRecordId)
            )
        }
    }

    /**
     * Deletes the weights Gadgetbridge wrote at the time of a deleted device reading.
     */
    @JvmStatic
    fun deleteDeviceSample(context: Context, timestampMillis: Long) {
        withClient(context, "delete the weight at $timestampMillis") { client ->
            val time = Instant.ofEpochMilli(timestampMillis)
            client.deleteRecords(WeightRecord::class, TimeRangeFilter.between(time, time.plusMillis(1)))
        }
    }

    /**
     * Writes a restored device reading back, if it had already been synced. Later readings are left to the next sync.
     */
    @JvmStatic
    fun restoreDeviceSample(context: Context, device: GBDevice, sample: WeightSample) {
        withClient(context, "restore the weight at ${sample.timestamp}") { client ->
            if (sample.timestamp >= syncedUntilMillis(device)) {
                return@withClient
            }
            val time = Instant.ofEpochMilli(sample.timestamp)
            val record = WeightSyncer.convertSample(
                sample,
                ZoneId.systemDefault().rules.getOffset(time),
                Metadata.autoRecorded(HealthConnectUtils.healthConnectDevice(context, device)),
                device.aliasOrName
            ) ?: return@withClient
            HealthConnectUtils.insertRecords(listOf(record), client)
        }
    }

    private fun syncedUntilMillis(device: GBDevice): Long = GBApplication.acquireDbReadOnly().use { db ->
        val dbDevice = DBHelper.findDevice(device, db.daoSession) ?: return@use 0L
        db.daoSession.healthConnectSyncStateDao.queryBuilder()
            .where(
                HealthConnectSyncStateDao.Properties.DeviceId.eq(dbDevice.id),
                HealthConnectSyncStateDao.Properties.DataType.eq(HealthConnectPermissionManager.HealthConnectDataType.WEIGHT.name)
            )
            .unique()
            ?.lastSyncTimestamp?.times(1000L) ?: 0L
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun withClient(context: Context, what: String, action: suspend (HealthConnectClient) -> Unit) {
        val appContext = context.applicationContext
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (!HealthConnectPermissionManager.isHealthConnectEnabled(appContext)) {
                    return@launch
                }
                val grantedPermissions = GBApplication.getPrefs().preferences.getStringSet(
                    HealthConnectPermissionManager.PREF_KEY_LAST_GRANTED_HC_PERMISSIONS,
                    emptySet()
                ) ?: emptySet()
                if (HealthPermission.getWritePermission(WeightRecord::class) !in grantedPermissions) {
                    return@launch
                }
                val healthConnectClient = HealthConnectClientProvider.healthConnectInit(appContext) ?: return@launch
                action(healthConnectClient)
                LOG.debug("Health Connect: did {}", what)
            } catch (e: Exception) {
                LOG.warn("Health Connect: failed to {}", what, e)
            }
        }
    }
}
