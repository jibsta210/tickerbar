package com.jakes.tickerbar;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
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

    private static final int BG = 0xFF101014, CARD = 0xFF1B1B21, FG = 0xFFE9E9ED,
            MUTED = 0xFF9A9AA6, OK = 0xFF4CD07A, BAD = 0xFFFF6B6B, WARN = 0xFFFFC857;

    private LinearLayout col;
    private TextView stAcc, stNotif;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Prefs.migrate(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setClipToPadding(false);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);
        setContentView(sv);

        // Android 15+ draws apps edge-to-edge behind the system bars. Opt in on every
        // version so behaviour is identical, then pad the content clear of the bars.
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        final int p = dp(18);
        col.setPadding(p, p, p, dp(48));
        sv.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int l, t, r, b;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    l = bars.left; t = bars.top; r = bars.right; b = bars.bottom;
                } else {
                    l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                    r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
                }
                v.setPadding(l, t, r, b);
                return in;
            }
        });

        header("TickerBar");
        note("v" + versionName()).setGravity(Gravity.CENTER_HORIZONTAL);

        // ---------------------------------------------------------- permissions
        section("Permissions");
        stAcc = label("");
        stNotif = label("");
        btn("Accessibility", new Runnable() { public void run() {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }});
        btn("Notification access", new Runnable() { public void run() {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }});
        btn("App info  →  ⋮  →  Allow restricted settings", new Runnable() { public void run() {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))); }});
        note("Accessibility is what lets the ticker sit on top of the status bar, and powers "
                + "the wallet button and shade shortcuts. On MIUI, allow restricted settings first.");

        // ---------------------------------------------------------- ticker
        section("Ticker");
        check("Ticker enabled", Prefs.TICKER_ON);
        cycle("Lines", Prefs.LINES, new int[]{1, 2, 3},
                new String[]{"1  (title — text)", "2  (title / text)", "3  (app / title / text)"});
        num("Bar height px  (0 = auto)", Prefs.HEIGHT);
        check("Hop around the camera cut-out", Prefs.HOP);
        num("Gap either side of the cut-out px", Prefs.HOP_GAP);
        check("Auto side padding (follow the rounded corners)", Prefs.AUTO_PAD);
        num("Manual side padding px  (used when auto is off)", Prefs.PAD);
        num("Text size sp", Prefs.TEXT);
        num("Background opacity %", Prefs.OPACITY);
        num("Minimum show time ms", Prefs.DWELL);
        check("Queue notifications  (off = newest replaces current)", Prefs.QUEUE);
        check("Skip silent notifications", Prefs.SKIP_SILENT);
        check("Skip ongoing  (media, downloads, running services)", Prefs.SKIP_ONGOING);
        text("Ignored apps  (package names, comma separated)", Prefs.IGNORE);

        // ---------------------------------------------------------- animation
        section("Animation");
        cycle("Entry", Prefs.ANIM_IN, new int[]{0, 1, 2, 3},
                new String[]{"None", "Fade", "Slide down", "Slide in from right"});
        cycle("Exit", Prefs.ANIM_OUT, new int[]{0, 1, 2, 3},
                new String[]{"None", "Fade", "Slide up", "Slide out left"});
        num("Animation duration ms", Prefs.ANIM_MS);
        cycle("Scrolling", Prefs.SCROLL_MODE,
                new int[]{TickerView.MODE_REVEAL, TickerView.MODE_LOOP, TickerView.MODE_OFF},
                new String[]{"Reveal once, then hold", "Continuous loop", "Off (truncate)"});
        num("Scroll speed px/sec", Prefs.SPEED);
        num("Pause before scrolling ms", Prefs.DELAY);
        btn("PREVIEW TICKER", new Runnable() { public void run() {
            if (!ShadeService.preview()) toast("Enable TickerBar in Accessibility first");
        }});

        // ---------------------------------------------------------- wallet button
        section("Wallet button");
        check("Enable nav-bar button", Prefs.SWIPE_ON);
        cycle("Side", Prefs.SWIPE_POS, new int[]{0, 1, 2}, new String[]{"Left", "Centre", "Right"});
        cycle("Trigger", Prefs.SWIPE_TRIGGER, new int[]{0, 1, 2},
                new String[]{"Tap or swipe up", "Tap only", "Swipe up only"});
        check("Only on the home screen", Prefs.SWIPE_HOME);
        check("Show card icon", Prefs.SWIPE_PILL);
        text("App to open  (package name)", Prefs.SWIPE_PKG);
        num("Width % of screen", Prefs.SWIPE_W);
        num("Height px  (0 = match the nav bar)", Prefs.SWIPE_H);
        num("Lift from bottom edge px", Prefs.SWIPE_Y);
        num("Swipe distance to trigger px", Prefs.SWIPE_DIST);
        note("It sits on top of the nav bar in a corner, so it won't fight the home gesture. "
                + "A tap is the most reliable trigger; a swipe fires early, before the system "
                + "can claim it as a home gesture.");

        // ---------------------------------------------------------- shade
        section("Shade shortcuts (for Nova gestures)");
        note("In Nova → Gestures, pick Activities → TickerBar:\n"
                + "  • Open Notifications\n  • Open Control Centre");
        check("Only work from the home screen", Prefs.SHADE_HOME);
        btn("Test: open Notifications", new Runnable() { public void run() {
            if (!ShadeService.notifications()) toast("Enable TickerBar in Accessibility first"); }});
        btn("Test: open Control Centre", new Runnable() { public void run() {
            if (!ShadeService.quickSettings()) toast("Enable TickerBar in Accessibility first"); }});

        // ---------------------------------------------------------- updates
        section("Updates");
        final TextView upd = note("");
        btn("Check for updates", new Runnable() { public void run() {
            Updater.checkAndInstall(MainActivity.this, new Updater.Cb() {
                public void msg(String m) { upd.setText(m); }
            });
        }});
    }

    @Override protected void onResume() {
        super.onResume();
        String l = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean nl = l != null && l.contains(getPackageName() + "/");
        String a = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean ac = a != null && a.contains(getPackageName() + "/");

        if (ac && ShadeService.isReady()) status(stAcc, "✓ Accessibility running", OK);
        else if (ac) status(stAcc, "! Accessibility granted but not running – toggle it off and on", WARN);
        else status(stAcc, "✗ Accessibility MISSING", BAD);
        status(stNotif, nl ? "✓ Notification access granted" : "✗ Notification access MISSING",
                nl ? OK : BAD);
        ShadeService.refresh();
    }

    // ============================================================ widgets

    private void status(TextView tv, String s, int color) { tv.setText(s); tv.setTextColor(color); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void header(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(26);
        tv.setTextColor(FG);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(tv);
    }

    private void section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s.toUpperCase());
        tv.setTextSize(12);
        tv.setTextColor(MUTED);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(0, dp(26), 0, dp(6));
        col.addView(tv);
    }

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(FG);
        tv.setPadding(0, dp(2), 0, dp(2));
        col.addView(tv);
        return tv;
    }

    private TextView note(String s) {
        TextView tv = label(s);
        tv.setTextColor(MUTED);
        tv.setTextSize(12);
        tv.setPadding(0, dp(6), 0, dp(4));
        return tv;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(FG);
        b.setBackgroundColor(CARD);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        col.addView(b);
        return b;
    }

    private void btn(String s, final Runnable r) {
        button(s).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
    }

    /** A button that steps through a fixed set of choices. */
    private void cycle(final String lbl, final String key, final int[] values, final String[] names) {
        final Button b = button("");
        final Runnable render = new Runnable() { public void run() {
            int cur = Prefs.n(MainActivity.this, key);
            String name = String.valueOf(cur);
            for (int i = 0; i < values.length; i++) if (values[i] == cur) name = names[i];
            b.setText(lbl + ":   " + name + "     ▸");
        }};
        render.run();
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            int cur = Prefs.n(MainActivity.this, key);
            int idx = -1;
            for (int i = 0; i < values.length; i++) if (values[i] == cur) idx = i;
            Prefs.put(MainActivity.this, key, values[(idx + 1) % values.length]);
            render.run();
            ShadeService.refresh();
        }});
    }

    private void check(String lbl, final String key) {
        CheckBox cb = new CheckBox(this);
        cb.setText(lbl);
        cb.setTextColor(FG);
        cb.setChecked(Prefs.on(this, key));
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.put(MainActivity.this, key, on ? 1 : 0);
                ShadeService.refresh();
            }
        });
        col.addView(cb);
    }

    private EditText field(String lbl, String initial, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(lbl);
        tv.setTextColor(MUTED);
        tv.setPadding(0, dp(12), 0, dp(2));
        col.addView(tv);
        EditText e = new EditText(this);
        e.setText(initial);
        e.setTextColor(FG);
        e.setBackgroundColor(CARD);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        col.addView(e);
        return e;
    }

    private void num(String lbl, final String key) {
        EditText e = field(lbl, String.valueOf(Prefs.n(this, key)),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.addTextChangedListener(new Watcher() {
            public void afterTextChanged(Editable s) {
                try {
                    Prefs.put(MainActivity.this, key, Integer.parseInt(s.toString().trim()));
                    ShadeService.refresh();
                } catch (NumberFormatException ignored) {
                    // mid-edit (empty or a lone '-'); keep the last good value
                }
            }
        });
    }

    private void text(String lbl, final String key) {
        EditText e = field(lbl, Prefs.str(this, key), InputType.TYPE_CLASS_TEXT);
        e.addTextChangedListener(new Watcher() {
            public void afterTextChanged(Editable s) {
                Prefs.put(MainActivity.this, key, s.toString().trim());
                ShadeService.refresh();
            }
        });
    }

    private abstract static class Watcher implements TextWatcher {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }
}
