package app.liza.android;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.videogo.exception.BaseException;
import com.videogo.openapi.EZGlobalSDK;
import com.videogo.openapi.bean.EZCameraInfo;
import com.videogo.openapi.bean.EZDeviceInfo;
import com.videogo.openapi.bean.EZAccessToken;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(12, 17, 25);
    private static final int PANEL = Color.rgb(23, 31, 43);
    private static final int MUTED = Color.rgb(158, 172, 191);
    private static final int ACCENT = Color.rgb(57, 197, 160);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<EZDeviceInfo> devices = new ArrayList<>();
    private LizaApplication app;
    private LinearLayout content;
    private TextView status;
    private LinearLayout deviceList;
    private EditText appKeyInput;
    private Spinner regionPicker;
    private boolean receiverRegistered;
    private Button signInButton;
    private Button signOutButton;
    private final BroadcastReceiver loginReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            worker.execute(() -> {
                try {
                    EZAccessToken access = EZGlobalSDK.getInstance().getEZAccessToken();
                    if (access == null || access.getAccessToken() == null || access.getAccessToken().isEmpty()) {
                        runOnUiThread(() -> setStatus("EZVIZ sign-in finished, but no access token was returned."));
                        return;
                    }
                    app.vault().put("access_token", access.getAccessToken());
                    EZGlobalSDK.getInstance().setAccessToken(access.getAccessToken());
                    runOnUiThread(() -> {
                        setStatus("Connected to EZVIZ");
                        refreshAuthControls();
                        refreshDevices();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> setStatus("Sign-in completed, but Liza could not save the session."));
                }
            });
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (LizaApplication) getApplication();
        buildHome();
        registerLoginReceiver();
        boolean configured = app.configureIfReady();
        if (configured) {
            String token = app.vault().get("access_token");
            if (token != null) {
                setStatus("Signed in to EZVIZ");
                refreshAuthControls();
                refreshDevices();
            } else {
                setStatus("Sign in to load your door viewer");
            }
        } else {
            setStatus("Add your EZVIZ Open Platform AppKey to get started");
        }
    }

    private void registerLoginReceiver() {
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter("com.videogo.action.OAUTH_SUCCESS_ACTION");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(loginReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(loginReceiver, filter);
            receiverRegistered = true;
        }
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(loginReceiver);
            receiverRegistered = false;
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
                view.setPadding(bars.left, bars.top, bars.right, bottom);
                return insets;
            });
        }
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(20), dp(22), dp(24));
        content.setBackgroundColor(BG);
        scroll.addView(content);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("LIZA", 13, ACCENT, true);
        brand.setLetterSpacing(.18f);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView about = text("i", 16, ACCENT, true);
        about.setGravity(Gravity.CENTER);
        about.setBackground(rounded(PANEL, 24));
        about.setContentDescription("About Liza");
        about.setClickable(true);
        about.setFocusable(true);
        brandRow.addView(about, new LinearLayout.LayoutParams(dp(36), dp(36)));
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        content.addView(brandRow);
        TextView title = text("Front door", 30, Color.WHITE, true);
        LinearLayout.LayoutParams tp = params(-1, -2);
        tp.topMargin = dp(8);
        content.addView(title, tp);
        content.addView(text("A quicker way to see and answer your door", 14, MUTED, false));

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams cp = params(-1, -2);
        cp.topMargin = dp(22);
        content.addView(statusCard, cp);
        status = text("Checking EZVIZ setup…", 15, Color.WHITE, true);
        statusCard.addView(status);

        TextView setupHeader = section("EZVIZ connection");
        content.addView(setupHeader, top(24));
        appKeyInput = new EditText(this);
        appKeyInput.setSingleLine(true);
        appKeyInput.setHint("Open Platform AppKey");
        appKeyInput.setTextColor(Color.WHITE);
        appKeyInput.setHintTextColor(MUTED);
        appKeyInput.setTextSize(15);
        appKeyInput.setPadding(dp(14), 0, dp(14), 0);
        appKeyInput.setBackground(rounded(PANEL, 14));
        String existingKey = app.vault().get("app_key");
        if (existingKey != null) appKeyInput.setText(existingKey);
        content.addView(appKeyInput, topHeight(54, 8));

        regionPicker = new Spinner(this);
        ArrayAdapter<ServerRegion> adapter = new ArrayAdapter<ServerRegion>(this, 0, ServerRegion.values()) {
            private TextView regionView(ServerRegion region) {
                TextView view = new TextView(MainActivity.this);
                view.setText(region.label);
                view.setTextColor(Color.WHITE);
                view.setTextSize(15);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(14), dp(10), dp(14), dp(10));
                return view;
            }
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return regionView(getItem(position));
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = regionView(getItem(position));
                view.setBackgroundColor(PANEL);
                return view;
            }
        };
        regionPicker.setAdapter(adapter);
        regionPicker.setBackground(rounded(PANEL, 14));
        regionPicker.setPadding(dp(12), 0, dp(12), 0);
        regionPicker.setSelection(regionIndex(ServerRegion.fromLabel(app.vault().get("region"))));
        content.addView(regionPicker, topHeight(52, 8));
        TextView regionHint = text("Choose the same EZVIZ service region as your account and camera.", 12, MUTED, false);
        content.addView(regionHint, top(6));

        Button save = button("Save connection", ACCENT, BG);
        save.setOnClickListener(v -> saveConnection());
        content.addView(save, topHeight(52, 12));

        signInButton = button("Sign in with EZVIZ", PANEL, Color.WHITE);
        signInButton.setOnClickListener(v -> signIn());
        content.addView(signInButton, topHeight(52, 10));
        signOutButton = button("Sign out", PANEL, Color.WHITE);
        signOutButton.setOnClickListener(v -> signOut());
        content.addView(signOutButton, topHeight(48, 8));

        TextView devicesTitle = section("Your devices");
        content.addView(devicesTitle, top(26));
        Button refresh = button("Refresh devices", PANEL, Color.WHITE);
        refresh.setOnClickListener(v -> refreshDevices());
        content.addView(refresh, topHeight(48, 10));
        Button alerts = button("Recent motion alerts", PANEL, Color.WHITE);
        alerts.setOnClickListener(v -> startActivity(new Intent(this, AlertsActivity.class)));
        content.addView(alerts, topHeight(48, 8));

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(deviceList, top(6));

        TextView privacy = text("Liza stores the AppKey and EZVIZ session token encrypted on this phone. Camera video is streamed by the EZVIZ SDK.", 12, MUTED, false);
        content.addView(privacy, top(26));
        setContentView(scroll);
        refreshAuthControls();
    }

    private void refreshAuthControls() {
        if (signInButton == null || signOutButton == null) return;
        boolean signedIn = app.vault().get("access_token") != null;
        signInButton.setText(signedIn ? "Signed in to EZVIZ" : "Sign in with EZVIZ");
        signInButton.setEnabled(!signedIn);
        signInButton.setAlpha(signedIn ? 0.75f : 1f);
        signOutButton.setVisibility(signedIn ? View.VISIBLE : View.GONE);
    }

    private void signOut() {
        try {
            EZGlobalSDK sdk = EZGlobalSDK.getInstance();
            if (sdk != null) sdk.logout();
            app.vault().put("access_token", null);
            deviceList.removeAllViews();
            setStatus("Signed out of EZVIZ");
            refreshAuthControls();
        } catch (Exception e) {
            setStatus("Could not sign out. Please try again.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAuthControls();
    }

    private void saveConnection() {
        String key = appKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Enter your AppKey first", Toast.LENGTH_SHORT).show();
            setStatus("AppKey is required");
            return;
        }
        ServerRegion region = (ServerRegion) regionPicker.getSelectedItem();
        String priorKey = app.vault().get("app_key");
        String priorRegion = app.vault().get("region");
        try {
            if (priorKey != null && !priorKey.equals(key)) app.vault().put("access_token", null);
            if (priorRegion != null && !priorRegion.equals(region.label)) app.vault().put("access_token", null);
            app.vault().put("app_key", key);
            app.vault().put("region", region.label);
            if (!app.configureIfReady()) {
                setStatus("SDK setup failed. Check the AppKey and selected region.");
                return;
            }
            setStatus("Connection settings saved. Sign in to EZVIZ.");
            Toast.makeText(this, "Connection settings saved", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            setStatus("Could not securely save the AppKey on this phone.");
        }
    }

    private void signIn() {
        if (!app.configureIfReady()) {
            Toast.makeText(this, "Save your AppKey and region first", Toast.LENGTH_LONG).show();
            return;
        }
        ServerRegion region = (ServerRegion) regionPicker.getSelectedItem();
        if (region == null) region = ServerRegion.fromLabel(app.vault().get("region"));
        try {
            EZGlobalSDK.getInstance().openLoginPage(region.areaId);
        } catch (Exception e) {
            setStatus("Could not open EZVIZ sign-in: " + e.getMessage());
        }
    }

    private void refreshDevices() {
        if (!app.configureIfReady()) {
            setStatus("Save your EZVIZ AppKey and region first");
            return;
        }
        if (app.vault().get("access_token") == null) {
            setStatus("Sign in to EZVIZ before loading devices");
            return;
        }
        setStatus("Loading devices…");
        worker.execute(() -> {
            try {
                List<EZDeviceInfo> result = EZGlobalSDK.getInstance().getDeviceList(0, 20);
                runOnUiThread(() -> showDevices(result));
            } catch (BaseException e) {
                String message = "Could not load devices (EZVIZ error " + e.getErrorCode() + "). Check account region, AppKey, and session.";
                runOnUiThread(() -> { setStatus(message); deviceList.removeAllViews(); });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Device request failed: " + e.getMessage()));
            }
        });
    }

    private void showDevices(List<EZDeviceInfo> result) {
        devices.clear();
        deviceList.removeAllViews();
        if (result != null) devices.addAll(result);
        if (devices.isEmpty()) {
            setStatus("Connected. No devices were returned for this account.");
            deviceList.addView(text("Add the DP2C to this EZVIZ account, then refresh.", 14, MUTED, false), top(8));
            return;
        }
        setStatus("Connected · " + devices.size() + (devices.size() == 20 ? "+ devices" : " device(s)"));
        for (EZDeviceInfo device : devices) addDeviceRow(device);
    }

    private void addDeviceRow(EZDeviceInfo device) {
        String serial = device.getDeviceSerial();
        String name = device.getDeviceName();
        if (name == null || name.trim().isEmpty()) name = "Door viewer";
        final String displayName = name;
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.addView(text(displayName, 16, Color.WHITE, true));
        String state = device.getStatus() == 1 ? "Online" : "Offline";
        textBlock.addView(text(state + "  ·  " + serial, 12, MUTED, false), top(5));
        row.addView(textBlock, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(v -> openDevice(device, displayName));
        Button settings = button("⚙  Settings", BG, ACCENT);
        settings.setTextSize(12);
        settings.setMinHeight(dp(44));
        settings.setContentDescription("Settings for " + displayName);
        row.addView(settings, new LinearLayout.LayoutParams(dp(104), dp(44)));
        settings.setOnClickListener(v -> {
            Intent intent = new Intent(this, DeviceSettingsActivity.class);
            intent.putExtra("serial", device.getDeviceSerial());
            intent.putExtra("name", displayName);
            int cameraNo = 1;
            List<EZCameraInfo> cameras = device.getCameraInfoList();
            if (cameras != null && !cameras.isEmpty()) cameraNo = cameras.get(0).getCameraNo();
            intent.putExtra("cameraNo", cameraNo);
            startActivity(intent);
        });
        LinearLayout.LayoutParams rp = params(-1, -2);
        rp.topMargin = dp(8);
        deviceList.addView(row, rp);
    }

    private void openDevice(EZDeviceInfo device, String name) {
        int cameraNo = 1;
        int videoLevel = 2;
        List<EZCameraInfo> cameras = device.getCameraInfoList();
        if (cameras != null && !cameras.isEmpty()) {
            EZCameraInfo camera = cameras.get(0);
            cameraNo = camera.getCameraNo();
            if (camera.getVideoLevel() != null) videoLevel = Math.min(2, camera.getVideoLevel().getVideoLevel());
        }
        Intent intent = new Intent(this, LiveActivity.class);
        intent.putExtra("serial", device.getDeviceSerial());
        intent.putExtra("name", name);
        intent.putExtra("cameraNo", cameraNo);
        intent.putExtra("videoLevel", videoLevel);
        intent.putExtra("encrypted", device.getIsEncrypt() == 1);
        startActivity(intent);
    }

    private void setStatus(String value) { if (status != null) status.setText(value); }
    private int regionIndex(ServerRegion wanted) {
        ServerRegion[] values = ServerRegion.values();
        for (int i = 0; i < values.length; i++) if (values[i] == wanted) return i;
        return 0;
    }

    private TextView section(String label) {
        TextView view = text(label, 18, Color.WHITE, true);
        return view;
    }
    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(15), dp(16), dp(15));
        view.setBackground(rounded(PANEL, 18));
        return view;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private Button button(String value, int fill, int color) {
        Button view = new Button(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(15);
        view.setAllCaps(false);
        view.setBackground(rounded(fill, 15));
        return view;
    }
    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams p = params(-1, -2);
        p.topMargin = dp(margin);
        return p;
    }
    private LinearLayout.LayoutParams topHeight(int height, int margin) {
        LinearLayout.LayoutParams p = params(-1, dp(height));
        p.topMargin = dp(margin);
        return p;
    }
    private LinearLayout.LayoutParams params(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
