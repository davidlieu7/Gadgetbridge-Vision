package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.entities.ManualWeightSample
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.WeightSyncer
import org.slf4j.LoggerFactory

object HealthConnectWeightDeletion {
    private val LOG = LoggerFactory.getLogger(HealthConnectWeightDeletion::class.java)

    @JvmStatic
    @OptIn(DelicateCoroutinesApi::class)
    fun deleteManualSample(context: Context, sample: ManualWeightSample) {
        val appContext = context.applicationContext
        val clientRecordId = WeightSyncer.manualClientRecordId(sample)
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
                healthConnectClient.deleteRecords(
                    WeightRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = listOf(clientRecordId)
                )
                LOG.debug("Deleted Health Connect WeightRecord with clientRecordId={}", clientRecordId)
            } catch (e: Exception) {
                LOG.warn("Failed to delete Health Connect WeightRecord with clientRecordId={}", clientRecordId, e)
            }
        }
    }
}
