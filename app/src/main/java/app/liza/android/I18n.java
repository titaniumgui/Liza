package app.liza.android;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * App language. English text in the code is the lookup key for tr(); other
 * languages live in res/raw/<lang>.json as {"English": "translation"} with
 * {0}, {1}… placeholders. tools/check_i18n.py finds untranslated strings.
 * The choice is stored in preferences and applied to every activity through
 * attachBaseContext(), which also sets the layout direction (RTL for Arabic).
 */
final class I18n {
    private static final String PREFS = "liza_settings";
    private static final String KEY = "language";
    private static volatile String lang = "en";
    private static volatile Map<String, String> dict = Collections.emptyMap();
    private static Locale systemLocale;

    private I18n() {}

    /** Loads the saved language. Called once from LizaApplication. */
    static synchronized void init(Context c) {
        if (systemLocale == null) systemLocale = Locale.getDefault();
        String saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        lang = "ar".equals(saved) ? "ar" : "en";
        dict = Collections.emptyMap();
        if ("ar".equals(lang)) {
            try (Reader in = new InputStreamReader(c.getResources().openRawResource(R.raw.ar), StandardCharsets.UTF_8)) {
                Map<String, String> loaded = new Gson().fromJson(in, new TypeToken<Map<String, String>>() {}.getType());
                if (loaded != null) dict = loaded;
            } catch (Exception ignored) { /* fall back to English text */ }
        }
        Locale.setDefault(locale());
    }

    /** True once the user has picked a language (the first-run screen is done). */
    static boolean chosen(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) != null;
    }

    static String lang() { return lang; }

    static boolean rtl() { return "ar".equals(lang); }

    /** Saves the choice and restarts the app's screens in the new language. */
    static void set(Context c, String language) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).commit();
        init(c.getApplicationContext());
        Intent home = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        c.startActivity(home);
    }

    /** Arabic keeps Western digits, to match the camera's own timestamps. */
    static Locale locale() {
        if ("ar".equals(lang)) return Locale.forLanguageTag("ar-u-nu-latn");
        String country = systemLocale == null ? "" : systemLocale.getCountry();
        return new Locale("en", country);
    }

    /**
     * A date format in the app language, from a skeleton such as "MMMd" or "yMMMd".
     * Android resets Locale.getDefault() to the phone's language after startup,
     * so formatters must ask for the app locale explicitly.
     */
    static java.text.SimpleDateFormat dateFormat(String skeleton) {
        Locale l = locale();
        return new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(l, skeleton), l);
    }

    /** A context whose resources, formatting and layout direction follow the app language. */
    static Context wrap(Context base) {
        Locale locale = locale();
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocale(locale);
        conf.setLayoutDirection(locale);
        return base.createConfigurationContext(conf);
    }

    /** Translates English text; {0}, {1}… are replaced by args. */
    static String tr(String en, Object... args) {
        String s = en;
        if (!"en".equals(lang)) {
            String t = dict.get(en);
            if (t != null) s = t;
        }
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }
}
