package app.liza.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First-run language choice. Both languages are shown side by side, so this
 * screen is never translated. MainActivity opens it until a language is saved;
 * About offers the same choice later.
 */
public final class LanguageActivity extends Activity {
    private static final int BG = Color.rgb(12, 17, 25);
    private static final int PANEL = Color.rgb(23, 31, 43);
    private static final int MUTED = Color.rgb(158, 172, 191);
    private static final int ACCENT = Color.rgb(57, 197, 160);

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(I18n.wrap(base)); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        page.setPadding(dp(24), dp(32), dp(24), dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -1));

        TextView brand = text("LIZA", 13, ACCENT, true);
        brand.setLetterSpacing(.18f);
        page.addView(brand);
        TextView en = text("Choose your language", 26, Color.WHITE, true);
        page.addView(en, top(14));
        TextView ar = text("اختر لغتك", 24, MUTED, true);
        ar.setTextDirection(View.TEXT_DIRECTION_RTL);
        page.addView(ar, top(4));

        page.addView(option("Aa", "English", "Continue in English", "en", false), topHeight(150, 32));
        page.addView(option("ع", "العربية", "المتابعة بالعربية", "ar", true), topHeight(150, 14));

        page.addView(text("You can change this later in About.", 13, MUTED, false), top(26));
        TextView noteAr = text("يمكنك تغييرها لاحقًا من «حول التطبيق».", 13, MUTED, false);
        noteAr.setTextDirection(View.TEXT_DIRECTION_RTL);
        page.addView(noteAr, top(2));
        setContentView(scroll);
    }

    private View option(String mark, String name, String sub, String lang, boolean rtl) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(2), Color.rgb(34, 47, 61));
        card.setBackground(bg);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(name);

        TextView badge = text(mark, 22, ACCENT, true);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.argb(36, 57, 197, 160));
        badge.setBackground(circle);
        card.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));
        card.addView(text(name, 21, Color.WHITE, true), top(8));
        card.addView(text(sub, 13, MUTED, false), top(2));
        card.setOnClickListener(v -> {
            bg.setStroke(dp(2), ACCENT);
            I18n.set(this, lang);
            finish();
        });
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(margin);
        return p;
    }
    private LinearLayout.LayoutParams topHeight(int height, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(height));
        p.topMargin = dp(margin);
        return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
