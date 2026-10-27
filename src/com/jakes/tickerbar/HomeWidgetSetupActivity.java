package com.jakes.tickerbar;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
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
        new AlertDialog.Builder(this)
                .setTitle("Google Home shelf")
                .setItems(new String[]{"Edit current home and controls", remember, add,
                                "Switch home now"},
                        (dialog, which) -> {
                            if (which == 0) configure(current, null);
                            else if (which == 1) rememberCurrent(network, current);
                            else if (which == 2) addForNetwork(network);
                            else showSwitcher();
                        })
                .setOnCancelListener(dialog -> finish())
                .show();
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
        Toast.makeText(this, "This home will appear automatically on “" + network + "”",
                Toast.LENGTH_LONG).show();
        finish();
    }

    private void showSwitcher() {
        final List<HomeWidgetProfiles.Profile> choices = HomeWidgetProfiles.choices(this);
        if (choices.size() < 2) {
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
        new AlertDialog.Builder(this).setTitle("Show controls for")
                .setItems(names, (dialog, which) -> {
                    HomeWidgetProfiles.useUntilNetworkChanges(this, choices.get(which).widgetId);
                    finish();
                })
                .setOnCancelListener(dialog -> finish())
                .show();
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
                    && AppWidgetManager.getInstance(this).getAppWidgetInfo(configuringId) != null)
                HomeWidgetProfiles.map(this, mappingNetwork, configuringId);
            else try { new AppWidgetHost(this, HOST_ID).deleteAppWidgetId(configuringId); }
            catch (Exception ignored) {}
        }
        finish();
    }

    private void failed(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }
}
