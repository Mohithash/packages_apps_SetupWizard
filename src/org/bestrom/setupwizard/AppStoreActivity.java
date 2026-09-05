/*
 * SPDX-FileCopyrightText: 2026 VoltageOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.bestrom.setupwizard;

import static com.google.android.setupcompat.util.ResultCodes.RESULT_SKIP;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.bestrom.setupwizard.util.SetupWizardUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppStoreActivity extends BaseSetupWizardActivity {

    private static final String TAG = AppStoreActivity.class.getSimpleName();

    private static final String APPSTORE_PACKAGE = "app.grapheneos.apps";
    private static final String APPSTORE_APK_URL =
            "https://github.com/GrapheneOS/AppStore/releases/download/36/AppStore-36.apk";

    private static final String APK_FILE_NAME = "AppStore.apk";
    private static final String ACTION_INSTALL_RESULT =
            "org.bestrom.setupwizard.APPSTORE_INSTALL_RESULT";

    private static final int BUFFER_SIZE = 8192;
    private static final int TIMEOUT_MS = 30000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private ExecutorService mInstallTask;
    private Button mInstallButton;
    private ProgressBar mProgressBar;
    private TextView mStatusView;
    private boolean mReceiverRegistered;

    private final BroadcastReceiver mInstallResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleInstallResult(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (shouldSkip()) {
            finishAction(RESULT_SKIP);
            return;
        }
        setNextText(R.string.next);
        mInstallButton = findViewById(R.id.appstore_install_button);
        mProgressBar = findViewById(R.id.appstore_progress);
        mStatusView = findViewById(R.id.appstore_status);
        mInstallButton.setOnClickListener(v -> startInstall());
        registerReceiver(mInstallResultReceiver, new IntentFilter(ACTION_INSTALL_RESULT),
                Context.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mReceiverRegistered) {
            unregisterReceiver(mInstallResultReceiver);
            mReceiverRegistered = false;
        }
        if (mInstallTask != null) {
            mInstallTask.shutdownNow();
            mInstallTask = null;
        }
        mHandler.removeCallbacksAndMessages(null);
    }

    private boolean shouldSkip() {
        return !SetupWizardUtils.isOwner()
                || SetupWizardUtils.isGmsCoreInstalled(this)
                || SetupWizardUtils.isPackageInstalled(this, APPSTORE_PACKAGE)
                || !SetupWizardUtils.isNetworkConnectedToInternet(this);
    }

    private void startInstall() {
        mInstallButton.setEnabled(false);
        mProgressBar.setVisibility(View.VISIBLE);
        mProgressBar.setIndeterminate(false);
        mProgressBar.setProgress(0);
        mStatusView.setText(R.string.appstore_downloading);
        if (mInstallTask != null) {
            mInstallTask.shutdownNow();
        }
        mInstallTask = Executors.newSingleThreadExecutor();
        mInstallTask.execute(() -> {
            File apk = new File(getCacheDir(), APK_FILE_NAME);
            try {
                download(apk);
                mHandler.post(this::onDownloadFinished);
                install(apk);
            } catch (IOException e) {
                Log.e(TAG, "Failed to download or install the App Store", e);
                mHandler.post(() -> onFailed(R.string.appstore_failed));
            } finally {
                apk.delete();
            }
        });
    }

    private void download(File destination) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(APPSTORE_APK_URL).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP response " + responseCode);
            }
            long contentLength = connection.getContentLengthLong();
            try (InputStream input = connection.getInputStream();
                    OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long downloaded = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download interrupted");
                    }
                    output.write(buffer, 0, read);
                    downloaded += read;
                    if (contentLength > 0) {
                        int progress = (int) (downloaded * 100 / contentLength);
                        mHandler.post(() -> mProgressBar.setProgress(progress));
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void install(File apk) throws IOException {
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(APPSTORE_PACKAGE);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream input = new FileInputStream(apk);
                    OutputStream output = session.openWrite(APK_FILE_NAME, 0, apk.length())) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            }
            Intent intent = new Intent(ACTION_INSTALL_RESULT).setPackage(getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pendingIntent.getIntentSender());
        }
    }

    private void handleInstallResult(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirmation == null) {
                    onFailed(R.string.appstore_failed);
                    return;
                }
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(confirmation);
            }
            case PackageInstaller.STATUS_SUCCESS -> onInstalled();
            default -> onFailed(R.string.appstore_failed);
        }
    }

    private void onDownloadFinished() {
        mProgressBar.setIndeterminate(true);
        mStatusView.setText(R.string.appstore_installing);
    }

    private void onInstalled() {
        mProgressBar.setIndeterminate(false);
        mProgressBar.setVisibility(View.GONE);
        mInstallButton.setVisibility(View.GONE);
        mStatusView.setText(R.string.appstore_installed);
    }

    private void onFailed(int messageResId) {
        mProgressBar.setIndeterminate(false);
        mProgressBar.setVisibility(View.GONE);
        mInstallButton.setEnabled(true);
        mStatusView.setText(messageResId);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.appstore_page;
    }

    @Override
    protected int getTitleResId() {
        return R.string.setup_appstore;
    }

    @Override
    protected int getIconResId() {
        return R.drawable.ic_system_update;
    }
}
