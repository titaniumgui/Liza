package app.liza.android;

import android.app.Application;

import com.videogo.openapi.EZGlobalSDK;

public final class LizaApplication extends Application {
    private SecureVault vault;
    private String initializedKey;
    private ServerRegion initializedRegion;
    private boolean p2pEnabled;

    @Override public void onCreate() {
        super.onCreate();
        // Application's base Context is not attached during field initialization.
        // Construct the preferences-backed store only after super.onCreate().
        vault = new SecureVault(this);
        configureIfReady();
    }

    synchronized boolean configureIfReady() {
        String appKey = vault.get("app_key");
        if (appKey == null || appKey.trim().isEmpty()) return false;
        ServerRegion region = ServerRegion.fromLabel(vault.get("region"));
        try {
            if (initializedKey == null) {
                EZGlobalSDK.showSDKLog(false);
                EZGlobalSDK.setDebugStreamEnable(false);
                // Start with the EZVIZ relay route. LiveActivity can retry P2P
                // when the relay connects but no video frames arrive.
                EZGlobalSDK.enableP2P(false);
                if (!EZGlobalSDK.initLib(this, appKey.trim())) return false;
                initializedKey = appKey.trim();
            }
            EZGlobalSDK sdk = EZGlobalSDK.getInstance();
            if (sdk == null) return false;
            if (initializedRegion != region) {
                sdk.setServerUrl(region.apiUrl, region.authUrl);
                initializedRegion = region;
            }
            String token = vault.get("access_token");
            if (token != null && !token.isEmpty()) sdk.setAccessToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    SecureVault vault() { return vault; }

    synchronized boolean isP2pEnabled() { return p2pEnabled; }

    synchronized void setP2pEnabled(boolean enabled) {
        EZGlobalSDK.enableP2P(enabled);
        p2pEnabled = enabled;
    }
}
