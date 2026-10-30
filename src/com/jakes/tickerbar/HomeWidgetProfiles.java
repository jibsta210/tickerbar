package com.jakes.tickerbar;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Google Home widget instances associated with the Wi-Fi at each physical home. */
final class HomeWidgetProfiles {
    static final class Profile {
        final String network;
        final int widgetId;
        String label;
        Profile(String network, int widgetId, String label) {
            this.network = network;
            this.widgetId = widgetId;
            this.label = label;
        }
    }

    private HomeWidgetProfiles() {}

    static String currentNetwork(Context context) {
        try {
            WifiManager manager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = manager == null ? null : manager.getConnectionInfo();
            String name = info == null ? "" : info.getSSID();
            if (name == null || WifiManager.UNKNOWN_SSID.equals(name)) return rootNetwork();
            if (name.length() >= 2 && name.charAt(0) == '"'
                    && name.charAt(name.length() - 1) == '"')
                name = name.substring(1, name.length() - 1);
            name = name.trim();
            return name.isEmpty() ? rootNetwork() : name;
        } catch (Throwable ignored) { return rootNetwork(); }
    }

    /** Location services can redact WifiInfo even after the root-granted permission. */
    private static String rootNetwork() {
        if (!RootShell.available()) return "";
        RootShell.Result result = RootShell.restricted()
                ? RootShell.operation("wifi_status")
                : RootShell.exec("cmd wifi status 2>/dev/null");
        if (!result.connected || result.code != 0) return "";
        for (String line : result.output.split("\\n")) {
            String trimmed = line.trim();
            int marker = trimmed.indexOf("SSID:");
            if (marker > 0 && trimmed.charAt(marker - 1) == 'B') marker = -1;
            if (marker < 0) marker = trimmed.indexOf("SSID =");
            if (marker < 0) continue;
            String name = trimmed.substring(marker + 5).trim();
            if (name.startsWith("=")) name = name.substring(1).trim();
            int comma = name.indexOf(',');
            if (comma >= 0) name = name.substring(0, comma).trim();
            if (name.length() >= 2 && name.charAt(0) == '"'
                    && name.charAt(name.length() - 1) == '"')
                name = name.substring(1, name.length() - 1);
            if (!name.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(name)) return name;
        }
        return "";
    }

    static int selectedId(Context context) {
        String network = currentNetwork(context);
        int override = Prefs.n(context, Prefs.HOME_WIDGET_OVERRIDE_ID);
        String overrideNetwork = Prefs.str(context, Prefs.HOME_WIDGET_OVERRIDE_NETWORK);
        if (override > 0 && network.equals(overrideNetwork)) return override;
        for (Profile profile : profiles(context))
            if (!network.isEmpty() && network.equals(profile.network)) return profile.widgetId;
        return Prefs.n(context, Prefs.HOME_WIDGET_ID);
    }

    static void useUntilNetworkChanges(Context context, int id) {
        Prefs.put(context, Prefs.HOME_WIDGET_OVERRIDE_ID, id);
        Prefs.put(context, Prefs.HOME_WIDGET_OVERRIDE_NETWORK, currentNetwork(context));
    }

    static void map(Context context, String network, int widgetId) {
        network = network == null ? "" : network.trim();
        if (network.isEmpty() || widgetId <= 0) return;
        List<Profile> all = profiles(context);
        String label = "";
        if (widgetId == Prefs.n(context, Prefs.HOME_WIDGET_ID))
            label = Prefs.str(context, Prefs.HOME_WIDGET_DEFAULT_LABEL);
        for (Profile profile : all) if (profile.widgetId == widgetId) label = profile.label;
        // One physical home can legitimately have several SSIDs (mesh, 2.4 GHz, guest).
        // A network selects one widget, but a widget must not be restricted to one network.
        for (int i = all.size() - 1; i >= 0; i--)
            if (network.equals(all.get(i).network)) all.remove(i);
        all.add(new Profile(network, widgetId, label));
        save(context, all);
        Prefs.put(context, Prefs.HOME_WIDGET_OVERRIDE_ID, 0);
        Prefs.put(context, Prefs.HOME_WIDGET_OVERRIDE_NETWORK, "");
    }

    static void label(Context context, int widgetId, String label) {
        if (widgetId <= 0 || label == null || label.trim().isEmpty()) return;
        List<Profile> all = profiles(context);
        boolean changed = false;
        for (Profile profile : all) if (profile.widgetId == widgetId
                && !label.trim().equals(profile.label)) {
            profile.label = label.trim();
            changed = true;
        }
        if (changed) save(context, all);
        if (widgetId == Prefs.n(context, Prefs.HOME_WIDGET_ID)
                && !label.trim().equals(Prefs.str(context, Prefs.HOME_WIDGET_DEFAULT_LABEL)))
            Prefs.put(context, Prefs.HOME_WIDGET_DEFAULT_LABEL, label.trim());
    }

    static List<Profile> profiles(Context context) {
        ArrayList<Profile> out = new ArrayList<Profile>();
        try {
            JSONArray array = new JSONArray(Prefs.str(context, Prefs.HOME_WIDGET_PROFILES));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String network = item.optString("network", "");
                int id = item.optInt("id", 0);
                if (!network.isEmpty() && id > 0)
                    out.add(new Profile(network, id, item.optString("label", "")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    static List<Profile> choices(Context context) {
        LinkedHashMap<Integer, Profile> unique = new LinkedHashMap<Integer, Profile>();
        int base = Prefs.n(context, Prefs.HOME_WIDGET_ID);
        if (base > 0) unique.put(base, new Profile("", base,
                Prefs.str(context, Prefs.HOME_WIDGET_DEFAULT_LABEL)));
        for (Profile profile : profiles(context)) unique.put(profile.widgetId, profile);
        return new ArrayList<Profile>(unique.values());
    }

    static String displayName(Context context, int widgetId) {
        if (widgetId <= 0) return "Home";
        if (widgetId == Prefs.n(context, Prefs.HOME_WIDGET_ID)) {
            String label = Prefs.str(context, Prefs.HOME_WIDGET_DEFAULT_LABEL).trim();
            if (!label.isEmpty()) return label;
        }
        for (Profile profile : profiles(context)) if (profile.widgetId == widgetId) {
            if (profile.label != null && !profile.label.trim().isEmpty()) return profile.label.trim();
            if (profile.network != null && !profile.network.trim().isEmpty()) return profile.network.trim();
        }
        return "Home";
    }

    private static void save(Context context, List<Profile> profiles) {
        JSONArray array = new JSONArray();
        try {
            for (Profile profile : profiles) {
                JSONObject item = new JSONObject();
                item.put("network", profile.network);
                item.put("id", profile.widgetId);
                item.put("label", profile.label == null ? "" : profile.label);
                array.put(item);
            }
        } catch (Exception ignored) {}
        Prefs.put(context, Prefs.HOME_WIDGET_PROFILES, array.toString());
    }
}
