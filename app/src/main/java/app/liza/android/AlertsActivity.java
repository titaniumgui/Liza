package app.liza.android;

import static app.liza.android.I18n.tr;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.videogo.exception.BaseException;
import com.videogo.openapi.EZGlobalSDK;
import com.videogo.openapi.bean.EZAlarmInfo;

import java.util.Calendar;
import java.util.List;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AlertsActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(I18n.wrap(base)); }

    private static final int BG = Color.rgb(12, 17, 25);
    private static final int PANEL = Color.rgb(23, 31, 43);
    private static final int MUTED = Color.rgb(158, 172, 191);
    private static final int ACCENT = Color.rgb(57, 197, 160);
    private final ExecutorService worker = Executors.newFixedThreadPool(4);
    private LinearLayout list;
    private TextView status;
    private LizaApplication app;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = (LizaApplication) getApplication();
        buildScreen();
        loadAlerts();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
                v.setPadding(bars.left, bars.top, bars.right, bottom);
                return insets;
            });
        }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(28));
        page.setBackgroundColor(BG);

        Button back = button(tr("‹  Back"), PANEL, Color.WHITE);
        back.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        back.setContentDescription(tr("Back to devices"));
        back.setOnClickListener(v -> finish());
        page.addView(back, size(-1, 48));

        TextView title = text(tr("Motion alerts"), 28, Color.WHITE, true);
        page.addView(title, top(20));
        TextView subtitle = text(tr("Events from the last 24 hours"), 14, MUTED, false);
        page.addView(subtitle, top(4));

        status = text(tr("Loading alerts…"), 14, MUTED, false);
        page.addView(status, top(20));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, top(4));

        Button refresh = button(tr("Refresh alerts"), ACCENT, BG);
        refresh.setOnClickListener(v -> loadAlerts());
        page.addView(refresh, top(18, 50));
        scroll.addView(page);
        setContentView(scroll);
    }

    private void loadAlerts() {
        if (!app.configureIfReady() || app.vault().get("access_token") == null) {
            showMessage(tr("Sign in to EZVIZ from the home screen to view alerts."));
            return;
        }
        status.setText(tr("Loading alerts…"));
        list.removeAllViews();
        Calendar end = Calendar.getInstance();
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.HOUR_OF_DAY, -24);
        worker.execute(() -> {
            try {
                List<EZAlarmInfo> alarms = EZGlobalSDK.getInstance().getAlarmList(null, 0, 50, start, end);
                runOnUiThread(() -> showAlerts(alarms));
            } catch (BaseException e) {
                runOnUiThread(() -> showMessage(tr("Could not load alerts (EZVIZ error {0}).", e.getErrorCode())));
            } catch (Exception e) {
                runOnUiThread(() -> showMessage(tr("Alert request failed. Check your connection and try again.")));
            }
        });
    }

    private void showAlerts(List<EZAlarmInfo> alarms) {
        list.removeAllViews();
        if (alarms == null || alarms.isEmpty()) {
            showMessage(tr("No motion alerts in the last 24 hours."));
            return;
        }
        status.setText(tr("{0} events", alarms.size() + (alarms.size() == 50 ? "+" : "")));
        for (EZAlarmInfo alarm : alarms) {
            String source = alarm.getDeviceName();
            if (source == null || source.trim().isEmpty()) source = alarm.getDeviceSerial();
            if (source == null || source.trim().isEmpty()) source = tr("Your camera");
            String event = alarm.getAlarmName();
            if (event == null || event.trim().isEmpty() || event.equals(source)) event = tr("Motion detected");
            String time = alarm.getAlarmStartTime();

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPaddingRelative(dp(12), dp(12), dp(14), dp(12));
            card.setBackground(rounded(PANEL, 18));
            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackground(rounded(Color.rgb(34, 47, 61), 12));
            thumbnail.setImageResource(android.R.drawable.ic_menu_camera);
            card.addView(thumbnail, size(dp(76), 76));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            TextView eventText = text(event, 16, Color.WHITE, true);
            eventText.setMaxLines(2);
            details.addView(eventText);
            TextView deviceText = text(source, 13, MUTED, false);
            deviceText.setMaxLines(1);
            deviceText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(deviceText, top(4));
            TextView timeText = text(time == null ? tr("Time unavailable") : time, 12, MUTED, false);
            // EZVIZ sends "yyyy-MM-dd HH:mm:ss"; keep it left-to-right inside Arabic layouts.
            timeText.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
            details.addView(timeText, top(6));
            LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1f);
            detailsParams.setMarginStart(dp(12)); // flips with the layout in Arabic
            card.addView(details, detailsParams);
            TextView arrow = text("›", 28, MUTED, false);
            card.addView(arrow, size(dp(24), -2));
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> openAlarm(alarm));
            String pictureUrl = alarm.getAlarmPicUrl();
            if (pictureUrl != null && !pictureUrl.trim().isEmpty()) {
                worker.execute(() -> {
                    try {
                        Bitmap bitmap;
                        java.net.URLConnection connection = new URL(pictureUrl).openConnection();
                        connection.setConnectTimeout(5000);
                        connection.setReadTimeout(5000);
                        try (java.io.InputStream in = connection.getInputStream()) { bitmap = BitmapFactory.decodeStream(in); }
                        if (bitmap != null) runOnUiThread(() -> thumbnail.setImageBitmap(bitmap));
                    } catch (Exception ignored) { }
                });
            }
            list.addView(card, top(8));
        }
    }

    private void openAlarm(EZAlarmInfo alarm) {
        Intent intent = new Intent(this, LiveActivity.class);
        intent.putExtra("serial", alarm.getDeviceSerial());
        intent.putExtra("name", alarm.getDeviceName());
        intent.putExtra("cameraNo", alarm.getCameraNo());
        intent.putExtra("alarmTime", alarm.getAlarmStartTime());
        intent.putExtra("alarmPreTime", alarm.getPreTime());
        intent.putExtra("alarmId", alarm.getAlarmId());
        intent.putExtra("encrypted", alarm.getIsEncrypt() != 0);
        startActivity(intent);
    }

    private void showMessage(String message) {
        status.setText(message);
        list.removeAllViews();
    }

    private Button button(String label, int fill, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(color);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(rounded(fill, 15));
        return b;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextAlignment(android.view.View.TEXT_ALIGNMENT_VIEW_START);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private LinearLayout.LayoutParams size(int width, int height) { return new LinearLayout.LayoutParams(width, height < 0 ? height : dp(height)); }
    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(margin);
        return p;
    }
    private LinearLayout.LayoutParams top(int margin, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(height));
        p.topMargin = dp(margin);
        return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
