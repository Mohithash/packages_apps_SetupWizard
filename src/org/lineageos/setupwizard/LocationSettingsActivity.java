/*
 * SPDX-FileCopyrightText: 2016 The CyanogenMod Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.setupwizard;

import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.ext.settings.GeocoderSettings;
import android.ext.settings.GnssSettings;
import android.ext.settings.NetworkLocationSettings;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

public class LocationSettingsActivity extends BaseSetupWizardActivity {

    private static final String KEY_SUPL = "supl";
    private static final String KEY_PSDS = "psds";
    private static final String KEY_NETWORK_LOCATION = "network_location";
    private static final String KEY_GEOCODER = "geocoder";

    private CheckBox mLocationAccess;
    private CheckBox mLocationAgpsAccess;

    private ChoicePicker mSuplPicker;
    private ChoicePicker mPsdsPicker;
    private ChoicePicker mNetworkLocationPicker;
    private ChoicePicker mGeocoderPicker;

    private LocationManager mLocationManager;

    private UserManager mUserManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setNextText(R.string.next);

        mLocationAccess = findViewById(R.id.location_checkbox);
        mLocationAgpsAccess = findViewById(R.id.location_agps_checkbox);
        mLocationManager = getSystemService(LocationManager.class);
        mUserManager = getSystemService(UserManager.class);
        View locationAccessView = findViewById(R.id.location);
        locationAccessView.setOnClickListener(
                v -> mLocationAccess.setChecked(!mLocationAccess.isChecked()));
        View locationAgpsAccessView = findViewById(R.id.location_agps);
        boolean isMainUser = mUserManager.isMainUser();
        if (isMainUser) {
            locationAgpsAccessView.setOnClickListener(
                    v -> mLocationAgpsAccess.setChecked(!mLocationAgpsAccess.isChecked()));
        } else {
            locationAgpsAccessView.setVisibility(View.GONE);
        }

        boolean hasGpsFeature = getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        boolean suplAvailable = isMainUser && hasGpsFeature;
        boolean psdsAvailable = suplAvailable
                && !getString(com.android.internal.R.string.config_gnssPsdsType).isEmpty();

        mSuplPicker = createPicker(suplAvailable, R.id.location_supl, R.id.location_supl_summary,
                R.string.location_supl_title,
                new int[] {
                        GnssSettings.SUPL_SERVER_GRAPHENEOS_PROXY,
                        GnssSettings.SUPL_SERVER_STANDARD,
                        GnssSettings.SUPL_DISABLED,
                },
                new int[] {
                        R.string.location_supl_grapheneos_proxy,
                        R.string.location_supl_standard_server,
                        R.string.location_supl_off,
                },
                GnssSettings.SUPL_SETTING.get(this), savedInstanceState, KEY_SUPL);

        mPsdsPicker = createPicker(psdsAvailable, R.id.location_psds, R.id.location_psds_summary,
                R.string.location_psds_title,
                new int[] {
                        GnssSettings.PSDS_SERVER_GRAPHENEOS,
                        GnssSettings.PSDS_SERVER_STANDARD,
                        GnssSettings.PSDS_DISABLED,
                },
                new int[] {
                        R.string.location_psds_grapheneos_server,
                        R.string.location_psds_standard_server,
                        R.string.location_psds_off,
                },
                GnssSettings.getPsdsSetting(this).get(this), savedInstanceState, KEY_PSDS);

        mNetworkLocationPicker = createPicker(isMainUser, R.id.location_network,
                R.id.location_network_summary, R.string.location_network_title,
                new int[] {
                        NetworkLocationSettings.NETWORK_LOCATION_GRAPHENEOS_APPLE_PROXY,
                        NetworkLocationSettings.NETWORK_LOCATION_APPLE,
                        NetworkLocationSettings.NETWORK_LOCATION_APPLE_CHINA,
                        NetworkLocationSettings.NETWORK_LOCATION_DISABLED,
                },
                new int[] {
                        R.string.location_network_grapheneos_apple_proxy,
                        R.string.location_network_apple,
                        R.string.location_network_apple_china,
                        R.string.location_network_off,
                },
                NetworkLocationSettings.NETWORK_LOCATION_SETTING.get(this), savedInstanceState,
                KEY_NETWORK_LOCATION);

        mGeocoderPicker = createPicker(isMainUser, R.id.location_geocoder,
                R.id.location_geocoder_summary, R.string.location_geocoder_title,
                new int[] {
                        GeocoderSettings.GEOCODER_SERVER_GRAPHENEOS,
                        GeocoderSettings.GEOCODER_SERVER_OPENSTREETMAP,
                        GeocoderSettings.GEOCODER_DISABLED,
                },
                new int[] {
                        R.string.location_geocoder_grapheneos_server,
                        R.string.location_geocoder_openstreetmap,
                        R.string.location_geocoder_off,
                },
                GeocoderSettings.GEOCODER_SETTING.get(this), savedInstanceState, KEY_GEOCODER);
    }

    private ChoicePicker createPicker(boolean available, int rowResId, int summaryResId,
            int titleResId, int[] values, int[] labelResIds, int currentValue,
            Bundle savedInstanceState, String key) {
        if (!available) {
            findViewById(rowResId).setVisibility(View.GONE);
            return null;
        }
        int value = savedInstanceState == null ? currentValue
                : savedInstanceState.getInt(key, currentValue);
        return new ChoicePicker(rowResId, summaryResId, titleResId, values, labelResIds, value);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mSuplPicker != null) {
            outState.putInt(KEY_SUPL, mSuplPicker.getValue());
        }
        if (mPsdsPicker != null) {
            outState.putInt(KEY_PSDS, mPsdsPicker.getValue());
        }
        if (mNetworkLocationPicker != null) {
            outState.putInt(KEY_NETWORK_LOCATION, mNetworkLocationPicker.getValue());
        }
        if (mGeocoderPicker != null) {
            outState.putInt(KEY_GEOCODER, mGeocoderPicker.getValue());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        boolean checked = mLocationManager.isLocationEnabled();
        if (mUserManager.isManagedProfile()) {
            checked &= mUserManager.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION);
        }
        mLocationAccess.setChecked(checked);
    }

    @Override
    protected void onNextPressed() {
        mLocationManager.setLocationEnabledForUser(mLocationAccess.isChecked(),
                Process.myUserHandle());
        if (mUserManager.isManagedProfile()) {
            mUserManager.setUserRestriction(UserManager.DISALLOW_SHARE_LOCATION,
                    !mLocationAccess.isChecked());
        }
        Settings.Global.putInt(getContentResolver(), Settings.Global.ASSISTED_GPS_ENABLED,
                mLocationAgpsAccess.isChecked() ? 1 : 0);
        if (mSuplPicker != null) {
            GnssSettings.SUPL_SETTING.put(this, mSuplPicker.getValue());
        }
        if (mPsdsPicker != null) {
            GnssSettings.getPsdsSetting(this).put(this, mPsdsPicker.getValue());
        }
        if (mNetworkLocationPicker != null) {
            NetworkLocationSettings.NETWORK_LOCATION_SETTING.put(this,
                    mNetworkLocationPicker.getValue());
        }
        if (mGeocoderPicker != null) {
            GeocoderSettings.GEOCODER_SETTING.put(this, mGeocoderPicker.getValue());
        }
        super.onNextPressed();
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.location_settings;
    }

    @Override
    protected int getTitleResId() {
        return R.string.setup_location;
    }

    @Override
    protected int getIconResId() {
        return R.drawable.ic_location;
    }

    private final class ChoicePicker {

        private final int mTitleResId;
        private final int[] mValues;
        private final int[] mLabelResIds;
        private final TextView mSummaryView;

        private int mValue;

        private ChoicePicker(int rowResId, int summaryResId, int titleResId, int[] values,
                int[] labelResIds, int value) {
            mTitleResId = titleResId;
            mValues = values;
            mLabelResIds = labelResIds;
            mSummaryView = findViewById(summaryResId);
            mValue = value;
            findViewById(rowResId).setOnClickListener(v -> showPicker());
            updateSummary();
        }

        private int getValue() {
            return mValue;
        }

        private int getSelectedIndex() {
            for (int i = 0; i < mValues.length; i++) {
                if (mValues[i] == mValue) {
                    return i;
                }
            }
            return 0;
        }

        private void updateSummary() {
            mSummaryView.setText(mLabelResIds[getSelectedIndex()]);
        }

        private void showPicker() {
            CharSequence[] labels = new CharSequence[mLabelResIds.length];
            for (int i = 0; i < mLabelResIds.length; i++) {
                labels[i] = getString(mLabelResIds[i]);
            }
            new AlertDialog.Builder(LocationSettingsActivity.this)
                    .setTitle(mTitleResId)
                    .setSingleChoiceItems(labels, getSelectedIndex(), (dialog, which) -> {
                        mValue = mValues[which];
                        updateSummary();
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }
}
