package com.webobs.studio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView webView;
    private Process mediaMtxProcess;
    private static final int PERMISSION_REQ_CODE = 101;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkAndRequestSystemPermissions();
        startEmbeddedMediaMTX();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void checkAndRequestSystemPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            };
            boolean needReq = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    needReq = true;
                    break;
                }
            }
            if (needReq) {
                requestPermissions(perms, PERMISSION_REQ_CODE);
            }
        }
    }

    private void startEmbeddedMediaMTX() {
        new Thread(() -> {
            try {
                File binFile = new File(getFilesDir(), "mediamtx");
                File confFile = new File(getFilesDir(), "mediamtx.yml");

                if (!binFile.exists() || binFile.length() == 0) {
                    copyAsset("mediamtx", binFile);
                    binFile.setExecutable(true, false);
                }

                if (!confFile.exists()) {
                    copyAsset("mediamtx.yml", confFile);
                }

                ProcessBuilder pb = new ProcessBuilder(binFile.getAbsolutePath(), confFile.getAbsolutePath());
                pb.directory(getFilesDir());
                mediaMtxProcess = pb.start();
            } catch (Exception ignored) {}
        }).start();
    }

    private void copyAsset(String assetName, File outFile) throws Exception {
        try (InputStream in = getAssets().open(assetName);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaMtxProcess != null) {
            mediaMtxProcess.destroy();
        }
    }
}
