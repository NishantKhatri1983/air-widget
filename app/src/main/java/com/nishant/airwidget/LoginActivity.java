package com.nishant.airwidget;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Shows Xiaomi's own sign-in page. The password is typed there, never into this app; we only read the
 * resulting passToken cookie, which later lets us ask Xiaomi for an API session.
 */
public class LoginActivity extends Activity {
    private static final String TAG = "AirWidget";
    private static final String ACCOUNT = "https://account.xiaomi.com";
    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);      // force a fresh sign-in so the cookies we read are new
        cm.setAcceptCookie(true);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { check(); }
        });
        setContentView(web);
        Ui.applyInsets(this, web);
        web.loadUrl(ACCOUNT + "/pass/serviceLogin?sid=xiaomiio&_locale=en_US");
        h.postDelayed(poll, 1000);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (done) return;
            check();
            h.postDelayed(this, 1000);
        }
    };

    private void check() {
        if (done) return;
        String c = CookieManager.getInstance().getCookie(ACCOUNT);
        if (c == null) return;
        String uid = cookie(c, "userId"), pt = cookie(c, "passToken"), did = cookie(c, "deviceId");
        if (uid.isEmpty() || pt.isEmpty()) return;
        done = true;
        Prefs p = new Prefs(this);
        p.put("userId", uid);
        p.put("passToken", pt);
        if (!did.isEmpty()) p.put("deviceId", did);
        p.remove("ssecurity", "serviceToken");
        Log.i(TAG, "login ok, userId=" + uid);
        setResult(RESULT_OK);
        finish();
    }

    private static String cookie(String all, String name) {
        for (String part : all.split(";")) {
            String s = part.trim();
            if (s.startsWith(name + "=")) return s.substring(name.length() + 1);
        }
        return "";
    }

    @Override
    protected void onDestroy() {
        done = true;
        h.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
