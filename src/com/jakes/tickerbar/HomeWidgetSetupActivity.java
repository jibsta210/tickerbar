package com.jakes.tickerbar;

import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Foreground trampoline for Google Home's Favorites-widget configuration.
 *
 * Widget configuration is an activity-for-result contract. Launching it directly from the
 * shade service can be ignored by Home (or immediately finish) because there is no activity
 * result owner. Keeping this tiny activity in front for the duration makes the gear and Home
 * header behave exactly like configuring the widget from a launcher.
 */
public final class HomeWidgetSetupActivity extends Activity {
    private static final int CONFIGURE = 0x484f;
    static final String EXTRA_SWITCHER = "switcher";
    private static final int HOST_ID = 0x5442;
    private static final ComponentName PROVIDER = new ComponentName(
            "com.google.android.apps.chromecast.app",
            "com.google.android.apps.chromecast.app.appwidget.favorites.FavoritesWidgetReceiver");
    private int configuringId;
    private String mappingNetwork;
    private Dialog sheet;

    private interface Choice { void selected(int which); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            configuringId = state.getInt("configuring_id", 0);
            mappingNetwork = state.getString("mapping_network");
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_SWITCHER, false)) showSwitcher();
        else showManager();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("configuring_id", configuringId);
        out.putString("mapping_network", mappingNetwork);
        super.onSaveInstanceState(out);
    }

    private void showManager() {
        final int current = HomeWidgetProfiles.selectedId(this);
        final String network = HomeWidgetProfiles.currentNetwork(this);
        String remember = network.isEmpty() ? "Remember current home on Wi-Fi (connect first)"
                : "Use current home automatically on “" + network + "”";
        String add = network.isEmpty() ? "Set up another Wi-Fi home (connect first)"
                : "Set up another home for “" + network + "”";
        showSheet("Google Home", new String[]{"Edit controls", remember, add,
                        "Switch home / assign this Wi-Fi"},
                new Choice() { public void selected(int which) {
                    if (which == 0) configure(current, null);
                    else if (which == 1) rememberCurrent(network, current);
                    else if (which == 2) addForNetwork(network);
                    else showSwitcher();
                }});
    }

    private void rememberCurrent(String network, int current) {
        if (network == null || network.isEmpty()) {
            failed("Connect to this home's Wi-Fi, then try again");
            return;
        }
        if (current <= 0) {
            failed("Open the shade once to create the Home shelf");
            return;
        }
        HomeWidgetProfiles.map(this, network, current);
        ShadeService.homeProfileChanged();
        Toast.makeText(this, "This home will appear automatically on “" + network + "”",
                Toast.LENGTH_LONG).show();
        finish();
    }

    private void showSwitcher() {
        final List<HomeWidgetProfiles.Profile> choices = HomeWidgetProfiles.choices(this);
        if (choices.size() < 2) {
            Toast.makeText(this, "Set up another Home shelf first, then it can switch by Wi-Fi",
                    Toast.LENGTH_LONG).show();
            showManager();
            return;
        }
        String[] names = new String[choices.size()];
        for (int i = 0; i < names.length; i++) {
            HomeWidgetProfiles.Profile profile = choices.get(i);
            names[i] = profile.label == null || profile.label.isEmpty()
                    ? (profile.network.isEmpty() ? "Home" : profile.network) : profile.label;
            if (!profile.network.isEmpty()) names[i] += "  ·  " + profile.network;
        }
        final String detectedNetwork = HomeWidgetProfiles.currentNetwork(this);
        showSheet(detectedNetwork.isEmpty() ? "Show controls for"
                        : "Use automatically on “" + detectedNetwork + "”",
                names, new Choice() { public void selected(int which) {
                    HomeWidgetProfiles.Profile chosen = choices.get(which);
                    String network = HomeWidgetProfiles.currentNetwork(HomeWidgetSetupActivity.this);
                    if (network.isEmpty())
                        HomeWidgetProfiles.useUntilNetworkChanges(HomeWidgetSetupActivity.this,
                                chosen.widgetId);
                    else
                        // Choosing a home while connected there is the least surprising
                        // automation: switch immediately and remember this SSID in one tap.
                        HomeWidgetProfiles.map(HomeWidgetSetupActivity.this, network,
                                chosen.widgetId);
                    ShadeService.homeProfileChanged();
                    String label = chosen.label == null || chosen.label.trim().isEmpty()
                            ? "Home" : chosen.label.trim();
                    Toast.makeText(HomeWidgetSetupActivity.this,
                            network.isEmpty() ? "Showing “" + label + "”"
                                    : "Showing “" + label + "” automatically on “"
                                            + network + "”",
                            Toast.LENGTH_LONG).show();
                    finish();
                }});
    }

    private void addForNetwork(String network) {
        if (network == null || network.isEmpty()) {
            Toast.makeText(this, "Connect to this home's Wi-Fi, then try again",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        AppWidgetHost host = new AppWidgetHost(this, HOST_ID);
        int id;
        try { id = host.allocateAppWidgetId(); }
        catch (Exception failure) { failed("Couldn't create another Home shelf"); return; }
        boolean bound;
        try { bound = AppWidgetManager.getInstance(this)
                .bindAppWidgetIdIfAllowed(id, PROVIDER); }
        catch (Exception failure) { bound = false; }
        if (!bound) {
            try { host.deleteAppWidgetId(id); } catch (Exception ignored) {}
            failed("TickerBar needs widget binding permission; apply root integration once");
            return;
        }
        configure(id, network);
    }

    private void configure(int id, String network) {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        AppWidgetProviderInfo info = id > 0 ? manager.getAppWidgetInfo(id) : null;
        if (info == null) {
            failed("Open the shade once to create the Home shelf");
            return;
        }

        Intent setup = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        if (info.configure != null) setup.setComponent(info.configure);
        else setup.setPackage(info.provider.getPackageName());
        try {
            configuringId = id;
            mappingNetwork = network;
            startActivityForResult(setup, CONFIGURE);
        } catch (Exception unavailable) {
            failed("Google Home does not expose Favorites setup in this version");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CONFIGURE && configuringId > 0 && mappingNetwork != null) {
            if (result == RESULT_OK
                    && AppWidgetManager.getInstance(this).getAppWidgetInfo(configuringId) != null) {
                HomeWidgetProfiles.map(this, mappingNetwork, configuringId);
                ShadeService.homeProfileChanged();
            } else try { new AppWidgetHost(this, HOST_ID).deleteAppWidgetId(configuringId); }
            catch (Exception ignored) {}
        }
        finish();
    }

    private void failed(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    /** A small Material-style sheet; platform AlertDialog looked like an Android 2.x relic. */
    private void showSheet(String title, final String[] options, final Choice choice) {
        if (sheet != null) sheet.dismiss();
        Palette themed = Palette.of(this, Prefs.n(this, Prefs.SHADE_THEME));
        int accent = Prefs.n(this, Prefs.SHADE_ACCENT);
        if (accent > 0 && accent < UnifiedShade.ACCENTS.length)
            themed = themed.withAccent(UnifiedShade.ACCENTS[accent]);
        final Palette palette = themed.withShadeColors(Prefs.n(this, Prefs.SHADE_BG_COLOR),
                        Prefs.n(this, Prefs.SHADE_PANEL_COLOR),
                        Prefs.n(this, Prefs.SHADE_INACTIVE_COLOR),
                        Prefs.n(this, Prefs.SHADE_ACTIVE_COLOR),
                        Prefs.n(this, Prefs.SHADE_TEXT_COLOR),
                        Prefs.n(this, Prefs.SHADE_SECONDARY_COLOR));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackground(round(Palette.alpha(palette.surface, 0xFA), dp(28)));

        TextView heading = text(title, 20, palette.onSurface);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(10), dp(4), dp(10), dp(12));
        panel.addView(heading);

        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView row = text(options[i], 15, palette.onSurface);
            row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackground(ripple(round(palette.surfaceHigh, dp(18)), palette.onSurface));
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                if (sheet != null) sheet.dismiss();
                choice.selected(index);
            }});
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
            if (i > 0) lp.topMargin = dp(8);
            panel.addView(row, lp);
        }

        sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheet.setContentView(panel);
        sheet.setCanceledOnTouchOutside(true);
        sheet.setOnCancelListener(dialog -> finish());
        sheet.show();
        Window window = sheet.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(400));
            attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attrs.gravity = Gravity.CENTER;
            attrs.dimAmount = 0.58f;
            window.setAttributes(attrs);
            window.getDecorView().setElevation(dp(24));
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private RippleDrawable ripple(Drawable content, int foreground) {
        GradientDrawable mask = round(Color.WHITE, dp(18));
        return new RippleDrawable(ColorStateList.valueOf(Palette.alpha(foreground, 0x22)),
                content, mask);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
