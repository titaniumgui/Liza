package app.liza.android;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * SD card reminder: per-device plan (date the card was emptied, check date,
 * estimated full date), how many days a full card lasts, and the 9:00
 * notification on the check date. Dates are stored as yyyy-MM-dd in the
 * phone's time zone. Nothing here is secret, so plain preferences are used.
 */
final class SdReminder {
    static final String PREFS = "liza_sd";
    private static final String CHANNEL = "sd_reminder";
    static final String EXTRA_SERIAL = "serial";

    private SdReminder() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String start(Context c, String sn) { return prefs(c).getString("start_" + sn, null); }
    static String check(Context c, String sn) { return prefs(c).getString("check_" + sn, null); }
    static String full(Context c, String sn) { return prefs(c).getString("full_" + sn, null); }
    static int life(Context c, String sn) { return prefs(c).getInt("life_" + sn, 0); }
    static void setLife(Context c, String sn, int days) {
        prefs(c).edit().putInt("life_" + sn, days >= 1 && days <= 3650 ? days : 0).apply();
    }

    static void save(Context c, String sn, String name, String start, String check, String full) {
        prefs(c).edit().putString("start_" + sn, start).putString("check_" + sn, check)
                .putString("full_" + sn, full).putString("name_" + sn, name)
                .remove("notified_" + sn).apply();
        schedule(c, sn);
    }

    static void clear(Context c, String sn) {
        cancel(c, sn);
        prefs(c).edit().remove("check_" + sn).remove("full_" + sn).remove("notified_" + sn).apply();
    }

    /** Marks the card as emptied today and drops the old reminder. */
    static void emptiedToday(Context c, String sn) {
        cancel(c, sn);
        prefs(c).edit().putString("start_" + sn, iso(today())).remove("check_" + sn)
                .remove("full_" + sn).remove("notified_" + sn).apply();
    }

    // ---- dates ----

    static Calendar today() {
        Calendar d = Calendar.getInstance();
        d.set(Calendar.HOUR_OF_DAY, 0); d.set(Calendar.MINUTE, 0); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
        return d;
    }

    static String iso(Calendar day) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day.getTime()); }

    static Calendar parse(String iso) {
        if (iso == null) return null;
        try {
            Calendar d = Calendar.getInstance();
            d.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso));
            d.set(Calendar.HOUR_OF_DAY, 0); d.set(Calendar.MINUTE, 0); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
            return d;
        } catch (ParseException e) { return null; }
    }

    static String label(String iso) {
        Calendar d = parse(iso);
        return d == null ? "—" : DateFormat.getDateInstance(DateFormat.MEDIUM).format(d.getTime());
    }

    static Calendar plusDays(Calendar day, int days) { Calendar d = (Calendar) day.clone(); d.add(Calendar.DAY_OF_MONTH, days); return d; }

    /** Whole calendar days from a to b (DST-safe). */
    static int daysBetween(Calendar a, Calendar b) {
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
    }

    static boolean due(Context c, String sn) {
        Calendar check = parse(check(c, sn));
        return check != null && !check.after(today());
    }

    // ---- notification ----

    private static PendingIntent alarmIntent(Context c, String sn) {
        Intent i = new Intent(c, AlarmReceiver.class).putExtra(EXTRA_SERIAL, sn);
        return PendingIntent.getBroadcast(c, sn.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c, String sn) {
        Calendar check = parse(check(c, sn));
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (check == null || alarms == null) return;
        if (check(c, sn).equals(prefs(c).getString("notified_" + sn, null))) return;
        check.set(Calendar.HOUR_OF_DAY, 9);
        long at = Math.max(check.getTimeInMillis(), System.currentTimeMillis() + 10000L);
        // Inexact is fine for a reminder and needs no exact-alarm permission.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c, sn));
    }

    static void cancel(Context c, String sn) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(alarmIntent(c, sn));
    }

    static void scheduleAll(Context c) {
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            if (e.getKey().startsWith("check_")) schedule(c, e.getKey().substring(6));
        }
    }

    static boolean canNotify(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void notifyDue(Context c, String sn) {
        String check = check(c, sn);
        // Without permission, keep the reminder pending: it is scheduled again
        // when permission is granted or Liza next starts.
        if (check == null || !canNotify(c)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        prefs(c).edit().putString("notified_" + sn, check).apply();
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "SD card reminders", NotificationManager.IMPORTANCE_DEFAULT));
        }
        String name = prefs(c).getString("name_" + sn, null);
        String text = "It was emptied on " + label(start(c, sn))
                + (full(c, sn) != null ? " and is estimated to fill around " + label(full(c, sn)) + "." : ".")
                + " Open Liza > Device settings to check it.";
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, sn.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_notify_sdcard)
                .setContentTitle("Check the SD card in " + (name == null || name.isEmpty() ? "your camera" : name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true);
        nm.notify(sn.hashCode(), b.build());
    }

    /** Fires on the check date. */
    public static final class AlarmReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            String sn = intent.getStringExtra(EXTRA_SERIAL);
            if (sn != null && due(c, sn)) notifyDue(c, sn);
        }
    }

    /** Alarms don't survive a reboot; schedule them again. */
    public static final class BootReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) scheduleAll(c);
        }
    }
}
