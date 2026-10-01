package com.webobs.studio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
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

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "WebOBS";
    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private static final int PERMISSION_REQ_CODE = 101;
    private static final int FILE_CHOOSER_REQ_CODE = 102;
    private ValueCallback<Uri[]> uploadMessageAboveL;

    // Pipeline Streaming via Named Pipe FFmpegKit
    private String pipePath = null;
    private FileOutputStream pipeOutputStream = null;
    private FFmpegSession currentFFmpegSession = null;
    private boolean isBroadcasting = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Global Exception caught: " + throwable.getMessage(), throwable);
        });

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        hideSystemUI();
        checkAndRequestSystemPermissions();
        setupWakeLock();

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
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                    } catch (Exception e) {
                        Log.e(TAG, "Permission request error", e);
                    }
                });
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
                    try {
                        acquireWakeLock();
                        startPipeBroadcast(rtmpUrl, streamKey);
                    } catch (Exception e) {
                        Log.e(TAG, "Error startStream: ", e);
                        Toast.makeText(MainActivity.this, "Gagal memulai stream: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @JavascriptInterface
            public void sendChunkBase64(String base64Data) {
                if (!isBroadcasting || pipeOutputStream == null) return;
                try {
                    byte[] data = Base64.decode(base64Data, Base64.NO_WRAP);
                    pipeOutputStream.write(data);
                    pipeOutputStream.flush();
                } catch (Exception e) {
                    // Pipe stream catch
                }
            }

            @JavascriptInterface
            public void stopStream() {
                runOnUiThread(() -> {
                    try {
                        stopPipeBroadcast();
                        releaseWakeLock();
                        Toast.makeText(MainActivity.this, "Stream Berakhir", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopStream: ", e);
                    }
                });
            }
        }, "AndroidBridge");

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void startPipeBroadcast(String rtmpUrl, String streamKey) {
        stopPipeBroadcast();
        isBroadcasting = true;

        String base = (rtmpUrl != null && !rtmpUrl.trim().isEmpty()) ? rtmpUrl.trim() : "rtmps://live-api-s.facebook.com:443/rtmp/";
        if (!base.endsWith("/")) base += "/";
        final String targetRtmp = base + (streamKey != null ? streamKey.trim() : "");

        new Thread(() -> {
            try {
                // Buat Named Pipe Android resmi via FFmpegKitConfig
                pipePath = FFmpegKitConfig.registerNewFFmpegPipe(MainActivity.this);
                
                String cmd = "-re -i " + pipePath + " " +
                             "-c:v libx264 -preset ultrafast -b:v 2500k -maxrate 2500k -bufsize 5000k " +
                             "-pix_fmt yuv420p -g 60 -c:a aac -b:a 128k -ar 44100 -f flv \"" + targetRtmp + "\"";

                currentFFmpegSession = FFmpegKit.executeAsync(cmd, session -> {
                    Log.i(TAG, "FFmpeg Session selesai.");
                    isBroadcasting = false;
                });

                // Buka output stream ke named pipe
                pipeOutputStream = new FileOutputStream(pipePath);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Live Facebook Dimulai!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Pipe setup error: ", e);
                isBroadcasting = false;
            }
        }).start();
    }

    private void stopPipeBroadcast() {
        isBroadcasting = false;
        try {
            if (pipeOutputStream != null) {
                pipeOutputStream.close();
                pipeOutputStream = null;
            }
        } catch (Exception e) {}

        try {
            if (currentFFmpegSession != null) {
                FFmpegKit.cancel(currentFFmpegSession.getSessionId());
                currentFFmpegSession = null;
            }
        } catch (Exception e) {}

        if (pipePath != null) {
            FFmpegKitConfig.closeFFmpegPipe(pipePath);
            pipePath = null;
        }
    }

    private void setupWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebOBS:LiveLock");
            }
        } catch (Exception e) {}
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(12 * 60 * 60 * 1000L);
        } catch (Exception e) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
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

    private void checkAndRequestSystemPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> list = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.CAMERA);
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.RECORD_AUDIO);
            }
            if (!list.isEmpty()) {
                requestPermissions(list.toArray(new String[0]), PERMISSION_REQ_CODE);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPipeBroadcast();
    }
}
