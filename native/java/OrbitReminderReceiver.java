package __PACKAGE__;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/**
 * Posts a "cooldown finished" notification. Alarms are scheduled with AlarmManager
 * (inexact: may fire a few minutes late in Doze). Each reminder is also saved (id, label, time) so that
 * after a phone reboot the alarms are re-created; reminders that ended while the phone was off are shown at boot.
 */
public class OrbitReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL = "orbit_ready";
    private static final String PREFS = "orbit_reminders";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) { restore(ctx); return; }
        String label = intent.getStringExtra("label");
        String id = intent.getStringExtra("id");
        notifyReady(ctx, id, label);
        if (id != null) forget(ctx, id);
    }

    static void notifyReady(Context ctx, String id, String label) {
        if (label == null || label.isEmpty()) label = "An account";
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Account ready", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent open = launch == null ? null
                : PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("✓ Ready: " + label)
                .setContentText("5h cooldown finished. You can use this account again.")
                .setAutoCancel(true);
        if (open != null) b.setContentIntent(open);
        nm.notify(id == null ? 1 : id.hashCode(), b.build());
    }

    /** After reboot: re-create alarms that are still in the future, notify for those that ended while off. */
    static void restore(Context ctx) {
        JSONObject data = data(ctx);
        java.util.Iterator<String> it = data.keys();
        java.util.List<String> expired = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject o = data.optJSONObject(id);
            if (o == null) continue;
            long at = o.optLong("at", 0);
            if (at > now) add(ctx, id, o.optString("label", ""), at);
            else { notifyReady(ctx, id, o.optString("label", "")); expired.add(id); }
        }
        for (String id : expired) forget(ctx, id);
    }

    private static JSONObject data(Context ctx) {
        try { return new JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static PendingIntent pending(Context ctx, String id, String label, int flags) {
        Intent i = new Intent(ctx, OrbitReminderReceiver.class);
        i.setData(Uri.parse("orbit://reminder/" + Uri.encode(id))); // makes the PendingIntent unique per alias
        i.putExtra("id", id);
        i.putExtra("label", label == null ? "" : label);
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Set<String> ids(Context ctx) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ids", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private static void saveIds(Context ctx, Set<String> ids) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ids", new JSONArray(ids).toString()).commit();
    }

    private static void forget(Context ctx, String id) {
        synchronized (OrbitBrowserActivity.LOCK) {
            Set<String> s = ids(ctx); s.remove(id); saveIds(ctx, s);
            JSONObject d = data(ctx); d.remove(id);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("data", d.toString()).commit();
        }
    }

    /** Schedule (or replace) one reminder at absolute time atMs. */
    static void add(Context ctx, String id, String label, long atMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null || atMs <= System.currentTimeMillis()) return;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(ctx, id, label, PendingIntent.FLAG_UPDATE_CURRENT));
        synchronized (OrbitBrowserActivity.LOCK) {
            Set<String> s = ids(ctx); s.add(id); saveIds(ctx, s);
            try {
                JSONObject d = data(ctx);
                d.put(id, new JSONObject().put("label", label == null ? "" : label).put("at", atMs));
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("data", d.toString()).commit();
            } catch (Exception ignored) { }
        }
    }

    static void cancel(Context ctx, String id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(ctx, id, "", PendingIntent.FLAG_UPDATE_CURRENT));
        forget(ctx, id);
    }

    /** Make the scheduled reminders exactly equal to the given list [{id,label,at}]. Returns how many are scheduled. */
    static int sync(Context ctx, JSONArray list) {
        Set<String> keep = new HashSet<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            long at = o.optLong("at", 0);
            if (id.isEmpty() || at <= now) continue;
            add(ctx, id, o.optString("label", ""), at);
            keep.add(id);
        }
        for (String old : ids(ctx)) if (!keep.contains(old)) cancel(ctx, old);
        return keep.size();
    }
}
