package app.liza.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.provider.CalendarContract;
import android.text.InputType;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.videogo.exception.BaseException;
import com.videogo.openapi.EZConstants;
import com.videogo.openapi.EZGlobalSDK;
import com.videogo.openapi.bean.EZDeviceInfo;
import com.videogo.openapi.bean.EZDeviceRecordFile;
import com.videogo.openapi.bean.EZStorageStatus;
import com.videogo.openapi.EZConstants.EZPTZDisplayCommand;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DeviceSettingsActivity extends Activity {
    private static final int BG = Color.rgb(12, 17, 25);
    private static final int PANEL = Color.rgb(23, 31, 43);
    private static final int MUTED = Color.rgb(158, 172, 191);
    private static final int ACCENT = Color.rgb(57, 197, 160);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String serial;
    private String deviceName;
    private TextView nameView;
    private TextView statusView;
    private TextView storageStatusView;
    private Button defenceButton;
    private Button storageButton;
    private Button qualityButton;
    private Button flipButton;
    private int cameraNo;
    // SD card reminder
    private Button sdStartButton;
    private Button sdCheckDateButton;
    private Button sdCheckCardButton;
    private Button sdFindButton;
    private EditText sdLifeInput;
    private TextView sdResultView;
    private String sdStart;
    private String sdCheck;
    private String sdFull;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        serial = getIntent().getStringExtra("serial");
        deviceName = getIntent().getStringExtra("name");
        cameraNo = getIntent().getIntExtra("cameraNo", 1);
        buildScreen();
        refreshDetails();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        applySystemInsets(scroll);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(24));
        page.setBackgroundColor(BG);
        scroll.addView(page);

        TextView brand = text("LIZA", 13, ACCENT, true);
        brand.setLetterSpacing(.16f);
        page.addView(brand);
        page.addView(text("Device settings", 28, Color.WHITE, true), top(8));
        page.addView(text(serial == null ? "" : serial, 13, MUTED, false), top(4));

        nameView = text(deviceName == null ? "Door viewer" : deviceName, 18, Color.WHITE, true);
        page.addView(nameView, top(24));
        Button rename = button("Rename device", PANEL, Color.WHITE);
        rename.setOnClickListener(v -> renameDevice());
        page.addView(rename, topHeight(50, 10));

        statusView = text("Loading device capabilities…", 14, MUTED, false);
        page.addView(statusView, top(22));
        defenceButton = button("Arm / disarm", ACCENT, BG);
        defenceButton.setOnClickListener(v -> toggleDefence());
        page.addView(defenceButton, topHeight(52, 12));

        TextView storageTitle = text("Storage", 18, Color.WHITE, true);
        page.addView(storageTitle, top(26));
        storageButton = button("Check storage status", PANEL, Color.WHITE);
        storageButton.setOnClickListener(v -> loadStorage());
        page.addView(storageButton, topHeight(50, 10));
        storageStatusView = text("Storage status has not been checked.", 13, MUTED, false);
        page.addView(storageStatusView, top(8));
        buildSdReminder(page);
        page.addView(text("Only settings exposed by the EZVIZ SDK are shown here. Liza does not format storage or upgrade firmware.", 12, MUTED, false), top(22));

        section(page, "Image");
        qualityButton = button("Video quality", PANEL, Color.WHITE);
        qualityButton.setOnClickListener(v -> chooseVideoQuality());
        page.addView(qualityButton, topHeight(50, 10));
        flipButton = button("Flip camera image 180°", PANEL, Color.WHITE);
        flipButton.setOnClickListener(v -> flipImage());
        page.addView(flipButton, topHeight(50, 10));
        page.addView(text("Video quality applies to the live stream. Image flip is sent to the camera and may briefly interrupt video.", 12, MUTED, false), top(8));

        section(page, "Battery, mode, audio, screen & lights");
        page.addView(text("The public EZVIZ SDK does not provide DP2C battery level or working-mode controls, or camera audio, screen and light settings. Liza cannot read or change these items through the supported SDK yet.", 13, MUTED, false), top(8));
        setContentView(scroll);
    }

    // ---------- SD card reminder ----------
    // EZVIZ reports the card's capacity but not its free space, and the space a
    // camera uses is not proportional to clip length (on a DP2C, clip length x
    // bitrate predicted ~950 days for a card that filled in ~90). So Liza plans
    // from how long a full card lasts: from the user's experience, or measured.
    // Once full, the camera overwrites its oldest recordings, so if the oldest
    // recording is newer than the day the card was emptied, the card is full
    // and (today - oldest recording) is how many days a full card holds.
    // Liza web (web/public/app.js) uses the same logic.
    private static final int FIRST_CHECK_DAYS = 30;

    private void buildSdReminder(LinearLayout page) {
        section(page, "SD card reminder");
        page.addView(text("EZVIZ reports the card's size but not its free space. When the card is full, the camera overwrites its oldest recordings. Liza checks for that, learns how many days a full card holds, and reminds you before the next one fills.", 12, MUTED, false), top(8));
        sdStart = SdReminder.start(this, serial);
        if (sdStart == null) sdStart = SdReminder.iso(SdReminder.today());
        sdCheck = SdReminder.check(this, serial);
        sdFull = SdReminder.full(this, serial);

        sdStartButton = button("", PANEL, Color.WHITE);
        sdStartButton.setOnClickListener(v -> pickSdDate(true));
        page.addView(sdStartButton, topHeight(50, 10));
        sdLifeInput = new EditText(this);
        sdLifeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        sdLifeInput.setHint("A full card lasts about … days (optional)");
        sdLifeInput.setTextColor(Color.WHITE);
        sdLifeInput.setHintTextColor(MUTED);
        sdLifeInput.setTextSize(14);
        int life = SdReminder.life(this, serial);
        if (life > 0) sdLifeInput.setText(String.valueOf(life));
        page.addView(sdLifeInput, top(8));

        sdCheckCardButton = button("Check the card", ACCENT, BG);
        sdCheckCardButton.setOnClickListener(v -> checkSdCard());
        page.addView(sdCheckCardButton, topHeight(50, 10));
        sdFindButton = button("Find oldest recording", PANEL, Color.WHITE);
        sdFindButton.setOnClickListener(v -> findSdStart());
        page.addView(sdFindButton, topHeight(50, 10));
        sdResultView = text(SdReminder.due(this, serial) ? "Your SD card reminder is due. Tap Check the card." : "", 13, MUTED, false);
        page.addView(sdResultView, top(10));

        sdCheckDateButton = button("", PANEL, Color.WHITE);
        sdCheckDateButton.setOnClickListener(v -> pickSdDate(false));
        page.addView(sdCheckDateButton, topHeight(50, 14));
        Button save = button("Save reminder", ACCENT, BG);
        save.setOnClickListener(v -> saveSdReminder());
        page.addView(save, topHeight(50, 10));
        Button calendar = button("Add to calendar", PANEL, Color.WHITE);
        calendar.setOnClickListener(v -> addSdToCalendar());
        page.addView(calendar, topHeight(50, 10));
        Button emptied = button("I emptied the card today", PANEL, Color.WHITE);
        emptied.setOnClickListener(v -> markSdEmptied());
        page.addView(emptied, topHeight(50, 10));
        Button remove = button("Remove reminder", PANEL, Color.WHITE);
        remove.setOnClickListener(v -> {
            SdReminder.clear(this, serial);
            sdCheck = null; sdFull = null;
            updateSdButtons();
            sdResultView.setText("Reminder removed.");
        });
        page.addView(remove, topHeight(50, 10));
        updateSdButtons();
    }

    private void updateSdButtons() {
        sdStartButton.setText("Card emptied on " + SdReminder.label(sdStart));
        sdCheckDateButton.setText(sdCheck == null ? "Remind me on: choose a date" : "Remind me on " + SdReminder.label(sdCheck));
    }

    private void pickSdDate(boolean start) {
        Calendar initial = SdReminder.parse(start ? sdStart : sdCheck);
        if (initial == null) initial = SdReminder.plusDays(SdReminder.today(), start ? 0 : FIRST_CHECK_DAYS);
        DatePickerDialog dialog = new DatePickerDialog(this, (picker, y, m, d) -> {
            Calendar day = SdReminder.today();
            day.set(y, m, d);
            String iso = SdReminder.iso(day);
            if (start) { sdStart = iso; sdFull = null; } else sdCheck = iso;
            updateSdButtons();
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        if (start) dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        else { Calendar min = SdReminder.parse(sdStart); if (min != null) dialog.getDatePicker().setMinDate(min.getTimeInMillis()); }
        dialog.show();
    }

    private int readSdLife() {
        try {
            int n = Integer.parseInt(sdLifeInput.getText().toString().trim());
            return n >= 1 && n <= 3650 ? n : 0;
        } catch (NumberFormatException e) { return 0; }
    }

    private void sdProgress(String message) { runOnUiThread(() -> sdResultView.setText(message)); }

    private void setSdBusy(boolean busy) {
        sdCheckCardButton.setEnabled(!busy);
        sdFindButton.setEnabled(!busy);
    }

    /** SD card capacity in GB, from EZVIZ's REST API (the SDK's storage status has no size). */
    private double readSdCapacityGb() throws Exception {
        LizaApplication app = (LizaApplication) getApplication();
        String token = app.vault().get("access_token");
        if (token == null || token.isEmpty()) throw new IllegalStateException("Sign in again to read the card size.");
        ServerRegion region = ServerRegion.fromLabel(app.vault().get("region"));
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build();
        Request request = new Request.Builder().url(region.apiUrl + "/api/v3/device/format/status")
                .header("accessToken", token).header("deviceSerial", serial).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            JsonObject meta = root.has("meta") ? root.getAsJsonObject("meta") : null;
            String code = meta != null && meta.has("code") ? meta.get("code").getAsString() : "";
            if (!"200".equals(code)) throw new IllegalStateException("EZVIZ error " + code + ".");
            JsonObject data = root.getAsJsonObject("data");
            JsonArray list = data == null ? null : data.getAsJsonArray("storageStatus");
            double mb = 0;
            if (list != null) for (JsonElement item : list) {
                JsonObject o = item.getAsJsonObject();
                if (o.has("capacity")) mb += o.get("capacity").getAsDouble();
            }
            return mb / 1024.0;
        }
    }

    /** Clips on the card for one local calendar day (null if the camera didn't answer). */
    private List<EZDeviceRecordFile> sdDay(Calendar day) {
        Calendar from = (Calendar) day.clone();
        Calendar to = SdReminder.plusDays(day, 1);
        to.add(Calendar.MILLISECOND, -1);
        Calendar now = Calendar.getInstance();
        if (to.after(now)) to = now;
        try {
            List<EZDeviceRecordFile> files = EZGlobalSDK.getInstance().searchRecordFileFromDevice(serial, cameraNo, from, to);
            return files == null ? new ArrayList<>() : files;
        } catch (BaseException e) {
            // The SDK reports a day without recordings as error 1153445, not an empty list.
            if (e.getErrorCode() == 1153445) return new ArrayList<>();
            android.util.Log.w("Liza", "SD search failed: EZVIZ error " + e.getErrorCode());
            return null;
        } catch (Exception e) {
            android.util.Log.w("Liza", "SD search failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Recorded time in one day, with overlapping clips merged. */
    private static long recordedMs(List<EZDeviceRecordFile> files, long from, long to) {
        List<long[]> spans = new ArrayList<>();
        for (EZDeviceRecordFile f : files) {
            if (f == null || f.getStartTime() == null || f.getStopTime() == null) continue;
            long a = Math.max(from, f.getStartTime().getTimeInMillis()), b = Math.min(to, f.getStopTime().getTimeInMillis());
            if (b > a) spans.add(new long[]{a, b});
        }
        Collections.sort(spans, (x, y) -> Long.compare(x[0], y[0]));
        long total = 0, curA = -1, curB = -1;
        for (long[] s : spans) {
            if (s[0] > curB) { if (curB > curA) total += curB - curA; curA = s[0]; curB = s[1]; }
            else curB = Math.max(curB, s[1]);
        }
        if (curB > curA) total += curB - curA;
        return total;
    }

    /** Day of the oldest recording on the card: probe one day a week going back,
     *  stop after empty weeks, then find the exact day in the last week. */
    private Calendar findOldestSdDay() {
        Calendar today = SdReminder.today();
        int hit = -1, misses = 0;
        for (int back = 0; back <= 400; back += 7) {
            sdProgress("Looking for the oldest recording on the card, " + back + " days back…");
            List<EZDeviceRecordFile> files = sdDay(SdReminder.plusDays(today, -back));
            if (files == null) continue;
            if (!files.isEmpty()) { hit = back; misses = 0; }
            else if (++misses >= (hit < 0 ? 5 : 3)) break;
        }
        if (hit < 0) return null;
        for (int back = hit + 6; back > hit; back--) {
            sdProgress("Narrowing it down, " + back + " days back…");
            List<EZDeviceRecordFile> files = sdDay(SdReminder.plusDays(today, -back));
            if (files != null && !files.isEmpty()) return SdReminder.plusDays(today, -back);
        }
        return SdReminder.plusDays(today, -hit);
    }

    private void findSdStart() {
        setSdBusy(true);
        worker.execute(() -> {
            Calendar oldest = findOldestSdDay();
            runOnUiThread(() -> {
                setSdBusy(false);
                if (oldest == null) { sdResultView.setText("No recordings found on the card. If the camera is asleep, press the doorbell and try again."); return; }
                sdStart = SdReminder.iso(oldest); sdFull = null;
                updateSdButtons();
                sdResultView.setText("The oldest recording on the card is from " + SdReminder.label(sdStart) + ". If you emptied the card that day, keep this date and tap Check the card.");
            });
        });
    }

    private void checkSdCard() {
        int typedLife = readSdLife();
        SdReminder.setLife(this, serial, typedLife);
        String start = sdStart;
        setSdBusy(true);
        worker.execute(() -> {
            StringBuilder out = new StringBuilder();
            try {
                sdProgress("Reading the card…");
                double capGb = readSdCapacityGb();
                if (capGb <= 0) {
                    runOnUiThread(() -> { setSdBusy(false); sdResultView.setText("The camera didn't report an SD card. Check that a card is inserted, then try again."); });
                    return;
                }
                Calendar today = SdReminder.today();
                Calendar startDay = SdReminder.parse(start);
                Calendar oldest = findOldestSdDay();

                sdProgress("Reading the last 7 days of recordings…");
                long recMs = 0; int clips = 0, days = 0;
                for (int i = 1; i <= 7; i++) {
                    Calendar day = SdReminder.plusDays(today, -i);
                    List<EZDeviceRecordFile> files = sdDay(day);
                    if (files == null) continue;
                    recMs += recordedMs(files, day.getTimeInMillis(), SdReminder.plusDays(day, 1).getTimeInMillis());
                    clips += files.size(); days++;
                }

                out.append(String.format(java.util.Locale.getDefault(), "Card size: %.0f GB.", capGb));
                if (days > 0) out.append("\nRecorded: about ").append(Math.round(recMs / (double) days / 60000))
                        .append(" min of clips a day (").append(clips).append(" clips in the last ").append(days).append(" days).");

                boolean overwriting = oldest != null && SdReminder.daysBetween(startDay, oldest) > 1;
                int life = typedLife;
                if (oldest == null) {
                    out.append("\nNo recordings found on the card.");
                } else if (overwriting) {
                    int held = SdReminder.daysBetween(oldest, today);
                    out.append("\n\nThe card is full and overwriting. The oldest recording is from ").append(SdReminder.label(SdReminder.iso(oldest)))
                            .append(", after the card was emptied on ").append(SdReminder.label(start))
                            .append(". A full card holds about ").append(held).append(" days of recordings, and Liza will use that from now on.");
                    if (held >= 1) { life = held; SdReminder.setLife(this, serial, held); }
                } else {
                    out.append("\nThe oldest recording is from ").append(SdReminder.label(SdReminder.iso(oldest))).append(", so nothing has been overwritten yet.");
                }

                String check, full = null;
                if (overwriting) {
                    check = SdReminder.iso(today);
                } else if (life > 0) {
                    Calendar fullDay = SdReminder.plusDays(startDay, life);
                    full = SdReminder.iso(fullDay);
                    Calendar checkDay = SdReminder.plusDays(startDay, Math.max(1, (int) Math.floor(life * 0.9)));
                    if (checkDay.before(today)) checkDay = today;
                    check = SdReminder.iso(checkDay);
                    int pct = Math.min(100, Math.round(SdReminder.daysBetween(startDay, today) * 100f / life));
                    if (!fullDay.after(today)) out.append("\n\nA full card lasts about ").append(life).append(" days, so it is probably full by now (around ")
                            .append(SdReminder.label(full)).append("). Check it now.");
                    else out.append("\n\nA full card lasts about ").append(life).append(" days: about ").append(pct).append("% used, full around ")
                            .append(SdReminder.label(full)).append(". Suggested check: ").append(SdReminder.label(check)).append(", a little early in case the camera is busier than usual.");
                } else {
                    Calendar checkDay = SdReminder.plusDays(startDay, FIRST_CHECK_DAYS);
                    if (checkDay.before(today)) checkDay = today;
                    check = SdReminder.iso(checkDay);
                    out.append("\n\nLiza can't tell how full the card is yet: EZVIZ doesn't report free space, and the space a camera uses isn't proportional to clip length. Enter how many days a full card lasts if you know, or check on ")
                            .append(SdReminder.label(check)).append(". On that day tap Check the card again: if the camera has started overwriting, Liza measures how many days the card holds.");
                }
                final String result = out.toString(), suggested = check, fullDate = full;
                final int learned = life;
                runOnUiThread(() -> {
                    setSdBusy(false);
                    if (learned > 0) sdLifeInput.setText(String.valueOf(learned));
                    sdCheck = suggested; sdFull = fullDate;
                    updateSdButtons();
                    sdResultView.setText(result + "\n\nAdjust the reminder date if you like, then tap Save reminder.");
                });
            } catch (Exception e) {
                String message = e instanceof BaseException
                        ? "Check failed (EZVIZ error " + ((BaseException) e).getErrorCode() + "). If the camera is asleep, press the doorbell and try again."
                        : "Check failed. " + (e.getMessage() == null ? "Check the connection and retry." : e.getMessage());
                runOnUiThread(() -> { setSdBusy(false); sdResultView.setText(message); });
            }
        });
    }

    private boolean saveSdReminder() {
        if (sdCheck == null) { sdResultView.setText("Choose a reminder date first, or tap Check the card for a suggestion."); return false; }
        if (sdCheck.compareTo(sdStart) < 0) { sdResultView.setText("The reminder date must be after the date the card was emptied."); return false; }
        SdReminder.setLife(this, serial, readSdLife());
        SdReminder.save(this, serial, deviceName, sdStart, sdCheck, sdFull);
        if (android.os.Build.VERSION.SDK_INT >= 33 && !SdReminder.canNotify(this)) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 50);
        }
        sdResultView.setText("Reminder saved. Liza will notify you on " + SdReminder.label(sdCheck) + " at about 9:00.");
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 50) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            SdReminder.schedule(this, serial); // in case it fired while the prompt was open
        } else {
            sdResultView.setText("Reminder saved, but notifications are off for Liza. Use Add to calendar, or allow notifications in Android settings.");
        }
    }

    private void addSdToCalendar() {
        if (!saveSdReminder()) return;
        Calendar at = SdReminder.parse(sdCheck);
        at.set(Calendar.HOUR_OF_DAY, 9);
        String name = deviceName == null || deviceName.isEmpty() ? "your camera" : deviceName;
        Intent intent = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at.getTimeInMillis())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at.getTimeInMillis() + 15 * 60000L)
                .putExtra(CalendarContract.Events.TITLE, "Check the SD card in " + name)
                .putExtra(CalendarContract.Events.DESCRIPTION, "The card was emptied on " + SdReminder.label(sdStart) + "."
                        + (sdFull != null ? " Liza estimated it fills around " + SdReminder.label(sdFull) + "." : "")
                        + " When the card is full the camera overwrites its oldest recordings. Open Liza > Device settings and tap Check the card.");
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException e) { Toast.makeText(this, "No calendar app found.", Toast.LENGTH_SHORT).show(); }
    }

    private void markSdEmptied() {
        String previous = SdReminder.start(this, serial);
        Runnable apply = () -> {
            SdReminder.emptiedToday(this, serial);
            sdStart = SdReminder.start(this, serial); sdCheck = null; sdFull = null;
            int life = SdReminder.life(this, serial);
            sdLifeInput.setText(life > 0 ? String.valueOf(life) : "");
            updateSdButtons();
            sdResultView.setText("Marked as emptied today. Tap Check the card to plan the next reminder.");
        };
        Calendar prev = SdReminder.parse(previous);
        int days = prev == null ? 0 : SdReminder.daysBetween(prev, SdReminder.today());
        if (days < 1) { apply.run(); return; }
        new AlertDialog.Builder(this).setTitle("Was the card full?")
                .setMessage("If it was full, Liza uses the " + days + " days since it was last emptied to plan the next reminder.")
                .setPositiveButton("Yes, it was full", (d, w) -> { SdReminder.setLife(this, serial, days); apply.run(); })
                .setNegativeButton("No", (d, w) -> apply.run())
                .show();
    }

    private void section(LinearLayout page, String title) {
        page.addView(text(title, 18, Color.WHITE, true), top(26));
    }

    private void chooseVideoQuality() {
        String[] labels = {"Fluent", "Balanced", "HD"};
        new AlertDialog.Builder(this).setTitle("Live video quality")
                .setItems(labels, (dialog, which) -> {
                    qualityButton.setEnabled(false);
                    statusView.setText("Changing live video quality…");
                    worker.execute(() -> {
                        try {
                            boolean ok = EZGlobalSDK.getInstance().setVideoLevel(serial, cameraNo, which);
                            runOnUiThread(() -> {
                                qualityButton.setEnabled(true);
                                statusView.setText(ok ? "Live video quality set to " + labels[which] : "EZVIZ did not accept the video quality change.");
                            });
                        } catch (BaseException e) {
                            runOnUiThread(() -> {
                                qualityButton.setEnabled(true);
                                statusView.setText("Could not change video quality (EZVIZ error " + e.getErrorCode() + ").");
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                qualityButton.setEnabled(true);
                                statusView.setText("Could not change video quality. Check the connection and retry.");
                            });
                        }
                    });
                }).show();
    }

    private void flipImage() {
        flipButton.setEnabled(false);
        statusView.setText("Sending image flip command…");
        worker.execute(() -> {
            try {
                EZGlobalSDK.getInstance().controlVideoFlip(serial, cameraNo, EZPTZDisplayCommand.EZPTZDisplayCommandFlip);
                runOnUiThread(() -> {
                    flipButton.setEnabled(true);
                    statusView.setText("Image flip command sent. The camera may take a moment to update.");
                });
            } catch (BaseException e) {
                runOnUiThread(() -> {
                    flipButton.setEnabled(true);
                    statusView.setText("Could not flip image (EZVIZ error " + e.getErrorCode() + ").");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    flipButton.setEnabled(true);
                    statusView.setText("Could not flip image. Check the connection and retry.");
                });
            }
        });
    }

    private void refreshDetails() {
        worker.execute(() -> {
            try {
                EZDeviceInfo info = EZGlobalSDK.getInstance().getDeviceInfo(serial);
                runOnUiThread(() -> {
                    if (info == null) { statusView.setText("No settings returned for this device."); return; }
                    int defence = info.getDefence();
                    statusView.setText("" + (info.getStatus() == 1 ? "Online" : "Offline")
                            + "  ·  " + (defence == 1 ? "Armed" : "Disarmed"));
                    defenceButton.setText(defence == 1 ? "Disarm door alerts" : "Arm door alerts");
                    if (!info.isSupportDefence()) defenceButton.setEnabled(false);
                });
            } catch (BaseException e) {
                runOnUiThread(() -> statusView.setText("Could not load settings (EZVIZ error " + e.getErrorCode() + ")."));
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Could not load device settings. Check the connection and retry."));
            }
        });
    }

    private void renameDevice() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(deviceName == null ? "" : deviceName);
        new AlertDialog.Builder(this).setTitle("Rename device").setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String next = input.getText().toString().trim();
                    if (next.isEmpty()) return;
                    statusView.setText("Saving device name…");
                    worker.execute(() -> {
                        try {
                            boolean saved = EZGlobalSDK.getInstance().setDeviceName(serial, next);
                            runOnUiThread(() -> {
                                if (saved) { deviceName = next; nameView.setText(next); statusView.setText("Device name updated"); }
                                else statusView.setText("EZVIZ did not accept the new device name");
                            });
                        } catch (BaseException e) {
                            runOnUiThread(() -> statusView.setText("Rename failed (EZVIZ error " + e.getErrorCode() + ")."));
                        } catch (Exception e) {
                            runOnUiThread(() -> statusView.setText("Rename failed. Check the connection and retry."));
                        }
                    });
                }).show();
    }

    private void toggleDefence() {
        if (!defenceButton.isEnabled()) return;
        defenceButton.setEnabled(false);
        worker.execute(() -> {
            try {
                EZDeviceInfo info = EZGlobalSDK.getInstance().getDeviceInfo(serial);
                if (info == null) {
                    runOnUiThread(() -> { defenceButton.setEnabled(true); statusView.setText("Could not read the current alert state. Try again."); });
                    return;
                }
                int current = info.getDefence();
                EZConstants.EZDefenceStatus next = current == 1
                        ? EZConstants.EZDefenceStatus.EZDefence_IPC_CLOSE
                        : EZConstants.EZDefenceStatus.EZDefence_IPC_OPEN;
                EZGlobalSDK.getInstance().setDefence(serial, next);
                runOnUiThread(() -> { defenceButton.setEnabled(true); refreshDetails(); });
            } catch (BaseException e) {
                runOnUiThread(() -> { defenceButton.setEnabled(true); statusView.setText("Arm/disarm failed (EZVIZ error " + e.getErrorCode() + ")."); });
            } catch (Exception e) {
                runOnUiThread(() -> { defenceButton.setEnabled(true); statusView.setText("Arm/disarm failed. Check the connection and try again."); });
            }
        });
    }

    private void loadStorage() {
        storageButton.setEnabled(false);
        storageStatusView.setText("Checking storage…");
        worker.execute(() -> {
            try {
                List<EZStorageStatus> result = EZGlobalSDK.getInstance().getStorageStatus(serial);
                StringBuilder message = new StringBuilder();
                if (result == null || result.isEmpty()) message.append("No storage information returned.");
                else for (EZStorageStatus item : result) {
                    if (item == null) continue;
                    int code = item.getStatus();
                    String state = code == 0 ? "Normal" : code == 1 ? "Wrong storage medium"
                            : code == 2 ? "Unformatted" : code == 3 ? "Formatting" : "Unknown (" + code + ")";
                    if (message.length() > 0) message.append('\n');
                    String name = item.getName();
                    message.append(name == null || name.isEmpty() ? "Storage" : name).append(": ").append(state);
                }
                if (message.length() == 0) message.append("No storage information returned.");
                String resultText = message.toString();
                runOnUiThread(() -> { storageButton.setEnabled(true); storageStatusView.setText(resultText); });
            } catch (BaseException e) {
                String message = e.getErrorCode() == 120008
                        ? "The camera did not answer before the request timed out (120008). Check Wi-Fi and battery. If it is in Super Power-saving mode, wake it with live view or the doorbell, then retry."
                        : "Storage check failed (EZVIZ error " + e.getErrorCode() + ").";
                runOnUiThread(() -> { storageButton.setEnabled(true); storageStatusView.setText(message); });
            } catch (Exception e) {
                runOnUiThread(() -> { storageButton.setEnabled(true); storageStatusView.setText("Storage check failed. Check the connection and retry."); });
            }
        });
    }

    private void applySystemInsets(View view) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
                v.setPadding(bars.left, bars.top, bars.right, bottom);
                return insets;
            });
        }
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private Button button(String value, int fill, int color) {
        Button v = new Button(this); v.setText(value); v.setTextColor(color); v.setTextSize(14); v.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(fill); bg.setCornerRadius(dp(14)); v.setBackground(bg); return v;
    }
    private LinearLayout.LayoutParams top(int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(margin); return p; }
    private LinearLayout.LayoutParams topHeight(int height, int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(height)); p.topMargin = dp(margin); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
