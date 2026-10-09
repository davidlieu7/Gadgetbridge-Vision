/*  Copyright (C) 2026 Davide Gessa

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
package nodomain.freeyourgadget.gadgetbridge.devices.casio.ggb100;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.casio.Casio2C2DDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.ggb100.CasioGGB100DeviceSupport;

public class CasioGGB100DeviceCoordinator extends Casio2C2DDeviceCoordinator {
    protected static final Logger LOG = LoggerFactory.getLogger(CasioGGB100DeviceCoordinator.class);

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^CASIO GG-B100.*");
    }

    @Override
    public int getBlePhyMask() {
        return BluetoothDevice.PHY_LE_1M_MASK;
    }

    @Override
    public int getBondingStyle(){
        return BONDING_STYLE_LAZY;
    }

    @Override
    public boolean supportsAlarmSnoozing(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public Class<? extends Activity> getPairingActivity() {
        return null;
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull GBDevice device) {
        return true;
    }

    // @Override
    // public SampleProvider<? extends ActivitySample> getSampleProvider(GBDevice device, DaoSession session) {
    //     return new CasioGGB100SampleProvider(device, session);
    // }

    @Override
    public int getAlarmSlotCount(GBDevice device) {
        return 4;
    }

    // @Override
    // public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
    //     Map<AbstractDao<?, ?>, Property> map = new HashMap<>(1);
    //     map.put(session.getCasioGGB100ActivitySampleDao(), CasioGGB100ActivitySampleDao.Properties.DeviceId);
    //     return map;
    // }

    @Override
    public int[] getSupportedDeviceSpecificSettings(GBDevice device) {
        return new int[]{
                R.xml.devicesettings_find_phone,
                R.xml.devicesettings_wearlocation,
                R.xml.devicesettings_timeformat,
                R.xml.devicesettings_autolight,
                R.xml.devicesettings_operating_sounds,
                R.xml.devicesettings_casio_alert
        };
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return CasioGGB100DeviceSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_casioggb100;
    }
}
