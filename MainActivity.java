package com.pcbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String HOME = "https://www.google.com";

    /** Mouse speed in CSS px per frame when the right stick is fully pushed. */
    private static final float MOUSE_MAX_SPEED = 28f;
    private static final float STICK_DEAD_ZONE = 0.12f;
    private static final float WASD_THRESHOLD = 0.35f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private LinearLayout topBar;
    private FrameLayout stage;

    private final List<View> controlViews = new ArrayList<>();
    private final List<JoystickView> sticks = new ArrayList<>();
    private boolean controlsVisible = true;

    private String vcScript = "";
    private boolean docStartScript = false;
    private volatile boolean pointerLocked = false;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    // ---- keyboard state ----
    private final LinkedHashMap<Integer, Integer> heldKeys = new LinkedHashMap<>();
    private int metaState = 0;
    private boolean repeating = false;

    // ---- mouse state ----
    private float stickX = 0f;
    private float stickY = 0f;
    private boolean mouseLoopRunning = false;
    private final boolean[] mouseDown = new boolean[3];

    private interface PressAction {
        void run(boolean down);
    }

    /** Receives the "pointer lock" state from the injected script. */
    private class Bridge {
        @JavascriptInterface
        public void onLock(boolean locked) {
            pointerLocked = locked;
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        vcScript = readAsset("vc.js");
        buildUi();
        setupWebView();
        buildControls();

        String start = HOME;
        Intent in = getIntent();
        if (in != null && in.getData() != null) {
            start = in.getDataString();
        }
        load(start);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) {
            load(intent.getDataString());
        }
    }

    @Override
    protected void onPause() {
        releaseAll();
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (pointerLocked) {
            js("window.__vc&&__vc.unlock()");
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    // =====================================================================
    // UI
    // =====================================================================

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView barButton(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(44));
        t.setMinHeight(dp(40));
        t.setOnClickListener(l);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);
        // keep the layout the same on Arabic / RTL devices
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xFF202124);
        topBar.setPadding(dp(4), dp(4), dp(4), dp(4));

        topBar.addView(barButton("←", v -> {
            if (web.canGoBack()) {
                web.goBack();
            }
        }));
        topBar.addView(barButton("→", v -> {
            if (web.canGoForward()) {
                web.goForward();
            }
        }));
        topBar.addView(barButton("↻", v -> web.reload()));

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(0xFF9AA0A6);
        urlBar.setHint("Search or type URL");
        urlBar.setTextSize(14);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable urlBg = new GradientDrawable();
        urlBg.setColor(0xFF3C4043);
        urlBg.setCornerRadius(dp(18));
        urlBar.setBackground(urlBg);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                load(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        urlLp.setMargins(dp(4), 0, dp(4), 0);
        topBar.addView(urlBar, urlLp);

        topBar.addView(barButton("🎮", v -> toggleControls()));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(this);
        stage.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    // =====================================================================
    // WebView
    // =====================================================================

    private static String desktopUserAgent(String defaultUa) {
        String major = "130";
        if (defaultUa != null) {
            Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(defaultUa);
            if (m.find()) {
                major = m.group(1);
            }
        }
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + major + ".0.0.0 Safari/537.36";
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(desktopUserAgent(s.getUserAgentString()));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.addJavascriptInterface(new Bridge(), "__vcNative");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                    web, vcScript, new HashSet<>(Collections.singletonList("*")));
            docStartScript = true;
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if (scheme == null || "http".equals(scheme) || "https".equals(scheme)
                        || "about".equals(scheme) || "data".equals(scheme)
                        || "blob".equals(scheme) || "javascript".equals(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                    // nothing can open this link
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pointerLocked = false;
                if (!docStartScript) {
                    view.evaluateJavascript(vcScript, null);
                }
                if (!urlBar.hasFocus()) {
                    urlBar.setText(url);
                }
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // safe to run twice: the script ignores a second run
                view.evaluateJavascript(vcScript, null);
                progress.setVisibility(View.GONE);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                // index 1 = above the page, below the joysticks and buttons
                stage.addView(view, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.INVISIBLE);
                topBar.setVisibility(View.GONE);
                progress.setVisibility(View.GONE);
                setImmersive(true);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                // no app can handle the download
            }
        });
    }

    private void hideCustomView() {
        if (customView == null) {
            return;
        }
        View v = customView;
        WebChromeClient.CustomViewCallback cb = customCallback;
        customView = null;
        customCallback = null;
        stage.removeView(v);
        web.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.VISIBLE);
        setImmersive(false);
        if (cb != null) {
            cb.onCustomViewHidden();
        }
    }

    @SuppressWarnings("deprecation")
    private void setImmersive(boolean on) {
        int flags = 0;
        if (on) {
            flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private void js(String code) {
        web.evaluateJavascript(code, null);
    }

    // =====================================================================
    // Navigation
    // =====================================================================

    private static String normalize(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            return HOME;
        }
        if (t.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") || t.startsWith("about:") || t.startsWith("data:")) {
            return t;
        }
        boolean hasSpace = t.contains(" ");
        if (!hasSpace && (t.startsWith("localhost") || t.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$"))) {
            return "http://" + t;
        }
        if (!hasSpace && t.contains(".")) {
            return "https://" + t;
        }
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return HOME;
        }
    }

    private void load(String input) {
        web.loadUrl(normalize(input));
        urlBar.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
        web.requestFocus();
    }

    // =====================================================================
    // Controls: two sticks + buttons
    // =====================================================================

    private JoystickView addStick(boolean left, JoystickView.Listener l) {
        JoystickView j = new JoystickView(this);
        j.setListener(l);
        int size = dp(140);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                size, size, Gravity.BOTTOM | (left ? Gravity.LEFT : Gravity.RIGHT));
        if (left) {
            lp.setMargins(dp(20), 0, 0, dp(20));
        } else {
            lp.setMargins(0, 0, dp(20), dp(20));
        }
        stage.addView(j, lp);
        controlViews.add(j);
        sticks.add(j);
        return j;
    }

    private GradientDrawable circle(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(dp(2), stroke);
        return g;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addButton(String label, boolean left, int slot, int row, final PressAction action) {
        final TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);

        final GradientDrawable idle = circle(0x66222222, 0xAAFFFFFF);
        final GradientDrawable pressed = circle(0xCC3D7BFF, 0xFFFFFFFF);
        b.setBackground(idle);

        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    v.setBackground(pressed);
                    action.run(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setBackground(idle);
                    action.run(false);
                    return true;
                default:
                    return true;
            }
        });

        int size = dp(52);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                size, size, Gravity.BOTTOM | (left ? Gravity.LEFT : Gravity.RIGHT));
        int side = dp(16 + slot * 60);
        int bottom = dp(170 + row * 60);
        if (left) {
            lp.setMargins(side, 0, 0, bottom);
        } else {
            lp.setMargins(0, 0, side, bottom);
        }
        stage.addView(b, lp);
        controlViews.add(b);
    }

    private void buildControls() {
        // left stick -> W A S D
        addStick(true, (x, y) -> {
            setKey(KeyEvent.KEYCODE_W, y < -WASD_THRESHOLD);
            setKey(KeyEvent.KEYCODE_S, y > WASD_THRESHOLD);
            setKey(KeyEvent.KEYCODE_A, x < -WASD_THRESHOLD);
            setKey(KeyEvent.KEYCODE_D, x > WASD_THRESHOLD);
        });

        // right stick -> mouse cursor
        addStick(false, (x, y) -> {
            stickX = x;
            stickY = y;
            if (!mouseLoopRunning && (Math.abs(x) > STICK_DEAD_ZONE || Math.abs(y) > STICK_DEAD_ZONE)) {
                mouseLoopRunning = true;
                handler.post(mouseTick);
            }
        });

        // left side, above the left stick: Shift, Alt, Q
        addButton("Shift", true, 0, 0, down -> setModifier(KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON, down));
        addButton("Alt", true, 1, 0, down -> setModifier(KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON, down));
        addButton("Q", true, 2, 0, down -> setKey(KeyEvent.KEYCODE_Q, down));

        // right side, above the right stick: F, V, middle mouse button
        addButton("F", false, 0, 0, down -> setKey(KeyEvent.KEYCODE_F, down));
        addButton("V", false, 1, 0, down -> setKey(KeyEvent.KEYCODE_V, down));
        addButton("MMB", false, 2, 0, down -> setMouseButton(1, down));

        // a cursor needs to be able to click
        addButton("LMB", false, 0, 1, down -> setMouseButton(0, down));
        addButton("RMB", false, 1, 1, down -> setMouseButton(2, down));
    }

    private void toggleControls() {
        controlsVisible = !controlsVisible;
        if (!controlsVisible) {
            releaseAll();
        }
        for (View v : controlViews) {
            v.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
        }
        js("window.__vc&&__vc.show(" + controlsVisible + ")");
    }

    // =====================================================================
    // Keyboard injection (real KeyEvents, so they reach iframes too)
    // =====================================================================

    private void sendKey(int action, int code, int repeat) {
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(now, now, action, code, repeat, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        web.dispatchKeyEvent(ev);
    }

    private final Runnable keyRepeat = new Runnable() {
        @Override
        public void run() {
            if (heldKeys.isEmpty()) {
                repeating = false;
                return;
            }
            for (Map.Entry<Integer, Integer> e : new ArrayList<>(heldKeys.entrySet())) {
                int n = e.getValue() + 1;
                heldKeys.put(e.getKey(), n);
                sendKey(KeyEvent.ACTION_DOWN, e.getKey(), n);
            }
            handler.postDelayed(this, 40);
        }
    };

    private void setKey(int code, boolean down) {
        if (down) {
            if (heldKeys.containsKey(code)) {
                return;
            }
            web.requestFocus();
            heldKeys.put(code, 0);
            sendKey(KeyEvent.ACTION_DOWN, code, 0);
            if (!repeating) {
                repeating = true;
                handler.postDelayed(keyRepeat, 400);
            }
        } else {
            if (!heldKeys.containsKey(code)) {
                return;
            }
            heldKeys.remove(code);
            sendKey(KeyEvent.ACTION_UP, code, 0);
        }
    }

    private void setModifier(int code, int flags, boolean down) {
        boolean held = (metaState & flags) == flags;
        if (down) {
            if (held) {
                return;
            }
            web.requestFocus();
            metaState |= flags;
            sendKey(KeyEvent.ACTION_DOWN, code, 0);
        } else {
            if (!held) {
                return;
            }
            metaState &= ~flags;
            sendKey(KeyEvent.ACTION_UP, code, 0);
        }
    }

    // =====================================================================
    // Mouse injection (virtual cursor driven by the right stick)
    // =====================================================================

    private static float curve(float v) {
        float a = Math.abs(v);
        if (a < STICK_DEAD_ZONE) {
            return 0f;
        }
        a = (a - STICK_DEAD_ZONE) / (1f - STICK_DEAD_ZONE);
        return Math.signum(v) * a * a * MOUSE_MAX_SPEED;
    }

    private final Runnable mouseTick = new Runnable() {
        @Override
        public void run() {
            float vx = curve(stickX);
            float vy = curve(stickY);
            if (vx == 0f && vy == 0f) {
                mouseLoopRunning = false;
                return;
            }
            js(String.format(Locale.US, "window.__vc&&__vc.move(%.2f,%.2f)", vx, vy));
            handler.postDelayed(this, 16);
        }
    };

    private void setMouseButton(int button, boolean down) {
        if (mouseDown[button] == down) {
            return;
        }
        mouseDown[button] = down;
        js("window.__vc&&__vc.button(" + button + "," + down + ")");
    }

    // =====================================================================
    // Safety: never leave keys or buttons stuck
    // =====================================================================

    private void releaseAll() {
        for (Integer code : new ArrayList<>(heldKeys.keySet())) {
            sendKey(KeyEvent.ACTION_UP, code, 0);
        }
        heldKeys.clear();

        if ((metaState & KeyEvent.META_SHIFT_ON) != 0) {
            metaState &= ~(KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON);
            sendKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0);
        }
        if ((metaState & KeyEvent.META_ALT_ON) != 0) {
            metaState &= ~(KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON);
            sendKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0);
        }

        for (int b = 0; b < mouseDown.length; b++) {
            setMouseButton(b, false);
        }

        stickX = 0f;
        stickY = 0f;
        for (JoystickView j : sticks) {
            j.reset();
        }
    }
}
