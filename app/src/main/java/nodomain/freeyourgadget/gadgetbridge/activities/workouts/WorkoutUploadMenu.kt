/*  Copyright (C) 2026 Dany Mestas

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

import android.content.Context
import android.view.Menu
import nodomain.freeyourgadget.gadgetbridge.R

/**
 * The "Upload to…" entries of the workout detail screen and of the workout list's selection mode.
 *
 * The items are built at runtime rather than declared in a menu resource, so a service is offered
 * because it is set up and can express the workout, not because someone remembered to add an id
 * for it.
 */
object WorkoutUploadMenu {
    /** Group the upload entries belong to, so they can be cleared together. */
    const val UPLOAD_GROUP_ID = 0x0f000000

    /**
     * Id of the first upload entry, the rest following it. Outside the range aapt gives the ids in
     * a menu resource, and not zero, which a menu reads as "no id at all".
     */
    private const val UPLOAD_ITEM_ID_BASE = 0x0f000010

    const val UPLOAD_ALL_ITEM_ID = 0x0f00000f

    /**
     * Adds an entry per service in [targets] to [menu]: one item when there is a single service,
     * and a submenu led by "all services" when there are several, since a row per service would
     * crowd out everything else in the menu.
     *
     * Answers the service behind each item id, [UPLOAD_ALL_ITEM_ID] aside.
     */
    fun build(
        menu: Menu,
        context: Context,
        targets: List<WorkoutUploadTarget>,
        order: Int
    ): Map<Int, WorkoutUploadTarget> {
        if (targets.isEmpty()) {
            return emptyMap()
        }
        if (targets.size == 1) {
            val target = targets.first()
            menu.add(
                UPLOAD_GROUP_ID,
                UPLOAD_ITEM_ID_BASE,
                order,
                context.getString(
                    R.string.activity_detail_upload_to_service,
                    context.getString(target.nameRes)
                )
            ).setIcon(R.drawable.ic_file_upload)
            return mapOf(UPLOAD_ITEM_ID_BASE to target)
        }

        val submenu = menu.addSubMenu(UPLOAD_GROUP_ID, Menu.NONE, order, R.string.activity_detail_upload_to)
        submenu.setIcon(R.drawable.ic_file_upload)
        submenu.add(UPLOAD_GROUP_ID, UPLOAD_ALL_ITEM_ID, 0, R.string.activity_detail_upload_to_all)
        val items = mutableMapOf<Int, WorkoutUploadTarget>()
        targets.forEachIndexed { index, target ->
            submenu.add(UPLOAD_GROUP_ID, UPLOAD_ITEM_ID_BASE + index, index + 1, target.nameRes)
            items[UPLOAD_ITEM_ID_BASE + index] = target
        }
        return items
    }
}
