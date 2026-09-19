/*  Copyright (C) 2026 Struchkov Mark, José Rebelo, Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.ridehub

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.externalservices.AbstractApiTokenSetupBottomSheet

class RideHubSetupBottomSheet : AbstractApiTokenSetupBottomSheet(
    R.layout.ridehub_bottomsheet_setup_wizard,
    R.id.ridehub_api_token
) {
    override val service = RideHubService

    /**
     * The server is the judge of whether a token is valid. Tokens carry an "rh_" prefix, but
     * checking for it here would tie the client to one server version and reject a token issued
     * before the prefix existed.
     */
    override fun validate(apiToken: String): String? =
        if (apiToken.isEmpty()) getString(R.string.service_setup_missing_information) else null

    override fun checkToken(apiToken: String): Pair<Boolean, String?> =
        RideHubApiClient(RideHubTokenManager(requireContext())).checkToken(apiToken)

    override fun onTokenAccepted(apiToken: String) {
        RideHubTokenManager(requireContext()).saveToken(apiToken)
    }
}
