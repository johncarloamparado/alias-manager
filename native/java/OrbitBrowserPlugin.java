package __PACKAGE__;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "OrbitBrowser")
public class OrbitBrowserPlugin extends Plugin {
    /** Returns and clears the queued "first chat" events written by OrbitBrowserActivity. */
    @PluginMethod
    public void consumeEvents(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            SharedPreferences p = getContext().getSharedPreferences("orbit_profiles", Context.MODE_PRIVATE);
            String raw;
            synchronized (OrbitBrowserActivity.LOCK) {
                raw = p.getString("events", "[]");
                p.edit().remove("events").commit();
            }
            ret.put("events", new JSArray(raw));
        } catch (Exception e) {
            ret.put("events", new JSArray());
        }
        call.resolve(ret);
    }

    /** Replace all "cooldown finished" notifications with the given list: reminders:[{id,label,at(ms)}]. */
    @PluginMethod
    public void scheduleReminders(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            JSArray list = call.getArray("reminders");
            int n = OrbitReminderReceiver.sync(getContext(), list == null ? new JSArray() : list);
            boolean allowed = true;
            if (Build.VERSION.SDK_INT >= 33 && n > 0
                    && getContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                allowed = false;
                getActivity().requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7731);
            }
            ret.put("scheduled", n);
            ret.put("notificationsAllowed", allowed);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Unable to schedule reminders: " + e.getMessage());
        }
    }

    /** Keeps a copy of the alias list for Orbit, so Orbit never depends only on the Intent it was opened with. */
    @PluginMethod
    public void syncAliases(PluginCall call) {
        try {
            JSArray list = call.getArray("aliases");
            getContext().getSharedPreferences("orbit_profiles", Context.MODE_PRIVATE)
                .edit().putString("aliases_json", list == null ? "[]" : list.toString()).commit();
            call.resolve();
        } catch (Exception e) {
            call.reject("Unable to sync aliases: " + e.getMessage());
        }
    }

    @PluginMethod
    public void open(PluginCall call) {
        try {
            Intent intent = new Intent(getContext(), OrbitBrowserActivity.class);
            JSArray aliases = call.getArray("aliases");
            if (aliases != null) {
                getContext().getSharedPreferences("orbit_profiles", Context.MODE_PRIVATE)
                    .edit().putString("aliases_json", aliases.toString()).commit();
            }
            intent.putExtra("aliases", aliases == null ? "[]" : aliases.toString());
            intent.putExtra("autoStart", call.getBoolean("autoStart", true));
            String aliasId = call.getString("aliasId");
            if (aliasId != null && !aliasId.isEmpty()) intent.putExtra("aliasId", aliasId); // open the profile linked to this alias
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Unable to open Orbit Browser: " + e.getMessage());
        }
    }
}
