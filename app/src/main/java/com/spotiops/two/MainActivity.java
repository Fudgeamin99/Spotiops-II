package com.spotiops.two;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import java.lang.ref.WeakReference;
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
    static final int BG = 0xFF06080A;

    static WeakReference<MainActivity> current = new WeakReference<>(null);

    private WebView web;
    private boolean askedNotifications = false;

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
        s.setUserAgentString(s.getUserAgentString() + " SpotiopsII/2.0.0");

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

    void runJs(final String code) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
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
            return "2.0.0";
        }
    }
}
