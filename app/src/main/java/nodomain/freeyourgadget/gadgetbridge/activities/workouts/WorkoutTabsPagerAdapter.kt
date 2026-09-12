package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter

enum class WorkoutTab(val label: String) {
    OVERVIEW("Overview"),
    CHARTS("Charts"),
    LAPS("Laps"),
    DETAILS("Details"),
}

private val BASE_TABS = listOf(WorkoutTab.OVERVIEW, WorkoutTab.CHARTS, WorkoutTab.DETAILS)
private val TABS_WITH_LAPS = listOf(WorkoutTab.OVERVIEW, WorkoutTab.LAPS, WorkoutTab.CHARTS, WorkoutTab.DETAILS)

class WorkoutTabsPagerAdapter(
    fm: FragmentManager,
    lifecycle: Lifecycle,
    private val workoutId: Long,
) : FragmentStateAdapter(fm, lifecycle) {

    // Laps tab starts hidden; it's only added once the loaded workout is confirmed to have
    // laps/intervals (cardio-type workouts) data. Strength-training sets are shown inline on
    // Overview instead of getting their own tab.
    private var tabs: List<WorkoutTab> = BASE_TABS

    // Tracked so the host can look up whichever tab fragment is currently visible (e.g. to
    // screenshot it), without relying on FragmentStateAdapter's internal fragment tag naming.
    private val fragmentsByItemId = mutableMapOf<Long, Fragment>()

    /** Shows or hides the Laps tab. Safe to call repeatedly; a no-op if unchanged. */
    fun setLapsTab(show: Boolean) {
        val newTabs = if (show) TABS_WITH_LAPS else BASE_TABS
        if (newTabs != tabs) {
            tabs = newTabs
            notifyDataSetChanged()
        }
    }

    fun titleAt(position: Int): String = tabs[position].label

    override fun getItemCount(): Int = tabs.size

    // Stable per-tab-type IDs so FragmentStateAdapter can tell which fragments survived
    // a tab being added/removed, instead of misattributing fragments by position.
    override fun getItemId(position: Int): Long = tabs[position].ordinal.toLong()

    override fun containsItem(itemId: Long): Boolean = tabs.any { it.ordinal.toLong() == itemId }

    override fun createFragment(position: Int): Fragment {
        val bundle = Bundle().apply { putLong("workoutId", workoutId) }
        val fragment = when (tabs[position]) {
            WorkoutTab.OVERVIEW -> WorkoutTabOverviewFragment()
            WorkoutTab.CHARTS -> WorkoutTabChartsFragment()
            WorkoutTab.LAPS -> WorkoutTabLapsFragment()
            WorkoutTab.DETAILS -> WorkoutTabDetailsFragment()
        }
        fragment.arguments = bundle
        fragmentsByItemId[getItemId(position)] = fragment
        return fragment
    }

    /** The fragment currently shown at [position], if it has been created yet. */
    fun fragmentAt(position: Int): Fragment? = fragmentsByItemId[getItemId(position)]
}
