package com.kfdxplus.firetv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

/** Opens the KFDX+ web app full screen in its TV layout; episodes play in the native full-screen player. */
public class MainActivity extends Activity {
    private static final int PLAY = 7;
    private WebView web;
    private FrameLayout root;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;
    private WebChromeClient chrome;

    /**
     * Injected after the page loads. Swaps the web player for the native one: tapping an episode hands
     * that episode plus the newer ones after it to PlayerActivity, and saved progress flows back in.
     */
    private static final String HOOK =
        "(function(){if(window.__kfdxNative||typeof play!=='function'||typeof STREAM!=='function')return;window.__kfdxNative=true;" +
        "window.play=function(e){var eps=e.show.eps,items=[];" +
        " for(var i=e.idx;i>=0;i--){var x=eps[i];items.push({id:String(x.id),title:x.title,show:x.show.name,url:STREAM(x.id)});}" +
        " var p=prog[e.id],r=(p&&p.t>20&&p.d&&p.t<p.d-30)?p.t*1000:0;" +
        " KFDXNative.play(JSON.stringify({items:items,resumeMs:r}));};" +
        "window.__kfdxSave=function(id,t,d){prog[id]={t:t,d:d,at:Date.now()};store.set(prog);};" +
        "window.__kfdxRefresh=function(){var a=document.activeElement,sel=a&&a.dataset&&a.dataset.ep?'[data-ep=\"'+a.dataset.ep+'\"]':null;" +
        " if(!document.getElementById('sheet').hidden&&openShow){showSheet(openShow.slug,false);}else{render();}" +
        " var el=(sel&&document.querySelector((document.getElementById('sheet').hidden?'':'#sheet ')+sel))||document.querySelector('[data-ep]');" +
        " if(el&&typeof focusEl==='function')focusEl(el);};" +
        "})();";

    public class Bridge {
        @JavascriptInterface public void play(final String cfg) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra("cfg", cfg);
                startActivityForResult(i, PLAY);
            });
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b1326"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b1326"));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);              // keeps "Keep watching" progress
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);                        // ignore the TV's text-size setting so nothing runs off the bottom
        web.addJavascriptInterface(new Bridge(), "KFDXNative");

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { v.evaluateJavascript(HOOK, null); }
        });
        chrome = new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                fullView = view; fullCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            @Override public void onHideCustomView() {
                if (fullView != null) { root.removeView(fullView); fullView = null; }
                if (fullCallback != null) { fullCallback.onCustomViewHidden(); fullCallback = null; }
            }
        };
        web.setWebChromeClient(chrome);

        if (state != null) web.restoreState(state);
        else {
            String url = BuildConfig.APP_URL;
            web.loadUrl(url + (url.contains("?") ? "&" : "?") + "tv");
        }
        web.requestFocus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PLAY) return;
        StringBuilder js = new StringBuilder("(function(){if(!window.__kfdxSave)return;");
        try {
            if (data != null && data.getStringExtra("progress") != null) {
                JSONArray arr = new JSONArray(data.getStringExtra("progress"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    js.append("__kfdxSave(").append(JSONObject.quote(o.getString("id")))
                      .append(",").append(o.getLong("t")).append(",").append(o.getLong("d")).append(");");
                }
            }
        } catch (Exception ignored) { }
        js.append("__kfdxRefresh();})();");
        web.evaluateJavascript(js.toString(), null);
        web.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Back closes the show page first (the web app keeps it in its history), then exits.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (fullView != null) { chrome.onHideCustomView(); return true; }
            if (web.canGoBack()) { web.goBack(); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
}
