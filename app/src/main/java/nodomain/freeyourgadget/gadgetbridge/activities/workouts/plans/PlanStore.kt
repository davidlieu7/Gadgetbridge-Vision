/*  Copyright (C) 2026 theupsidedownabyss

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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts.plans

import android.content.SharedPreferences
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import java.time.LocalDate

/**
 * The active training plan, the weight-loss plan and the weight log, kept in the app preferences.
 */
object PlanStore {
    private const val TRAINING_PLAN = "plans_training_plan"
    private const val TRAINING_START = "plans_training_start"
    private const val WEIGHT_START_KG = "plans_weight_start_kg"
    private const val WEIGHT_TARGET_KG = "plans_weight_target_kg"
    private const val WEIGHT_PACE = "plans_weight_kg_per_week"
    private const val WEIGHT_START = "plans_weight_start"

    /**
     * "date=kg" entries separated by ";", in date order.
     */
    private const val WEIGHT_LOG = "plans_weight_log"

    private val prefs: SharedPreferences get() = GBApplication.getPrefs().preferences

    fun trainingPlan(): Pair<TrainingPlan, LocalDate>? {
        val plan = TrainingPlan.byKey(prefs.getString(TRAINING_PLAN, null)) ?: return null
        val start = prefs.getString(TRAINING_START, null)?.let { LocalDate.parse(it) } ?: return null
        return plan to start
    }

    fun startTrainingPlan(plan: TrainingPlan, start: LocalDate) {
        prefs.edit().putString(TRAINING_PLAN, plan.key).putString(TRAINING_START, start.toString()).apply()
    }

    fun stopTrainingPlan() {
        prefs.edit().remove(TRAINING_PLAN).remove(TRAINING_START).apply()
    }

    fun weightPlan(): WeightPlan? {
        val start = prefs.getString(WEIGHT_START, null)?.let { LocalDate.parse(it) } ?: return null
        return WeightPlan(
            prefs.getFloat(WEIGHT_START_KG, 0f).toDouble(),
            prefs.getFloat(WEIGHT_TARGET_KG, 0f).toDouble(),
            prefs.getFloat(WEIGHT_PACE, 0.5f).toDouble(),
            start,
        )
    }

    fun saveWeightPlan(plan: WeightPlan) {
        prefs.edit()
            .putFloat(WEIGHT_START_KG, plan.startKg.toFloat())
            .putFloat(WEIGHT_TARGET_KG, plan.targetKg.toFloat())
            .putFloat(WEIGHT_PACE, plan.kgPerWeek.toFloat())
            .putString(WEIGHT_START, plan.start.toString())
            .apply()
    }

    fun stopWeightPlan() {
        prefs.edit().remove(WEIGHT_START_KG).remove(WEIGHT_TARGET_KG).remove(WEIGHT_PACE).remove(WEIGHT_START).apply()
    }

    fun weightLog(): List<Pair<LocalDate, Double>> = parseLog(prefs.getString(WEIGHT_LOG, "").orEmpty())

    /**
     * Logs [kg] on [date], replacing an entry of the same day.
     */
    fun logWeight(date: LocalDate, kg: Double) {
        val log = (weightLog().filter { it.first != date } + (date to kg)).sortedBy { it.first }
        prefs.edit().putString(WEIGHT_LOG, formatLog(log)).apply()
    }

    fun parseLog(value: String): List<Pair<LocalDate, Double>> = value.split(';')
        .mapNotNull { entry ->
            val parts = entry.split('=')
            if (parts.size != 2) return@mapNotNull null
            val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val kg = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            date to kg
        }
        .sortedBy { it.first }

    fun formatLog(log: List<Pair<LocalDate, Double>>): String = log.joinToString(";") { "${it.first}=${it.second}" }
}
