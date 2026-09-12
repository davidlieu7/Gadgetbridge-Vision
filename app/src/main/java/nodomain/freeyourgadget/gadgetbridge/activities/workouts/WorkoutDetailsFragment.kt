/*  Copyright (C) 2020-2025 José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.EndurainApiClient
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.EndurainSetupViewModel
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.WandererApiClient
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.WandererTokenManager
import nodomain.freeyourgadget.gadgetbridge.activities.fit.FitViewerActivity
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.charts.DefaultWorkoutCharts
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryGroup
import nodomain.freeyourgadget.gadgetbridge.databinding.FragmentWorkoutDetailsBinding
import nodomain.freeyourgadget.gadgetbridge.entities.Device
import nodomain.freeyourgadget.gadgetbridge.export.FitExporter
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.model.workout.Workout
import nodomain.freeyourgadget.gadgetbridge.model.workout.WorkoutViewModel
import nodomain.freeyourgadget.gadgetbridge.util.ActivitySummaryUtils
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

class WorkoutDetailsFragment : Fragment(), MenuProvider {
    private var workoutId: Long = -1
    private var currentWorkout: Workout? = null
    private lateinit var gbDevice: GBDevice

    private lateinit var binding: FragmentWorkoutDetailsBinding

    private lateinit var tabsPagerAdapter: WorkoutTabsPagerAdapter

    private lateinit var workoutEditor: WorkoutEditor

    private val workoutValueFormatter = WorkoutValueFormatter()

    private var menu: Menu? = null

    private lateinit var workoutViewModel: WorkoutViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workoutEditor = WorkoutEditor(requireContext(), this)
        workoutViewModel = ViewModelProvider(requireActivity()).get(WorkoutViewModel::class.java)
        arguments?.let {
            workoutId = it.getLong(ARG_WORKOUT_ID, -1)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentWorkoutDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Attach workout tabs.
        val viewPager = binding.tabsViewPager
        val tabLayout = binding.tabLayout
        tabsPagerAdapter = WorkoutTabsPagerAdapter(childFragmentManager, lifecycle, workoutId)
        viewPager.adapter = tabsPagerAdapter
        // Keep every tab's fragment (and its view) alive once created instead of the ViewPager2
        // default, which tears a tab's view down as soon as it's swiped away and rebuilds it
        // from scratch next time — expensive for Charts, which builds a fresh chart view per
        // metric. There are only a handful of tabs, so keeping them all resident is cheap.
        viewPager.offscreenPageLimit = WorkoutTab.entries.size
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabsPagerAdapter.titleAt(position)
        }.attach()

        loadWorkoutData()
    }

    override fun onResume() {
        super.onResume()

        updateActionBarTitle()
    }

    private fun updateActionBarTitle() {
        workoutLabel()?.let {
            (activity as? AppCompatActivity)?.supportActionBar?.title = it
        }
    }

    /** The workout's custom label, falling back to its sport/activity kind name. */
    private fun workoutLabel(): String? {
        val summary = currentWorkout?.summary ?: return null
        return summary.name?.takeIf { it.isNotBlank() }
            ?: summary.activityKind?.let { ActivityKind.fromCode(it).getLabel(requireContext()) }
    }

    private fun loadWorkoutData() {
        if (workoutId == -1L) return

        showLoading(true)

        lifecycleScope.launch {
            try {
                currentWorkout = withContext(Dispatchers.IO) {
                    val summary = GBApplication.acquireDbReadOnly().use { dbHandler ->
                        dbHandler.daoSession.baseActivitySummaryDao.load(workoutId)
                    }
                    gbDevice = getGBDevice(summary.device)
                    workoutEditor.gbDevice = gbDevice
                    val parsedWorkout = try {
                        gbDevice.deviceCoordinator.getActivitySummaryParser(gbDevice, requireContext())
                            .parseWorkout(summary, true)
                    } catch (e: Exception) {
                        // Do not break completely - use any previously processed data
                        GB.toast(requireContext(), "Error while loading workout", Toast.LENGTH_SHORT, GB.ERROR, e)
                        Workout(
                            summary,
                            ActivitySummaryData.fromJson(summary.summaryData),
                            mutableListOf()
                        )
                    }
                    if (parsedWorkout.charts.isEmpty()) {
                        try {
                            val activityTrackProvider =
                                gbDevice.deviceCoordinator.getActivityTrackProvider(gbDevice, requireContext())
                            if (activityTrackProvider != null) {
                                val activityPoints = activityTrackProvider.getActivityTrack(parsedWorkout.summary)?.allPoints
                                if (!activityPoints.isNullOrEmpty()) {
                                    val defaultCharts = DefaultWorkoutCharts.buildDefaultCharts(
                                        requireContext(),
                                        activityPoints,
                                        ActivityKind.fromCode(parsedWorkout.summary.activityKind)
                                    )
                                    return@withContext Workout(parsedWorkout.summary, parsedWorkout.data, defaultCharts)
                                }
                            }
                        } catch (e: Exception) {
                            LOG.error("Failed to build default charts", e)
                        }
                    }
                    return@withContext parsedWorkout
                }

                requireActivity().addMenuProvider(
                    this@WorkoutDetailsFragment,
                    viewLifecycleOwner,
                    Lifecycle.State.RESUMED
                )

                currentWorkout?.let { workout ->
                    workoutValueFormatter.setActivityKind(ActivityKind.fromCode(workout.summary.activityKind))
                    workoutViewModel.setWorkout(workout, workoutId)
                    tabsPagerAdapter.setLapsTab(hasLaps(workout))

                    showLoading(false)
                } ?: run {
                    showError("Workout not found")
                }
            } catch (e: Exception) {
                LOG.error("Error loading workout data", e)
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                showError("Failed to load workout: $sw")
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingSpinner.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.tabLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        binding.tabsViewPager.visibility = if (isLoading) View.GONE else View.VISIBLE
        binding.errorMessage.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.loadingSpinner.visibility = View.GONE
        binding.tabLayout.visibility = View.GONE
        binding.tabsViewPager.visibility = View.GONE
        binding.errorMessage.visibility = View.VISIBLE
        binding.errorMessage.text = message
    }

    /** Whether the workout has laps/intervals data (cardio-type workouts), and so gets a Laps tab. */
    private fun hasLaps(workout: Workout): Boolean {
        val groups = ActivitySummaryGroup.buildGroupedList(workout.data)
        return !groups[ActivitySummaryEntries.GROUP_LAPS].isNullOrEmpty() ||
            !groups[ActivitySummaryEntries.GROUP_INTERVALS].isNullOrEmpty()
    }

    private fun workoutHasGps(workout: Workout): Boolean {
        if (workout.data.hasGps()) {
            return true
        }

        workout.summary.gpxTrack?.let { gpxTrack ->
            val existing = FileUtils.tryFixPath(File(gpxTrack))
            if (existing != null && existing.canRead()) {
                return true
            }
        }

        return false
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menu.clear()
        menuInflater.inflate(R.menu.activity_take_screenshot_menu, menu)
        this.menu = menu
        updateMenuItems(menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        val workout = currentWorkout ?: return false

        return when (menuItem.itemId) {
            R.id.activity_action_take_screenshot -> {
                takeSharedScreenshot()
                true
            }

            R.id.activity_action_show_gpx -> {
                viewGpxTrack()
                true
            }

            R.id.activity_action_share_gpx -> {
                shareGpxTrack()
                true
            }

            R.id.activity_action_upload_to_endurain -> {
                uploadToEndurain()
                true
            }

            R.id.activity_action_upload_to_wanderer -> {
                uploadToWanderer()
                true
            }

            R.id.activity_action_dev_inspect_file -> {
                val rawDetailsPath = workout.summary.rawDetailsPath ?: return true
                val intent = Intent(requireContext(), FitViewerActivity::class.java).apply {
                    putExtra(FitViewerActivity.EXTRA_PATH, File(rawDetailsPath).absolutePath)
                }
                startActivity(intent)
                true
            }

            R.id.activity_action_dev_share_raw_summary -> {
                shareRawSummary(workout)
                true
            }

            R.id.activity_action_dev_share_raw_details -> {
                shareRawDetails(workout)
                true
            }

            R.id.activity_action_dev_share_json_details -> {
                shareJsonDetails(workout)
                true
            }

            R.id.activity_action_share_fit -> {
                exportFit(workout)
                true
            }

            R.id.activity_summary_detail_action_edit_name -> {
                currentWorkout?.let {
                    workoutEditor.editWorkoutName(it, object : WorkoutEditor.Callback {
                        override fun onWorkoutUpdated() {
                            notifyWorkoutChanged()
                            updateActionBarTitle()
                            workoutViewModel.refreshWorkout(workoutId)
                        }
                    })
                }
                true
            }

            R.id.activity_summary_detail_action_add_photo -> {
                currentWorkout?.let {
                    workoutEditor.setHeaderPhoto(it, object : WorkoutEditor.Callback {
                        override fun onWorkoutUpdated() {
                            notifyWorkoutChanged()
                            workoutViewModel.refreshWorkout(workoutId)
                            // Swap which of add/remove-photo is visible in the overflow menu.
                            requireActivity().invalidateMenu()
                        }
                    })
                }
                true
            }

            R.id.activity_summary_detail_action_remove_photo -> {
                currentWorkout?.let {
                    workoutEditor.removeHeaderPhoto(it, object : WorkoutEditor.Callback {
                        override fun onWorkoutUpdated() {
                            notifyWorkoutChanged()
                            workoutViewModel.refreshWorkout(workoutId)
                            // Swap which of add/remove-photo is visible in the overflow menu.
                            requireActivity().invalidateMenu()
                        }
                    })
                }
                true
            }

            R.id.activity_summary_detail_action_edit_gps -> {
                currentWorkout?.let {
                    workoutEditor.editGpsTrack(it, object : WorkoutEditor.Callback {
                        override fun onWorkoutUpdated() {
                            notifyWorkoutChanged()
                            // Reload the entire workout data so that we can refresh the charts
                            loadWorkoutData()
                        }
                    })
                }
                true
            }

            android.R.id.home -> {
                requireActivity().finish()
                true
            }

            else -> false
        }
    }

    private fun notifyWorkoutChanged() {
        val resultIntent = Intent().apply {
            putExtra(ARG_WORKOUT_ID, workoutId)
        }
        requireActivity().setResult(WorkoutDetailsActivity.RESULT_WORKOUT_CHANGED, resultIntent)
    }

    private fun updateMenuItems(menu: Menu) {
        val workout = currentWorkout ?: return

        val hasGpx = workoutHasGps(workout)
        val hasRawSummary = workout.summary.rawSummaryData != null
        val hasRawDetails = workout.summary.rawDetailsPath?.let { FileUtils.tryFixPath(File(it)) != null } ?: false

        val overflowMenu = menu.findItem(R.id.activity_detail_overflowMenu)?.subMenu
        if (overflowMenu != null) {
            overflowMenu.findItem(R.id.activity_action_show_gpx)?.isVisible = hasGpx
            overflowMenu.findItem(R.id.activity_action_share_gpx)?.isVisible = hasGpx
            overflowMenu.findItem(R.id.activity_action_dev_inspect_file)?.isVisible =
                hasRawDetails && workout.summary.rawDetailsPath?.lowercase(Locale.ROOT)?.endsWith(".fit") == true
            overflowMenu.findItem(R.id.activity_action_dev_share_raw_summary)?.isVisible = hasRawSummary
            overflowMenu.findItem(R.id.activity_action_dev_share_raw_details)?.isVisible = hasRawDetails

            val devToolsMenu = overflowMenu.findItem(R.id.activity_action_dev_tools)
            val devToolsSubMenu = devToolsMenu?.subMenu
            devToolsMenu?.isVisible = devToolsSubMenu != null && devToolsSubMenu.hasVisibleItems()
        }

        val overflowMenu2 = menu.findItem(R.id.activity_detail_overflowMenu2)?.subMenu
        overflowMenu2?.findItem(R.id.activity_summary_detail_action_add_photo)?.isVisible = workout.summary.headerPhoto == null
        overflowMenu2?.findItem(R.id.activity_summary_detail_action_remove_photo)?.isVisible = workout.summary.headerPhoto != null

        // Endurain accepts FIT (built from the summary alone if needed), so it is offered
        // for any workout. Wanderer only supports GPX uploads, so it requires a GPS track.
        val endurainVm: EndurainSetupViewModel by viewModels()
        val endurainServer = GBApplication.getPrefs().preferences.getString("endurain_server", null)
        val wandererServer = GBApplication.getPrefs().preferences.getString("wanderer_server", null)
        overflowMenu?.findItem(R.id.activity_action_upload_to_endurain)?.isVisible = endurainServer != null && endurainVm.endurainTokenManager.isLoggedIn()
        overflowMenu?.findItem(R.id.activity_action_upload_to_wanderer)?.isVisible = hasGpx && wandererServer != null && WandererTokenManager(requireContext()).isLoggedIn()
    }

    /**
     * Screenshots whichever tab is currently visible (each tab fragment exposes its own root via
     * [WorkoutTabScreenshotProvider], since the host no longer owns a single scrollable view now
     * that Overview/Charts/Laps/Details are split into tabs).
     */
    private fun takeSharedScreenshot() {
        lifecycleScope.launch {
            try {
                val workout = currentWorkout ?: return@launch
                val screenshotView = (tabsPagerAdapter.fragmentAt(binding.tabsViewPager.currentItem)
                        as? WorkoutTabScreenshotProvider)?.screenshotView ?: return@launch

                // Capture the full scrollable content, not just what's currently visible on screen.
                val content = (screenshotView as? ViewGroup)?.getChildAt(0) ?: screenshotView
                val width = content.width
                val height = content.height
                if (width <= 0 || height <= 0) return@launch

                val bitmap = createBitmap(width, height)
                val canvas = Canvas(bitmap)
                canvas.drawColor(GBApplication.getWindowBackgroundColor(requireContext()))
                screenshotView.draw(canvas)

                val fileName = FileUtils.makeValidFileName(
                    "Screenshot-${
                        ActivityKind.fromCode(workout.summary.activityKind).getLabel(requireContext()).lowercase()
                    }-${DateTimeUtils.formatIso8601(workout.summary.startTime)}.png"
                )
                val targetFile = File(FileUtils.getExternalFilesDir(), fileName)
                withContext(Dispatchers.IO) {
                    FileOutputStream(targetFile).use { fOut ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 85, fOut)
                    }
                }

                shareScreenshot(targetFile)
                GB.toast(requireContext(), "Screenshot saved", Toast.LENGTH_LONG, GB.INFO)
            } catch (e: IOException) {
                LOG.error("Error taking screenshot", e)
            }
        }
    }

    private fun shareScreenshot(targetFile: File) {
        val subject = currentWorkout?.summary?.name?.let { "Sports Activity" }
        val contentUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.screenshot_provider",
            targetFile
        )
        val sharingIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, subject)
            putExtra(Intent.EXTRA_STREAM, contentUri)
        }

        try {
            startActivity(Intent.createChooser(sharingIntent, "Share via"))
        } catch (e: Exception) {
            LOG.error("Failed to share screenshot", e)
            Toast.makeText(requireContext(), R.string.activity_error_no_app_for_png, Toast.LENGTH_LONG).show()
        }
    }

    private fun viewGpxTrack() {
        val workout = currentWorkout ?: return
        val activityTrackProvider = gbDevice.deviceCoordinator.getActivityTrackProvider(gbDevice, requireContext())
        val gpxFile = ActivitySummaryUtils.getShareableGpxFile(activityTrackProvider, workout.summary)

        if (gpxFile == null) {
            GB.toast(requireContext(),
                getString(R.string.no_gpx_track_in_activity_toast), Toast.LENGTH_LONG, GB.INFO)
            return
        }

        try {
            AndroidUtils.viewFile(gpxFile.path, "application/gpx+xml", requireContext())
        } catch (e: Exception) {
            GB.toast(
                requireContext(),
                getString(R.string.unable_to_display_gpx_track_toast, e.localizedMessage),
                Toast.LENGTH_LONG,
                GB.ERROR,
                e
            )
        }
    }

    private fun shareGpxTrack() {
        val workout = currentWorkout ?: return
        val activityTrackProvider = gbDevice.deviceCoordinator.getActivityTrackProvider(gbDevice, requireContext())
        val gpxFile = ActivitySummaryUtils.getShareableGpxFile(activityTrackProvider, workout.summary)

        if (gpxFile == null) {
            GB.toast(requireContext(), getString(R.string.no_gpx_track_in_activity_toast), Toast.LENGTH_LONG, GB.INFO)
            return
        }

        try {
            AndroidUtils.shareFile(requireContext(), gpxFile, "application/gpx+xml")
        } catch (e: Exception) {
            GB.toast(
                requireContext(),
                getString(R.string.unable_to_share_gpx_track_toast, e.localizedMessage),
                Toast.LENGTH_LONG,
                GB.ERROR,
                e
            )
        }
    }

    private fun uploadToEndurain() {
        val workout = currentWorkout ?: return
        val activityKind = ActivityKind.fromCode(workout.summary.activityKind)
        val workoutName = workout.summary.name ?: activityKind.getLabel(requireContext())

        lifecycleScope.launch {
            val activityFile = try {
                buildFitFile(workout)
            } catch (e: Exception) {
                LOG.error("Failed to build FIT for Endurain upload", e)
                GB.toast(
                    getString(R.string.endurain_unable_to_upload_gpx_file_toast, e.localizedMessage),
                    Toast.LENGTH_LONG,
                    GB.ERROR,
                    e
                )
                return@launch
            }

            try {
                val endurainVm: EndurainSetupViewModel by viewModels()
                val serverUrl = GBApplication.getPrefs().preferences.getString("endurain_server", null)
                val apiClient = EndurainApiClient(serverUrl!!, endurainVm.endurainTokenManager)
                endurainVm.endurainTokenManager.performTokenRefresh(serverUrl) {
                    LOG.info("Uploading workout '{}' (type {}) to Endurain", workoutName, activityKind)
                    GB.toast(
                        getString(R.string.endurain_uploading_started),
                        Toast.LENGTH_SHORT,
                        GB.INFO
                    )
                    apiClient.uploadActivity(activityFile) { newId ->
                        if (newId != null) {
                            // Update activity type on the server
                            apiClient.editActivity(newId, activityKind, workoutName)
                            // Upload workout photo to the new activity
                            val headerPhoto = workout.summary.headerPhoto
                            if (headerPhoto != null) {
                                apiClient.uploadActivityPhoto(newId, File(headerPhoto))
                            }
                        }
                        activity?.runOnUiThread {
                            if (newId != null)
                                GB.toast(
                                    getString(R.string.endurain_successfully_uploaded_toast),
                                    Toast.LENGTH_SHORT,
                                    GB.INFO
                                )
                            else
                                GB.toast(
                                    getString(R.string.endurain_error_while_uploading_toast),
                                    Toast.LENGTH_SHORT,
                                    GB.INFO
                                )
                        }
                    }
                }
            } catch (e: Exception) {
                GB.toast(
                    getString(R.string.endurain_unable_to_upload_gpx_file_toast, e.localizedMessage),
                    Toast.LENGTH_LONG,
                    GB.ERROR,
                    e
                )
            }
        }
    }

    private fun uploadToWanderer() {
        val workout = currentWorkout ?: return
        val activityTrackProvider = gbDevice.deviceCoordinator.getActivityTrackProvider(gbDevice, requireContext())

        // Wanderer only supports GPX uploads, so always send GPX (never a FIT file).
        val activityFile = ActivitySummaryUtils.getShareableGpxFile(activityTrackProvider, workout.summary)
        if (activityFile == null) {
            GB.toast(getString(R.string.no_activity_track_in_activity_toast), Toast.LENGTH_LONG, GB.INFO)
            return
        }

        try {
            val serverUrl = GBApplication.getPrefs().preferences.getString("wanderer_server", null)
            val apiClient = WandererApiClient(serverUrl!!, WandererTokenManager(requireContext()))
            apiClient.uploadActivity(activityFile) { newId, message ->
                if (newId != null && message == null) {
                    LOG.info("Uploaded GPX to Wanderer, ID $newId")
                    // TODO: Update activity type on the server
                    //apiClient.editActivity(newId, activityKind, workoutName)
                }
                activity?.runOnUiThread {
                    if (newId != null && message == null)
                        GB.toast(
                            getString(R.string.wanderer_toast_successfully_uploaded),
                            Toast.LENGTH_LONG,
                            GB.INFO
                        )
                    else
                        GB.toast(
                            getString(R.string.wanderer_toast_upload_error, message),
                            Toast.LENGTH_LONG,
                            GB.INFO
                        )
                }
            }
        } catch (e: Exception) {
            GB.toast(
                getString(R.string.wanderer_unable_to_upload_gpx_file_toast, e.localizedMessage),
                Toast.LENGTH_LONG,
                GB.ERROR,
                e
            )
        }
    }

    private fun shareRawSummary(workout: Workout) {
        val rawSummaryData = workout.summary.rawSummaryData
        if (rawSummaryData == null) {
            GB.toast(requireContext(), "No raw summary in this activity", Toast.LENGTH_LONG, GB.WARN)
            return
        }

        val filename =
            FileUtils.makeValidFileName("${DateTimeUtils.formatIso8601(workout.summary.startTime)}_summary.bin")

        try {
            AndroidUtils.shareBytesAsFile(
                requireContext(),
                filename,
                rawSummaryData,
                "application/octet-stream"
            )
        } catch (e: Exception) {
            GB.toast(
                requireContext(),
                "Unable to share raw summary: ${e.localizedMessage}",
                Toast.LENGTH_LONG,
                GB.ERROR,
                e
            )
        }
    }

    private fun shareRawDetails(workout: Workout) {
        val rawDetailsPath = workout.summary.rawDetailsPath
        if (rawDetailsPath == null) {
            GB.toast(requireContext(), "No raw details in this activity", Toast.LENGTH_LONG, GB.WARN)
            return
        }
        val file = FileUtils.tryFixPath(File(rawDetailsPath))
        if (file == null) {
            GB.toast(requireContext(), "No raw details in this activity", Toast.LENGTH_LONG, GB.WARN)
            return
        }

        try {
            AndroidUtils.shareFile(requireContext(), file, "application/octet-stream")
        } catch (e: Exception) {
            GB.toast(
                requireContext(),
                "Unable to share raw details: ${e.localizedMessage}",
                Toast.LENGTH_LONG,
                GB.ERROR,
                e
            )
        }
    }

    private fun shareJsonDetails(workout: Workout) {
        val filename = FileUtils.makeValidFileName("${DateTimeUtils.formatIso8601(workout.summary.startTime)}.json")

        try {
            AndroidUtils.shareBytesAsFile(
                requireContext(),
                filename,
                workout.data.toString().toByteArray(StandardCharsets.UTF_8),
                "application/json"
            )
        } catch (e: Exception) {
            GB.toast(
                requireContext(),
                "Unable to share json details: ${e.localizedMessage}",
                Toast.LENGTH_LONG,
                GB.ERROR,
                e
            )
        }
    }

    /**
     * Builds a FIT file for the given workout in the cache directory.
     *
     * FIT-native devices (Garmin, iGPSPORT) keep the original .fit at rawDetailsPath —
     * it is copied verbatim. For any other device the FIT is synthesized from the
     * summary (and the activity track, if one is available).
     */
    private suspend fun buildFitFile(workout: Workout): File = withContext(Dispatchers.IO) {
        val kindLabel = ActivityKind.fromCode(workout.summary.activityKind)
            .getLabel(requireContext()).lowercase()
        val fileName = FileUtils.makeValidFileName(
            "Workout-${kindLabel}-${DateTimeUtils.formatIso8601(workout.summary.startTime)}.fit"
        )
        val cacheSubDir = File(requireContext().cacheDir, "raw")
        cacheSubDir.mkdirs()
        val outFile = File(cacheSubDir, fileName)

        val rawFit = FitExporter.resolveRawFitFile(workout.summary)
        if (rawFit != null) {
            rawFit.copyTo(outFile, overwrite = true)
        } else {
            val activityTrackProvider = gbDevice.deviceCoordinator
                .getActivityTrackProvider(gbDevice, requireContext())
            val track = try {
                activityTrackProvider?.getActivityTrack(workout.summary)
            } catch (e: Exception) {
                LOG.warn("Failed to load activity track for FIT export", e)
                null
            }
            FitExporter().performExport(track, workout.summary, workout.data, outFile)
        }
        outFile
    }

    private fun exportFit(workout: Workout) {
        lifecycleScope.launch {
            try {
                val targetFile = buildFitFile(workout)
                AndroidUtils.shareFile(requireContext(), targetFile, "application/octet-stream")
            } catch (e: Exception) {
                LOG.error("Failed to export FIT file", e)
                GB.toast(
                    requireContext(),
                    getString(R.string.activity_detail_export_fit_failed),
                    Toast.LENGTH_LONG,
                    GB.ERROR,
                    e
                )
            }
        }
    }

    private fun getGBDevice(device: Device): GBDevice {
        return device.let { findDevice ->
            GBApplication.app().deviceManager.devices
                .first { it.address.equals(findDevice.identifier, ignoreCase = true) }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(WorkoutDetailsFragment::class.java)

        private const val ARG_WORKOUT_ID = "workout_id"

        fun newInstance(workoutId: Long): WorkoutDetailsFragment {
            return WorkoutDetailsFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_WORKOUT_ID, workoutId)
                }
            }
        }
    }
}
