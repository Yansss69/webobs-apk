package com.webobs.studio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView webView;
    private Process mediaMtxProcess;
    private static final int PERMISSION_REQ_CODE = 101;
    private static final int FILE_CHOOSER_REQ_CODE = 102;
    private ValueCallback<Uri[]> uploadMessageAboveL;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        hideSystemUI();
        checkAndRequestSystemPermissions();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessageAboveL != null) uploadMessageAboveL.onReceiveValue(null);
                uploadMessageAboveL = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQ_CODE);
                } catch (Exception e) {
                    uploadMessageAboveL = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void startStreamToFacebook(String rtmpUrl, String streamKey) {
                runOnUiThread(() -> {
                    boolean ok = launchMediaMtx(rtmpUrl, streamKey);
                    if (ok) {
                        Toast.makeText(MainActivity.this, "RTMP Relay Terhubung ke FB Live!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Gagal menjalankan engine RTMP", Toast.LENGTH_LONG).show();
                    }
                });
            }

            @JavascriptInterface
            public void stopStream() {
                runOnUiThread(() -> {
                    killMediaMtx();
                    Toast.makeText(MainActivity.this, "Stream Stopped", Toast.LENGTH_SHORT).show();
                });
            }
        }, "AndroidBridge");

        launchMediaMtx("", "");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean launchMediaMtx(String rtmpUrl, String streamKey) {
        killMediaMtx();
        try {
            // Path executable resmi: dari direktori library native yang diizinkan OS
            File binFile = new File(getApplicationInfo().nativeLibraryDir, "libmediamtx.so");
            if (!binFile.exists() || !binFile.canExecute()) {
                // Fallback: salin ke filesDir dan set executable
                binFile = new File(getFilesDir(), "mediamtx");
                if (!binFile.exists() || binFile.length() == 0) {
                    copyAsset("mediamtx", binFile);
                }
                binFile.setExecutable(true, false);
            }

            String forwardBlock = "";
            if (streamKey != null && !streamKey.trim().isEmpty()) {
                String base = rtmpUrl.trim();
                if (!base.endsWith("/")) base += "/";
                String fullTarget = base + streamKey.trim();
                forwardBlock = 
                    "    forward:\n" +
                    "      - dest: " + fullTarget + "\n";
            }

            String ymlContent = 
                "api: yes\n" +
                "apiAddress: 127.0.0.1:9997\n" +
                "webrtcAddress: 127.0.0.1:8889\n" +
                "webrtcAllowStreamCreation: yes\n" +
                "paths:\n" +
                "  live:\n" +
                "    source: publisher\n" +
                forwardBlock;

            File confFile = new File(getFilesDir(), "mediamtx.yml");
            try (FileOutputStream fos = new FileOutputStream(confFile)) {
                fos.write(ymlContent.getBytes(StandardCharsets.UTF_8));
            }

            ProcessBuilder pb = new ProcessBuilder(binFile.getAbsolutePath(), confFile.getAbsolutePath());
            pb.directory(getFilesDir());
            mediaMtxProcess = pb.start();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void killMediaMtx() {
        if (mediaMtxProcess != null) {
            mediaMtxProcess.destroy();
            mediaMtxProcess = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQ_CODE) {
            if (uploadMessageAboveL == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                } else if (data.getClipData() != null) {
                    final int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
            uploadMessageAboveL.onReceiveValue(results);
            uploadMessageAboveL = null;
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void checkAndRequestSystemPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO };
            boolean needReq = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    needReq = true;
                    break;
                }
            }
            if (needReq) requestPermissions(perms, PERMISSION_REQ_CODE);
        }
    }

    private void copyAsset(String assetName, File outFile) throws Exception {
        try (InputStream in = getAssets().open(assetName); OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        killMediaMtx();
    }
}
