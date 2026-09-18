/*  Copyright (C) 2026 Sam Jeffery, José Rebelo, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.dreeve

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.externalservices.AbstractApiTokenSetupBottomSheet

class DreeveSetupBottomSheet : AbstractApiTokenSetupBottomSheet(
    R.layout.dreeve_bottomsheet_setup_wizard,
    R.id.dreeve_api_token,
    R.id.dreeve_server_name
) {
    override val service = DreeveService

    override fun validate(apiToken: String): String? = when {
        serverName.isEmpty() || apiToken.isEmpty() ->
            getString(R.string.wanderer_setup_missing_information)

        !apiToken.startsWith(DreeveTokenManager.TOKEN_PREFIX) ->
            getString(R.string.dreeve_setup_api_token_error)

        else -> null
    }

    override fun checkToken(apiToken: String): Pair<Boolean, String?> =
        DreeveApiClient(serverName, DreeveTokenManager(requireContext())).checkToken(apiToken)

    override fun onTokenAccepted(apiToken: String) {
        DreeveTokenManager(requireContext()).saveToken(apiToken)
    }
}
