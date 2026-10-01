package com.webobs.studio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
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
import com.arthenica.ffmpegkit.FFmpegSession;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {
    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private static final int PERMISSION_REQ_CODE = 101;
    private static final int FILE_CHOOSER_REQ_CODE = 102;
    private ValueCallback<Uri[]> uploadMessageAboveL;

    // Embedded Engine Server
    private ServerSocket bridgeServerSocket;
    private Socket activeClientSocket;
    private Thread serverThread;
    private FFmpegSession currentFFmpegSession;
    private boolean isBroadcasting = false;

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
                    acquireWakeLock();
                    showStreamingNotification();
                    startEmbeddedFFmpegBroadcast(rtmpUrl, streamKey);
                    Toast.makeText(MainActivity.this, "Live FB Standalone Mengudara!", Toast.LENGTH_SHORT).show();
                });
            }

            @JavascriptInterface
            public void stopStream() {
                runOnUiThread(() -> {
                    stopEmbeddedBroadcast();
                    releaseWakeLock();
                    removeStreamingNotification();
                    Toast.makeText(MainActivity.this, "Stream Dihentikan", Toast.LENGTH_SHORT).show();
                });
            }
        }, "AndroidBridge");

        // Mulai listener socket internal APK di port 8080
        startInternalSocketServer();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void startInternalSocketServer() {
        serverThread = new Thread(() -> {
            try {
                bridgeServerSocket = new ServerSocket(8080);
                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = bridgeServerSocket.accept();
                    activeClientSocket = socket;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.start();
    }

    private void startEmbeddedFFmpegBroadcast(String rtmpUrl, String streamKey) {
        stopCurrentFFmpeg();
        isBroadcasting = true;

        String base = (rtmpUrl != null && !rtmpUrl.trim().isEmpty()) ? rtmpUrl.trim() : "rtmps://live-api-s.facebook.com:443/rtmp/";
        if (!base.endsWith("/")) base += "/";
        final String targetRtmp = base + (streamKey != null ? streamKey.trim() : "");

        new Thread(() -> {
            try {
                // Menghubungkan socket lokal internal ke FFmpeg Kit
                String cmd = "-f webm -i tcp://127.0.0.1:8080?listen=1 " +
                             "-c:v libx264 -preset veryfast -b:v 2500k -maxrate 2500k -bufsize 5000k " +
                             "-pix_fmt yuv420p -g 60 -c:a aac -b:a 128k -ar 44100 -f flv \"" + targetRtmp + "\"";

                currentFFmpegSession = FFmpegKit.executeAsync(cmd, session -> {
                    isBroadcasting = false;
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void stopCurrentFFmpeg() {
        if (currentFFmpegSession != null) {
            FFmpegKit.cancel(currentFFmpegSession.getSessionId());
            currentFFmpegSession = null;
        }
        isBroadcasting = false;
    }

    private void stopEmbeddedBroadcast() {
        stopCurrentFFmpeg();
        try {
            if (activeClientSocket != null) {
                activeClientSocket.close();
                activeClientSocket = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebOBS:LiveBackgroundLock");
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void showStreamingNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("webobs_live", "WebOBS Streaming", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);

            Notification notification = new Notification.Builder(this, "webobs_live")
                .setContentTitle("WebOBS Studio LIVE")
                .setContentText("Siaran langsung mandiri sedang aktif...")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();

            if (nm != null) nm.notify(999, notification);
        }
    }

    private void removeStreamingNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(999);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopEmbeddedBroadcast();
        try {
            if (bridgeServerSocket != null) bridgeServerSocket.close();
            if (serverThread != null) serverThread.interrupt();
        } catch (Exception e) {}
    }
}
