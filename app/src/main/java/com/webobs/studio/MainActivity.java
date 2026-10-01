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

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "WebOBS";
    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private static final int PERMISSION_REQ_CODE = 101;
    private static final int FILE_CHOOSER_REQ_CODE = 102;
    private ValueCallback<Uri[]> uploadMessageAboveL;

    private EmbeddedStreamServer wsServer = null;
    private String pipePath = null;
    private FileOutputStream pipeOutStream = null;
    private FFmpegSession currentSession = null;
    private volatile boolean isLiveRunning = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught Exception: " + throwable.getMessage(), throwable);
        });

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        hideSystemUI();
        checkAndRequestSystemPermissions();
        setupWakeLock();

        // Start Local WebSocket Server di port 8088
        startInternalWebSocketServer();

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
                    } catch (Exception ignored) {}
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
                        startLiveBroadcast(rtmpUrl, streamKey);
                    } catch (Exception e) {
                        Log.e(TAG, "startStream error: ", e);
                    }
                });
            }

            @JavascriptInterface
            public void stopStream() {
                runOnUiThread(() -> {
                    try {
                        stopLiveBroadcast();
                        releaseWakeLock();
                        Toast.makeText(MainActivity.this, "Stream Dihentikan", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "stopStream error: ", e);
                    }
                });
            }
        }, "AndroidBridge");

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void startInternalWebSocketServer() {
        try {
            wsServer = new EmbeddedStreamServer(new InetSocketAddress(8088));
            wsServer.setReuseAddr(true);
            wsServer.start();
            Log.i(TAG, "Embedded WebSocket Server berjalan di port 8088");
        } catch (Exception e) {
            Log.e(TAG, "Gagal start WS Server: ", e);
        }
    }

    private class EmbeddedStreamServer extends WebSocketServer {
        public EmbeddedStreamServer(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            Log.i(TAG, "Koneksi biner WebSocket dari WebView terhubung!");
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {}

        @Override
        public void onMessage(WebSocket conn, String message) {}

        @Override
        public void onMessage(WebSocket conn, ByteBuffer bytes) {
            // Menerima data biner video murni langsung dari WebView tanpa Base64!
            if (!isLiveRunning || pipeOutStream == null) return;
            try {
                byte[] raw = new byte[bytes.remaining()];
                bytes.get(raw);
                pipeOutStream.write(raw);
                pipeOutStream.flush();
            } catch (Exception ignored) {}
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {}

        @Override
        public void onStart() {}
    }

    private void startLiveBroadcast(String rtmpUrl, String streamKey) {
        stopLiveBroadcast();
        isLiveRunning = true;

        String base = (rtmpUrl != null && !rtmpUrl.trim().isEmpty()) ? rtmpUrl.trim() : "rtmps://live-api-s.facebook.com:443/rtmp/";
        if (!base.endsWith("/")) base += "/";
        final String fullTarget = base + ((streamKey != null) ? streamKey.trim() : "");

        new Thread(() -> {
            try {
                pipePath = FFmpegKitConfig.registerNewFFmpegPipe(MainActivity.this);

                // Command FFmpeg profesional untuk transmisi RTMPS Facebook Live
                String cmd = "-f webm -re -i " + pipePath + " " +
                             "-c:v libx264 -preset ultrafast -tune zerolatency -b:v 2500k -maxrate 2500k -bufsize 5000k " +
                             "-pix_fmt yuv420p -g 60 -c:a aac -b:a 128k -ar 44100 " +
                             "-flvflags no_duration_filesize -f flv \"" + fullTarget + "\"";

                currentSession = FFmpegKit.executeAsync(cmd, session -> {
                    Log.i(TAG, "FFmpeg Session Code: " + session.getReturnCode());
                    isLiveRunning = false;
                });

                pipeOutStream = new FileOutputStream(pipePath);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Live Facebook Terhubung!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Broadcast error: ", e);
                isLiveRunning = false;
            }
        }).start();
    }

    private void stopLiveBroadcast() {
        isLiveRunning = false;
        try {
            if (pipeOutStream != null) {
                pipeOutStream.close();
                pipeOutStream = null;
            }
        } catch (Exception ignored) {}

        try {
            if (currentSession != null) {
                FFmpegKit.cancel(currentSession.getSessionId());
                currentSession = null;
            }
        } catch (Exception ignored) {}

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
        } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(12 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
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
        stopLiveBroadcast();
        try {
            if (wsServer != null) wsServer.stop();
        } catch (Exception ignored) {}
    }
}
