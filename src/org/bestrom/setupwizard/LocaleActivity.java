/*
 * SPDX-FileCopyrightText: 2016 The CyanogenMod Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.bestrom.setupwizard;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.NumberPicker;
import android.widget.Toast;

import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.util.LocaleUtils;

import com.google.android.setupcompat.util.SystemBarHelper;

import org.bestrom.setupwizard.widget.LocalePicker;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocaleActivity extends BaseSetupWizardActivity {

    private static final String TAG = LocaleActivity.class.getSimpleName();

    // ISO 3166 user-assigned regions used by AOSP pseudo-locales: en-XA (pseudo-accents),
    // ar-XB (pseudo-bidi) and the checked-in en-XC. LocaleList.isPseudoLocale() only knows
    // the first two, so en-XC leaks through LocalePicker.getAllAssetLocales() and shows up
    // in the wheel as a second "English (...)" row. XK is Kosovo, so filter on the explicit
    // set rather than on a "region starts with X" prefix.
    private static final Set<String> PSEUDO_LOCALE_REGIONS = Set.of("XA", "XB", "XC");

    private ArrayAdapter<com.android.internal.app.LocalePicker.LocaleInfo> mLocaleAdapter;
    private Locale mCurrentLocale;
    private int[] mAdapterIndices;
    private LocalePicker mLanguagePicker;
    private ExecutorService mFetchUpdateSimLocaleTask;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mPendingLocaleUpdate;
    private boolean mPaused = true;

    private final Runnable mUpdateLocale = new Runnable() {
        public void run() {
            if (mCurrentLocale != null) {
                mLanguagePicker.setEnabled(false);
                com.android.internal.app.LocalePicker.updateLocale(mCurrentLocale);
            }
        }
    };

    private final BroadcastReceiver mSimChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                fetchAndUpdateSimLocale();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.setBackButtonVisible(getWindow(), true);
        setNextText(R.string.next);
        mLanguagePicker = findViewById(R.id.locale_list);
        mLanguagePicker.setNextRight(getNextButton().getId());
        mLanguagePicker.requestFocus();
        if (getResources().getBoolean(R.bool.config_isLargeNoTouch)) {
            mLanguagePicker.setOnClickListener((View v) -> getNextButton().performClick());
        }
        loadLanguages();
    }

    @Override
    public void onPause() {
        super.onPause();
        mPaused = true;
        unregisterReceiver(mSimChangedReceiver);
    }

    @Override
    public void onResume() {
        super.onResume();
        mPaused = false;
        registerReceiver(mSimChangedReceiver,
                new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED));
        if (mLanguagePicker != null) {
            mLanguagePicker.setEnabled(true);
        }
        if (mPendingLocaleUpdate) {
            mPendingLocaleUpdate = false;
            fetchAndUpdateSimLocale();
        }
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.setup_locale;
    }

    @Override
    protected int getTitleResId() {
        return R.string.setup_locale;
    }

    @Override
    protected int getIconResId() {
        return R.drawable.ic_locale;
    }

    private void loadLanguages() {
        mLocaleAdapter = com.android.internal.app.LocalePicker.constructAdapter(this,
                R.layout.locale_picker_item, R.id.locale);
        mCurrentLocale = Locale.getDefault();
        fetchAndUpdateSimLocale();
        final int count = mLocaleAdapter.getCount();
        final int[] indices = new int[count];
        final String[] allLabels = new String[count];
        final Set<String> seenLabels = new HashSet<>(count);
        int currentLocaleIndex = 0;
        int kept = 0;
        for (int i = 0; i < count; i++) {
            final com.android.internal.app.LocalePicker.LocaleInfo localLocaleInfo =
                    mLocaleAdapter.getItem(i);
            if (localLocaleInfo == null) {
                continue;
            }
            final Locale localLocale = localLocaleInfo.getLocale();
            final String label = localLocaleInfo.getLabel();
            if (localLocale == null || label == null) {
                continue;
            }
            // getAllAssetLocales() enumerates the raw framework asset locales and drops
            // pseudo-locales only via LocaleList.isPseudoLocale() (en-XA/ar-XB), so the
            // checked-in en-XC pseudo-locale shows up as a stray extra English row. It
            // also never de-duplicates the visible labels.
            if (PSEUDO_LOCALE_REGIONS.contains(localLocale.getCountry())
                    || !seenLabels.add(label)) {
                continue;
            }
            if (localLocale.equals(mCurrentLocale)) {
                // Index into the filtered list, not into the adapter: the wheel wraps,
                // so an out-of-range preselection would silently land on a random row.
                currentLocaleIndex = kept;
            }
            indices[kept] = i;
            allLabels[kept] = label;
            kept++;
        }
        mAdapterIndices = Arrays.copyOf(indices, kept);
        final String[] labels = Arrays.copyOf(allLabels, kept);
        if (labels.length == 0) {
            // setMaxValue(-1) would throw; leave the picker empty rather than crash.
            Log.e(TAG, "No selectable locales; leaving the picker empty");
            return;
        }
        mLanguagePicker.setDisplayedValues(labels);
        mLanguagePicker.setMaxValue(labels.length - 1);
        mLanguagePicker.setValue(currentLocaleIndex);
        mLanguagePicker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        mLanguagePicker.setOnValueChangedListener((pkr, oldVal, newVal) -> setLocaleFromPicker());

        mLanguagePicker.setOnScrollListener((view, scrollState) -> {
            if (scrollState == NumberPicker.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                ((SetupWizardApp) getApplication()).setIgnoreSimLocale(true);
            }
        });
    }

    private void setLocaleFromPicker() {
        ((SetupWizardApp) getApplication()).setIgnoreSimLocale(true);
        int i = mAdapterIndices[mLanguagePicker.getValue()];
        final com.android.internal.app.LocalePicker.LocaleInfo localLocaleInfo =
                mLocaleAdapter.getItem(i);
        onLocaleChanged(localLocaleInfo.getLocale());
    }

    private void onLocaleChanged(Locale paramLocale) {
        mLanguagePicker.setEnabled(true);
        mHandler.removeCallbacks(mUpdateLocale);
        mCurrentLocale = paramLocale;
        mHandler.postDelayed(mUpdateLocale, 1000);
    }

    private void fetchAndUpdateSimLocale() {
        if (((SetupWizardApp) getApplication()).ignoreSimLocale() || isDestroyed()) {
            return;
        }
        if (mPaused) {
            mPendingLocaleUpdate = true;
            return;
        }
        if (mFetchUpdateSimLocaleTask != null) {
            mFetchUpdateSimLocaleTask.shutdown();
        }
        mFetchUpdateSimLocaleTask = Executors.newSingleThreadExecutor();
        mFetchUpdateSimLocaleTask.execute(() -> {
            Locale locale = null;
            Activity activity = LocaleActivity.this;
            if (!activity.isFinishing() || !activity.isDestroyed()) {
                // If the sim is currently pin locked, return
                TelephonyManager telephonyManager = (TelephonyManager)
                        activity.getSystemService(Context.TELEPHONY_SERVICE);
                int state = telephonyManager.getSimState();
                if (state == TelephonyManager.SIM_STATE_PIN_REQUIRED ||
                        state == TelephonyManager.SIM_STATE_PUK_REQUIRED) {
                    return;
                }

                final SubscriptionManager subscriptionManager =
                        activity.getSystemService(SubscriptionManager.class);
                List<SubscriptionInfo> activeSubs =
                        subscriptionManager.getActiveSubscriptionInfoList();
                if (activeSubs == null || activeSubs.isEmpty()) {
                    return;
                }

                // Fetch locale for active sim's MCC
                final String mccString = activeSubs.get(0).getMccString();
                try {
                    if (mccString != null && !mccString.isEmpty()) {
                        int mcc = Integer.parseInt(mccString);
                        locale = LocaleUtils.getLocaleFromMccMnc(activity, mcc, null, null);
                    } else {
                        Log.w(TAG, "Unexpected mccString: '" + mccString + "'");
                    }
                } catch (NumberFormatException e) {
                    Log.w(TAG, "mccString not a number: '" + mccString + "'", e);
                }

                // If that fails, fall back to preferred languages reported
                // by the sim
                if (locale == null) {
                    Locale simLocale = telephonyManager.getSimLocale();
                    if (simLocale != null) {
                        locale = simLocale;
                    }
                }
                Locale finalLocale = locale;
                mHandler.post(() -> {
                    if (finalLocale != null && !finalLocale.equals(mCurrentLocale)) {
                        if (!((SetupWizardApp) getApplication()).ignoreSimLocale()
                                && !isDestroyed()) {
                            String label = getString(R.string.sim_locale_changed,
                                    finalLocale.getDisplayName());
                            Toast.makeText(LocaleActivity.this, label, Toast.LENGTH_SHORT).show();
                            onLocaleChanged(finalLocale);
                            ((SetupWizardApp) getApplication()).setIgnoreSimLocale(true);
                        }
                    }
                });
            }
        });
    }
}
