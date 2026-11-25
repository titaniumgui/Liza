package app.liza.android;

import android.app.Activity;
import android.app.AlertDialog;
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
import com.videogo.openapi.bean.EZStorageStatus;
import com.videogo.openapi.EZConstants.EZPTZDisplayCommand;

import java.util.List;
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
