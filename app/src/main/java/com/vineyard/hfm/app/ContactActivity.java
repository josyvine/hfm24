package com.vineyard.hfm.app;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

public class ContactActivity extends Activity {

    private static final String TAG = "ContactActivity";
    private static final String FORM_API_ENDPOINT = "https://formspree.io/f/xyzenlao";

    private ImageButton backButton;
    private EditText nameEditText, emailEditText, messageEditText;
    private Button sendButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact);

        backButton = findViewById(R.id.back_button_contact);
        nameEditText = findViewById(R.id.edit_text_name);
        emailEditText = findViewById(R.id.edit_text_email);
        messageEditText = findViewById(R.id.edit_text_message);
        sendButton = findViewById(R.id.button_send);

        backButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish(); // Closes the activity and returns to the previous one
				}
			});

        sendButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					sendMessage();
				}
			});
    }

    private void sendMessage() {
        String name = nameEditText.getText().toString().trim();
        String email = emailEditText.getText().toString().trim();
        String message = messageEditText.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "Please enter your name.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(email) || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Please enter a valid email address.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(message)) {
            Toast.makeText(this, "Please enter a message.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create a JSON object with the form data
        JSONObject formData = new JSONObject();
        try {
            formData.put("name", name);
            formData.put("email", email);
            formData.put("message", message);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON object", e);
            Toast.makeText(this, "An error occurred. Please try again.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Send the data using an AsyncTask to avoid blocking the main thread
        new SendFormTask().execute(formData.toString());
    }

    private class SendFormTask extends AsyncTask<String, Void, Boolean> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            sendButton.setEnabled(false); // Disable button while sending
            sendButton.setText("Sending...");
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String jsonData = params[0];
            HttpURLConnection urlConnection = null;
            try {
                URL url = new URL(FORM_API_ENDPOINT);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("POST");
                urlConnection.setRequestProperty("Content-Type", "application/json");
                urlConnection.setRequestProperty("Accept", "application/json");
                urlConnection.setDoOutput(true);
                urlConnection.setDoInput(true);

                // Write the JSON data to the connection
                OutputStream os = urlConnection.getOutputStream();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
                writer.write(jsonData);
                writer.flush();
                writer.close();
                os.close();

                int responseCode = urlConnection.getResponseCode();
                Log.d(TAG, "Formspree Response Code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // You can optionally read the response if needed
                    BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    String inputLine;
                    StringBuffer response = new StringBuffer();
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();
                    Log.d(TAG, "Formspree Response: " + response.toString());
                    return true;
                } else {
                    return false;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error sending form data", e);
                return false;
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            sendButton.setEnabled(true); // Re-enable button
            sendButton.setText("Send Message");

            if (success) {
                Toast.makeText(ContactActivity.this, "Message sent successfully!", Toast.LENGTH_LONG).show();
                // Clear the form fields
                nameEditText.setText("");
                emailEditText.setText("");
                messageEditText.setText("");
            } else {
                Toast.makeText(ContactActivity.this, "Failed to send message. Please check your internet connection and try again.", Toast.LENGTH_LONG).show();
            }
        }
    }
}

