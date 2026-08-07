package com.vineyard.hfm.app;

import android.content.Context;
import android.content.SharedPreferences;

public class ApiKeyManager {

    private static final String PREFERENCES_NAME = "ApiKeyPrefs";
    private static final String API_KEY = "GeminiApiKey";

    /**
     * Saves the user's Gemini API key to SharedPreferences.
     * @param context The application context.
     * @param apiKey The API key to save.
     */
    public static void saveApiKey(Context context, String apiKey) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(API_KEY, apiKey);
        editor.apply();
    }

    /**
     * Retrieves the saved Gemini API key from SharedPreferences.
     * @param context The application context.
     * @return The saved API key, or null if it's not set.
     */
    public static String getApiKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        return prefs.getString(API_KEY, null);
    }
}
