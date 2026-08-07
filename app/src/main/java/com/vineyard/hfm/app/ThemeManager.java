package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

public class ThemeManager {

    private static final String PREFERENCES_NAME = "ThemePrefs";
    private static final String THEME_KEY = "SelectedTheme";

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_AMOLED = "amoled";
    public static final String THEME_NORDIC = "nordic";
    public static final String THEME_SOLARIZED = "solarized";

    public static void applyTheme(Activity activity) {
        String theme = getTheme(activity);
        switch (theme) {
            case THEME_DARK:
                activity.setTheme(R.style.AppTheme_Dark);
                break;
            case THEME_AMOLED:
                activity.setTheme(R.style.AppTheme_Amoled);
                break;
            case THEME_NORDIC:
                activity.setTheme(R.style.AppTheme_Nordic);
                break;
            case THEME_SOLARIZED:
                activity.setTheme(R.style.AppTheme_Solarized);
                break;
            default:
                activity.setTheme(R.style.AppTheme_Light);
                break;
        }
    }

    public static String getTheme(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        return prefs.getString(THEME_KEY, THEME_LIGHT); // Default to Light
    }

    public static void setTheme(Context context, String theme) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(THEME_KEY, theme);
        editor.apply();
    }
}

