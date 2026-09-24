package com.jakes.tickerbar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Owns the overlay bar. Runs independently of notification access. */
public class BarService extends Service {

    public static final String ACTION_SHOW    = "com.jakes.tickerbar.SHOW";
    public static final String ACTION_REFRESH = "com.jakes.tickerbar.REFRESH";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY  = "body";
    private static final String CHAN = "tickerbar";

    private WindowManager wm;
    private View edge;
    private LinearLayout root;
    private TextView line1, line2;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hide = new Runnable() {
        public void run() { if (root != null) root.setVisibility(View.GONE); }
    };

    public static void show(Context c, String title, String body) {
        Intent i = new Intent(c, BarService.class)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body);
        c.startForegroundService(i);
    }
    public static void refresh(Context c) {
        c.startForegroundService(new Intent(c, BarService.class).setAction(ACTION_REFRESH));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHAN, "TickerBar", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, CHAN)
                .setContentTitle("TickerBar running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        build();
        buildEdge();
    }

    private void build() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setVisibility(View.GONE);
        line1 = mk(true); line2 = mk(false);
        root.addView(line1); root.addView(line2);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, Prefs.height(this),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try { wm.addView(root, lp); } catch (Exception e) {
            stopSelf(); return;
        }
        apply();
    }

    /** Thin touchable strip along the bottom edge: swipe up launches the chosen app. */
    private void buildEdge() {
        if (!Prefs.swipeOn(this)) return;
        edge = new View(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, Prefs.swipeH(this),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        edge.setOnTouchListener(new View.OnTouchListener() {
            float startY; long startT;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = e.getRawY(); startT = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dy = startY - e.getRawY();
                        long dt = System.currentTimeMillis() - startT;
                        if (dy > 120 && dt < 700) launchTarget();
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(edge, lp); } catch (Exception ignored) {}
    }

    private void launchTarget() {
        String pkg = Prefs.swipePkg(this);
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            android.widget.Toast.makeText(this, "Not installed: " + pkg,
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }

    private void rebuildEdge() {
        if (edge != null) { try { wm.removeView(edge); } catch (Exception ignored) {} edge = null; }
        buildEdge();
    }

    private TextView mk(boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        t.setMarqueeRepeatLimit(-1);
        t.setHorizontallyScrolling(true);
        t.setSelected(true);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    void apply() {
        if (root == null) return;
        int pad = Prefs.pad(this);
        root.setPadding(pad, 0, pad, 0);
        int sp = Prefs.textSp(this);
        line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        line2.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        line2.setVisibility(Prefs.lines(this) >= 2 ? View.VISIBLE : View.GONE);
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
            lp.height = Prefs.height(this);
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && ACTION_SHOW.equals(i.getAction())) {
            String t = i.getStringExtra(EXTRA_TITLE);
            String b = i.getStringExtra(EXTRA_BODY);
            apply();
            if (Prefs.lines(this) >= 2) { line1.setText(t); line2.setText(b); }
            else line1.setText(TextUtils.isEmpty(b) ? t : t + " — " + b);
            line1.setSelected(true); line2.setSelected(true);
            root.setVisibility(View.VISIBLE);
            ui.removeCallbacks(hide);
            ui.postDelayed(hide, Prefs.dwell(this));
        } else if (i != null && ACTION_REFRESH.equals(i.getAction())) {
            apply();
            rebuildEdge();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (root != null && wm != null) { try { wm.removeView(root); } catch (Exception ignored) {} }
        if (edge != null && wm != null) { try { wm.removeView(edge); } catch (Exception ignored) {} }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
