/*  Copyright (C) 2026 José Rebelo, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices

import nodomain.freeyourgadget.gadgetbridge.externalservices.endurain.EndurainService
import nodomain.freeyourgadget.gadgetbridge.externalservices.wanderer.WandererService

/** Every external service the user can set up, in the order the settings list them. */
object ExternalServiceRegistry {
    val services: List<ExternalService> = listOf(
        EndurainService,
        WandererService,
    )

    fun byId(id: String): ExternalService? = services.firstOrNull { it.id == id }
}
