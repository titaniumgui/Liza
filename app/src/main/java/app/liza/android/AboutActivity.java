package app.liza.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class AboutActivity extends Activity {
    private static final int BG = Color.rgb(12, 17, 25);
    private static final int PANEL = Color.rgb(23, 31, 43);
    private static final int MUTED = Color.rgb(158, 172, 191);
    private static final int ACCENT = Color.rgb(57, 197, 160);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
                bottom = Math.max(bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(20), dp(22), dp(28));
        page.setBackgroundColor(BG);
        scroll.addView(page);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("LIZA", 13, ACCENT, true);
        brand.setLetterSpacing(.18f);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        Button done = button("Done");
        done.setOnClickListener(v -> finish());
        top.addView(done, new LinearLayout.LayoutParams(-2, dp(40)));
        page.addView(top);

        TextView title = text("About Liza", 30, Color.WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(24);
        page.addView(title, titleParams);
        page.addView(text("A quicker way to see and answer your door", 14, MUTED, false), top(5));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(PANEL, 20));
        page.addView(card, top(24));
        card.addView(text("Liza", 20, Color.WHITE, true));
        String version = "Unknown";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { }
        card.addView(text("Version " + version, 14, MUTED, false), top(8));
        card.addView(text("Co-developed with Codex", 15, Color.WHITE, true), top(22));
        card.addView(text("Hassan Khallaf", 14, MUTED, false), top(5));
        card.addView(text("Built for quick, direct access to your EZVIZ door camera.", 14, MUTED, false), top(22));
        setContentView(scroll);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private Button button(String value) {
        Button v = new Button(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setAllCaps(false);
        v.setBackground(rounded(PANEL, 14));
        return v;
    }
    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(margin);
        return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
