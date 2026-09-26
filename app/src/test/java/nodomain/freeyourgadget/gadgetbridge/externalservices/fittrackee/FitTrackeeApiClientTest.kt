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
package nodomain.freeyourgadget.gadgetbridge.externalservices.fittrackee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers which requests a login is taken to allow, from the scopes its token was granted. */
class FitTrackeeApiClientTest {

    @Test
    fun aGrantedScopeIsAllowed() {
        assertTrue(FitTrackeeApiClient.scopeAllows("workouts:read workouts:write profile:read", "workouts:write"))
    }

    @Test
    fun aScopeNotGrantedIsRefused() {
        assertFalse(FitTrackeeApiClient.scopeAllows("workouts:read profile:read media:write", "workouts:write"))
        assertFalse(FitTrackeeApiClient.scopeAllows("workouts:read workouts:write profile:read", "media:write"))
    }

    @Test
    fun aScopeIsMatchedWholeNotAsAPrefix() {
        assertFalse(FitTrackeeApiClient.scopeAllows("workouts:write-extra", "workouts:write"))
    }

    @Test
    fun aTokenWithNoRecordedScopeKeepsWorking() {
        assertTrue(FitTrackeeApiClient.scopeAllows(null, "media:write"))
    }
}
