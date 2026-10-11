package com.spotiops.two;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

/**
 * Hosts the Spotiops II player page in a full-screen WebView.
 * The page is served from the app's assets under a private https address, so its
 * storage (including songs downloaded for offline listening) stays inside the app.
 * It talks to Android through the "SpotiopsNative" bridge.
 */
public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String START_URL = "https://" + HOST + "/index.html";
    static final String ACTION_INSTALL_STATUS = "com.spotiops.two.INSTALL_STATUS";
    static final int BG = 0xFF06080A;

    static WeakReference<MainActivity> current = new WeakReference<>(null);

    private WebView web;
    private boolean askedNotifications = false;
    private volatile boolean updating = false;
    private BroadcastReceiver installReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);

        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // The page is https (local assets) but home Jellyfin servers are usually plain http.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " SpotiopsII/" + versionName());

        web.addJavascriptInterface(new Bridge(), "SpotiopsNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!HOST.equals(url.getHost())) return null;
                String path = url.getPath();
                if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mimeFor(path), "UTF-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        // Results from the system installer while an update is being installed.
        installReceiver = new BroadcastReceiver() {
            @Override
            @SuppressWarnings("deprecation")
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(confirm);
                        } catch (Exception e) {
                            updateStatus("error", "the installer couldn't open");
                        }
                    }
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    updateStatus("done", "");
                } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    updateStatus("cancelled", "");
                } else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT
                        || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    updateStatus("conflict", "");
                } else {
                    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    updateStatus("error", msg == null ? "" : msg);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(installReceiver, filter);
        }

        web.loadUrl(START_URL);
    }

    static String mimeFor(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    @SuppressWarnings("deprecation")
    int buildNumber() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "2" : v;
        } catch (Exception e) {
            return "2";
        }
    }

    void runJs(final String code) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    void updateStatus(String state, String detail) {
        runJs("window.__updateStatus && window.__updateStatus(" + JSONObject.quote(state) + "," + JSONObject.quote(detail) + ")");
    }

    /** Called by the playback notification and lock-screen controls. */
    static void command(String cmd) {
        MainActivity a = current.get();
        if (a != null) a.runJs("window.__spotiopsCmd && window.__spotiopsCmd(" + JSONObject.quote(cmd) + ")");
    }

    void askForNotifications() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return;
        askedNotifications = true;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** Downloads the APK from GitHub and hands it to the system installer. */
    void downloadAndInstall(String apkUrl) {
        HttpURLConnection conn = null;
        PackageInstaller.Session session = null;
        try {
            updateStatus("downloading", "0");
            conn = (HttpURLConnection) new URL(apkUrl).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("GitHub answered " + code);
            long total = conn.getContentLengthLong();

            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(getPackageName());
            if (total > 0) params.setSize(total);
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            try (InputStream in = conn.getInputStream();
                 OutputStream out = session.openWrite("update.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            updateStatus("downloading", String.valueOf(pct));
                        }
                    }
                }
                session.fsync(out);
            }

            updateStatus("installing", "");
            Intent callback = new Intent(ACTION_INSTALL_STATUS).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(this, 3, callback, flags);
            session.commit(pi.getIntentSender());
            session.close();
            session = null;
        } catch (Exception e) {
            if (session != null) {
                try {
                    session.abandon();
                } catch (Exception ignored) {
                }
            }
            updateStatus("error", e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
            updating = false;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.__spotiopsBack && window.__spotiopsBack()) ? 1 : 0", value -> {
            // Nothing left to go back to: keep the music playing and just leave the app.
            if (!"1".equals(value)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(installReceiver);
        } catch (Exception ignored) {
        }
        PlaybackService.stop();
        if (current.get() == this) current = new WeakReference<>(null);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Methods the page can call as window.SpotiopsNative.*. */
    class Bridge {

        @JavascriptInterface
        public void nowPlaying(final String title, final String artist, final String album,
                               final String artUrl, final boolean playing) {
            runOnUiThread(() -> {
                askForNotifications();
                PlaybackService.update(MainActivity.this, title, artist, album, artUrl, playing);
            });
        }

        @JavascriptInterface
        public void stopped() {
            runOnUiThread(PlaybackService::stop);
        }

        @JavascriptInterface
        public String version() {
            return versionName();
        }

        @JavascriptInterface
        public int buildNumber() {
            return MainActivity.this.buildNumber();
        }

        /** Returns "ok", "busy", or "permission" (the user must allow installs first). */
        @JavascriptInterface
        public String installUpdate(final String apkUrl) {
            if (apkUrl == null || !apkUrl.startsWith("https://github.com/")) return "error";
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                    }
                });
                return "permission";
            }
            if (updating) return "busy";
            updating = true;
            new Thread(() -> downloadAndInstall(apkUrl)).start();
            return "ok";
        }
    }
}
