package com.jakes.tickerbar;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int BG     = 0xFF101014;
    private static final int CARD   = 0xFF1B1B21;
    private static final int FG     = 0xFFE9E9ED;
    private static final int MUTED  = 0xFF9A9AA6;
    private static final int OK     = 0xFF4CD07A;
    private static final int BAD    = 0xFFFF6B6B;

    private LinearLayout col;
    private TextView stOverlay, stNotif, stAcc;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18); col.setPadding(p, p, p, p);
        sv.addView(col); setContentView(sv);

        header("TickerBar");

        section("Permissions");
        stOverlay = label(""); stNotif = label(""); stAcc = label("");
        btn("Draw over other apps", new Runnable() { public void run() {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))); }});
        btn("Notification access", new Runnable() { public void run() {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }});
        btn("Accessibility (for shade shortcuts)", new Runnable() { public void run() {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }});
        btn("App info – ⋮ → Allow restricted settings", new Runnable() { public void run() {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))); }});

        section("Ticker");
        num("Side padding (px)", Prefs.PAD,    Prefs.pad(this));
        num("Bar height (px)",   Prefs.HEIGHT, Prefs.height(this));
        num("Lines (1 or 2)",    Prefs.LINES,  Prefs.lines(this));
        num("Text size (sp)",    Prefs.TEXT,   Prefs.textSp(this));
        num("Show for (ms)",     Prefs.DWELL,  Prefs.dwell(this));
        btn("PREVIEW TICKER", new Runnable() { public void run() {
            if (!Settings.canDrawOverlays(MainActivity.this)) {
                Toast.makeText(MainActivity.this, "Grant overlay permission first",
                        Toast.LENGTH_LONG).show(); return;
            }
            BarService.show(MainActivity.this, "TickerBar preview",
                    "Tune padding and height until this clears the camera and corners");
        }});

        section("Bottom swipe → app");
        check("Enable bottom-edge swipe", Prefs.swipeOn(this));
        num("Strip height (px)", Prefs.SWIPE_H, Prefs.swipeH(this));
        text("Package to launch", Prefs.SWIPE_PKG, Prefs.swipePkg(this));

        section("Launcher shortcuts");
        TextView t = label("Bind these to Nova gestures:\n"
                + "  • TickerBar – Open Notifications\n"
                + "  • TickerBar – Open Control Centre\n"
                + "Both appear in Nova's activity list. They need Accessibility enabled.");
        t.setTextColor(MUTED);
        btn("Test: open Notifications", new Runnable() { public void run() {
            if (!ShadeService.notifications())
                Toast.makeText(MainActivity.this, "Enable Accessibility first", Toast.LENGTH_LONG).show();
        }});
        btn("Test: open Control Centre", new Runnable() { public void run() {
            if (!ShadeService.quickSettings())
                Toast.makeText(MainActivity.this, "Enable Accessibility first", Toast.LENGTH_LONG).show();
        }});

        section("Updates");
        final TextView upd = label("");
        upd.setTextColor(MUTED);
        btn("Check for updates", new Runnable() { public void run() {
            Updater.checkAndInstall(MainActivity.this, new Updater.Cb() {
                public void msg(String m) { upd.setText(m); }
            });
        }});
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ov = Settings.canDrawOverlays(this);
        String l = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean nl = l != null && l.contains(getPackageName());
        String a = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean ac = a != null && a.contains(getPackageName());

        mark(stOverlay, "Overlay", ov);
        mark(stNotif,   "Notification access", nl);
        mark(stAcc,     "Accessibility", ac);
        if (ov) BarService.refresh(this);
    }

    private void mark(TextView tv, String name, boolean good) {
        tv.setText((good ? "✓ " : "✗ ") + name + (good ? " granted" : " MISSING"));
        tv.setTextColor(good ? OK : BAD);
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private void header(String s) {
        TextView tv = new TextView(this);
        tv.setText(s); tv.setTextSize(26); tv.setTextColor(FG);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(0, 0, 0, dp(6));
        col.addView(tv);
    }

    private void section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s.toUpperCase()); tv.setTextSize(12); tv.setTextColor(MUTED);
        tv.setPadding(0, dp(22), 0, dp(6));
        col.addView(tv);
    }

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s); tv.setTextColor(FG); tv.setPadding(0, dp(2), 0, dp(2));
        col.addView(tv); return tv;
    }

    private void btn(String s, final Runnable r) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false);
        b.setTextColor(FG); b.setBackgroundColor(CARD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6); b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }});
        col.addView(b);
    }

    private void check(String lbl, boolean cur) {
        CheckBox cb = new CheckBox(this);
        cb.setText(lbl); cb.setTextColor(FG); cb.setChecked(cur);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.get(MainActivity.this).edit().putInt(Prefs.SWIPE_ON, on ? 1 : 0).apply();
                if (Settings.canDrawOverlays(MainActivity.this)) BarService.refresh(MainActivity.this);
            }});
        col.addView(cb);
    }

    private EditText field(String lbl, String initial, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(lbl); tv.setTextColor(MUTED); tv.setPadding(0, dp(10), 0, 0);
        col.addView(tv);
        EditText e = new EditText(this);
        e.setText(initial); e.setTextColor(FG); e.setBackgroundColor(CARD);
        e.setInputType(inputType); e.setPadding(dp(10), dp(8), dp(10), dp(8));
        col.addView(e); return e;
    }

    private void num(String lbl, final String key, int cur) {
        final EditText e = field(lbl, String.valueOf(cur), InputType.TYPE_CLASS_NUMBER);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                try {
                    int v = Integer.parseInt(s.toString().trim());
                    SharedPreferences.Editor ed = Prefs.get(MainActivity.this).edit();
                    ed.putInt(key, v); ed.apply();
                    if (Settings.canDrawOverlays(MainActivity.this))
                        BarService.refresh(MainActivity.this);
                } catch (NumberFormatException ignored) {}
            }});
    }

    private void text(String lbl, final String key, String cur) {
        final EditText e = field(lbl, cur, InputType.TYPE_CLASS_TEXT);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                Prefs.get(MainActivity.this).edit()
                        .putString(key, s.toString().trim()).apply();
            }});
    }
}
