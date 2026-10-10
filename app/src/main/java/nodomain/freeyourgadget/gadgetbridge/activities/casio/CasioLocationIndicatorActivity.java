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
package nodomain.freeyourgadget.gadgetbridge.activities.casio;

import android.Manifest;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import androidx.core.app.ActivityCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.Locale;
import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.CasioLocationPoint;
import nodomain.freeyourgadget.gadgetbridge.service.devices.casio.CasioLocationIndicatorLibrary;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;

public class CasioLocationIndicatorActivity extends AbstractGBActivity {
    private GBDevice gbDevice;
    protected CasioLocationIndicatorLibrary library;
    protected LocationAdapter adapter;
    private TextView emptyView;
    private FloatingActionButton addButton;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_casio_location_indicator);

        gbDevice = getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
        if (gbDevice == null) {
            finish();
            return;
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        library = loadLibrary();

        emptyView = findViewById(R.id.casio_location_empty);
        addButton = findViewById(R.id.casio_location_add);
        final RecyclerView list = findViewById(R.id.casio_location_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LocationAdapter();
        list.setAdapter(adapter);

        addButton.setOnClickListener(v -> onAddClicked());
        refreshEmpty();
    }

    private CasioLocationIndicatorLibrary loadLibrary() {
        final String json = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress())
                .getString(CasioLocationIndicatorLibrary.PREF_LOCATION_INDICATOR_LIBRARY, null);
        return CasioLocationIndicatorLibrary.fromJson(json);
    }

    protected void persistAndRefresh() {
        GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress()).edit()
                .putString(CasioLocationIndicatorLibrary.PREF_LOCATION_INDICATOR_LIBRARY, library.toJson())
                .apply();
        adapter.notifyDataSetChanged();
        refreshEmpty();
    }

    private void refreshEmpty() {
        final boolean empty = library.locations.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }


    protected void openEditor(final int index) {
        final boolean isNew = index < 0;
        final CasioLocationPoint working = isNew ? defaultPoint() : clonePoint(library.locations.get(index));

        final View root = LayoutInflater.from(this)
                .inflate(R.layout.dialog_casio_location_indicator_edit, null);
        final EditText labelView = root.findViewById(R.id.location_label);
        final EditText latView = root.findViewById(R.id.location_lat);
        final EditText lonView = root.findViewById(R.id.location_lon);
        final EditText elevView = root.findViewById(R.id.location_elevation);
        final View useCurrentButton = root.findViewById(R.id.location_use_current);

        labelView.setText(working.label);
        if (!isNew) {
            latView.setText(String.format(Locale.ROOT, "%.6f", working.waypoint.getLatitude()));
            lonView.setText(String.format(Locale.ROOT, "%.6f", working.waypoint.getLongitude()));
            if (working.waypoint.hasAltitude()) {
                elevView.setText(String.format(Locale.ROOT, "%.0f", working.waypoint.getAltitude()));
            }
        }

        useCurrentButton.setOnClickListener(v -> {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_SHORT).show();
                return;
            }
            final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location fix = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (fix == null) fix = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (fix == null) {
                Toast.makeText(this, R.string.casio_location_indicator_no_fix, Toast.LENGTH_SHORT).show();
                return;
            }
            latView.setText(String.format(Locale.ROOT, "%.6f", fix.getLatitude()));
            lonView.setText(String.format(Locale.ROOT, "%.6f", fix.getLongitude()));
            if (fix.hasAltitude()) {
                elevView.setText(String.format(Locale.ROOT, "%.0f", fix.getAltitude()));
            }
        });

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.pref_casio_location_indicator_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    final Double lat = parseCoordinate(latView, -90.0, 90.0);
                    final Double lon = parseCoordinate(lonView, -180.0, 180.0);
                    if (lat == null || lon == null) {
                        Toast.makeText(this, R.string.casio_location_indicator_invalid_coordinates,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final Double elev = parseElevation(elevView);

                    working.label = labelView.getText().toString().trim();
                    if (working.label.isEmpty()) {
                        working.label = getString(R.string.casio_location_indicator_default_label,
                                (isNew ? library.locations.size() : index) + 1);
                    }
                    working.waypoint = new GPSCoordinate(lon, lat,
                            elev != null ? elev : GPSCoordinate.UNKNOWN_ALTITUDE);
                    if (isNew) {
                        working.timestamp = System.currentTimeMillis();
                    }

                    final boolean editingActive = !isNew && index == library.activeIndex;
                    if (isNew) {
                        library.add(working);
                    } else {
                        library.locations.set(index, working);
                    }
                    persistAndRefresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Nullable
    private static Double parseCoordinate(final EditText view, final double min, final double max) {
        try {
            final double v = Double.parseDouble(view.getText().toString().trim());
            return (v >= min && v <= max) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Double parseElevation(final EditText view) {
        final String s = view.getText().toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }


    private static CasioLocationPoint defaultPoint() {
        return new CasioLocationPoint();
    }

    private static CasioLocationPoint clonePoint(final CasioLocationPoint src) {
        final CasioLocationPoint copy = new CasioLocationPoint();
        copy.label = src.label;
        copy.timestamp = src.timestamp;
        copy.waypoint = src.waypoint;
        return copy;
    }

    private void onAddClicked() {
        openEditor(-1);
    }

    static String summarize(final Context context, final CasioLocationPoint p) {
        final String coords = String.format(Locale.ROOT, "%.5f, %.5f",
                p.waypoint.getLatitude(), p.waypoint.getLongitude());
        final String added = DateTimeUtils.formatDateTimeRelative(context, new Date(p.timestamp));
        return coords + " · " + added;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    class LocationAdapter extends RecyclerView.Adapter<LocationAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final android.widget.RadioButton active;
            final TextView label;
            final TextView summary;
            final android.widget.ImageButton delete;
            VH(final View v) {
                super(v);
                active = v.findViewById(R.id.casio_location_item_active);
                label = v.findViewById(R.id.casio_location_item_label);
                summary = v.findViewById(R.id.casio_location_item_summary);
                delete = v.findViewById(R.id.casio_location_item_delete);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            final View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_casio_location_indicator, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final VH h, final int position) {
            final CasioLocationPoint p = library.locations.get(position);
            h.label.setText(p.label);
            h.summary.setText(summarize(h.itemView.getContext(), p));
            h.active.setChecked(position == library.activeIndex);
            h.active.setOnClickListener(v -> {
                library.setActive(h.getBindingAdapterPosition());
                persistAndRefresh();
            });
            h.itemView.setOnClickListener(v -> openEditor(h.getBindingAdapterPosition()));
            h.delete.setOnClickListener(v -> {
                library.remove(h.getBindingAdapterPosition());
                persistAndRefresh();
            });
        }

        @Override
        public int getItemCount() {
            return library.locations.size();
        }
    }
}
