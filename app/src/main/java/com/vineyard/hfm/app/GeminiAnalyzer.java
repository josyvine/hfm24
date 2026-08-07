package com.vineyard.hfm.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GeminiAnalyzer {

    private static final String TAG = "GeminiAnalyzer";
    // --- THIS IS THE CORRECTED ENDPOINT AS YOU INSTRUCTED ---
    private static final String GEMINI_API_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    private static final long MAX_TEXT_PAYLOAD_SIZE = 250000; // Limit text sent to API

    private Context context;
    private TextView resultTextView;
    private ProgressBar progressBar;
    private Button copySummaryButton;

    public GeminiAnalyzer(Context context, TextView resultTextView, ProgressBar progressBar, Button copySummaryButton) {
        this.context = context;
        this.resultTextView = resultTextView;
        this.progressBar = progressBar;
        this.copySummaryButton = copySummaryButton;
    }

    public void analyze(List<File> files) {
        String apiKey = ApiKeyManager.getApiKey(context);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Toast.makeText(context, "Gemini API Key not set.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isNetworkAvailable()) {
            Toast.makeText(context, "No internet connection.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AnalyzeTask(apiKey).execute(files);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }
        return false;
    }

    private class AnalyzeTask extends AsyncTask<List<File>, Void, String> {
        private String apiKey;
        private String error = null;

        AnalyzeTask(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        protected String doInBackground(List<File>... params) {
            List<File> files = params[0];
            String prompt;
            String jsonPayload = null;

            try {
                if (files.size() == 1) {
                    File item = files.get(0);
                    if (item.isDirectory()) {
                        prompt = "Provide a brief summary of the contents of a folder containing the following items: ";
                        StringBuilder fileList = new StringBuilder();
                        File[] children = item.listFiles();
                        if (children != null) {
                            int count = 0;
                            for (File child : children) {
                                if (count++ < 50) { // Limit to 50 items
                                    fileList.append(child.getName()).append(", ");
                                } else {
                                    fileList.append(" and more...");
                                    break;
                                }
                            }
                        }
                        prompt += fileList.toString();
                        jsonPayload = buildTextPayload(prompt);
                    } else { // It's a single file
                        String extension = getFileExtension(item);
                        if (isImage(extension)) {
                            prompt = "Describe this image in detail.";
                            String base64Image = imageToBase64(item);
                            if (base64Image != null) {
                                jsonPayload = buildImagePayload(prompt, base64Image, "image/jpeg");
                            } else {
                                error = "Failed to process image.";
                            }
                        } else if (extension.equals("pdf")) {
                            prompt = "Provide a tiny summary of the following PDF text. If I ask again, provide a more detailed summary.";
                            String pdfText = extractTextFromPdf(item);
                            String existingSummary = resultTextView.getText().toString();
                            if (!existingSummary.isEmpty()) {
                                prompt += "\n\nExisting summary (provide more detail): " + existingSummary;
                            }
                            jsonPayload = buildTextPayload(prompt + "\n\n" + pdfText);
                        } else { // Assume text-based file
                            prompt = "Provide a tiny summary of the following text/code. If I ask again, provide a more detailed summary.";
                            String fileContent = readFileContent(item);
                            String existingSummary = resultTextView.getText().toString();
                            if (!existingSummary.isEmpty()) {
                                prompt += "\n\nExisting summary (provide more detail): " + existingSummary;
                            }
                            jsonPayload = buildTextPayload(prompt + "\n\n" + fileContent);
                        }
                    }
                } else { // Multiple files
                    prompt = "Provide a brief summary of a selection containing " + files.size() + " files, including items like: ";
                    StringBuilder fileList = new StringBuilder();
                    for (int i = 0; i < Math.min(files.size(), 20); i++) {
                        fileList.append(files.get(i).getName()).append(", ");
                    }
                    prompt += fileList.toString();
                    jsonPayload = buildTextPayload(prompt);
                }

                if (jsonPayload != null) {
                    return makeApiCall(jsonPayload);
                } else {
                    return null;
                }

            } catch (Exception e) {
                error = "An error occurred during analysis.";
                Log.e(TAG, "Analysis failed", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            progressBar.setVisibility(View.GONE);
            if (result != null) {
                String existingText = resultTextView.getText().toString();
                if (existingText.contains("AI Analysis:")) {
					resultTextView.append("\n\n---\n\n" + result);
                } else {
                    resultTextView.setText("AI Analysis:\n" + result);
                }
                copySummaryButton.setVisibility(View.VISIBLE);
            } else {
                Toast.makeText(context, error != null ? error : "Failed to get AI response.", Toast.LENGTH_LONG).show();
            }
        }

        private String makeApiCall(String jsonPayload) throws IOException {
            OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

            MediaType JSON = MediaType.get("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(jsonPayload, JSON);
            String url = GEMINI_API_ENDPOINT + "?key=" + apiKey;

            Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    error = "API Error: " + response.code() + " " + response.message();
                    Log.e(TAG, "API Error Body: " + (response.body() != null ? response.body().string() : "null"));
                    return null;
                }
                String responseBody = response.body().string();
                return parseGeminiResponse(responseBody);
            }
        }
    }

    private String getFileExtension(File file) {
        String name = file.getName();
        int lastIndexOf = name.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return name.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isImage(String extension) {
        return extension.equals("jpg") || extension.equals("jpeg") || extension.equals("png") || extension.equals("webp");
    }

    private String imageToBase64(File imageFile) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);

            int height = options.outHeight;
            int width = options.outWidth;
            int inSampleSize = 1;
            int reqWidth = 512;
            int reqHeight = 512;

            if (height > reqHeight || width > reqWidth) {
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }
            options.inSampleSize = inSampleSize;
            options.inJustDecodeBounds = false;

            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);
            if (bitmap == null) return null;

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream);
            // ===================================================================
            // === THIS IS THE FIX FOR THE IMAGE ANALYSIS "ERROR 400" ===
            // Changed Base64.DEFAULT to Base64.NO_WRAP to prevent line breaks in the output string.
            return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
            // ===================================================================
        } catch (Exception e) {
            Log.e(TAG, "Error converting image to Base64", e);
            return null;
        }
    }

    private String extractTextFromPdf(File pdfFile) {
        PDDocument document = null;
        try {
            document = PDDocument.load(pdfFile);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(10); // Limit to first 10 pages for performance
            String text = stripper.getText(document);

            if (text.length() > MAX_TEXT_PAYLOAD_SIZE) {
                return text.substring(0, (int) MAX_TEXT_PAYLOAD_SIZE);
            }
            return text;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting text from PDF with PDFBox", e);
            return "Error: Could not read PDF content.";
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing PDF document", e);
                }
            }
        }
    }

    private String readFileContent(File textFile) {
        try {
            StringBuilder text = new StringBuilder();
            FileInputStream fis = new FileInputStream(textFile);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                text.append(new String(buffer, 0, bytesRead));
                if (text.length() > MAX_TEXT_PAYLOAD_SIZE) {
                    break;
                }
            }
            fis.close();

            String result = text.toString();
            if (result.length() > MAX_TEXT_PAYLOAD_SIZE) {
                return result.substring(0, (int) MAX_TEXT_PAYLOAD_SIZE);
            }
            return result;
        } catch (IOException e) {
            Log.e(TAG, "Error reading text file", e);
            return "Error: Could not read file content.";
        }
    }

    private String buildTextPayload(String prompt) throws Exception {
        JSONObject textPart = new JSONObject();
        textPart.put("text", prompt);

        JSONArray partsArray = new JSONArray();
        partsArray.put(textPart);

        JSONObject content = new JSONObject();
        content.put("parts", partsArray);

        JSONArray contentsArray = new JSONArray();
        contentsArray.put(content);

        JSONObject payload = new JSONObject();
        payload.put("contents", contentsArray);

        return payload.toString();
    }

    private String buildImagePayload(String prompt, String base64Image, String mimeType) throws Exception {
        JSONObject textPart = new JSONObject();
        textPart.put("text", prompt);

        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", mimeType);
        inlineData.put("data", base64Image);

        JSONObject imagePart = new JSONObject();
        imagePart.put("inline_data", inlineData);

        JSONArray partsArray = new JSONArray();
        partsArray.put(textPart);
        partsArray.put(imagePart);

        JSONObject content = new JSONObject();
        content.put("parts", partsArray);

        JSONArray contentsArray = new JSONArray();
        contentsArray.put(content);

        JSONObject payload = new JSONObject();
        payload.put("contents", contentsArray);

        return payload.toString();
    }

    private String parseGeminiResponse(String jsonResponse) {
        try {
            JSONObject responseObj = new JSONObject(jsonResponse);
            JSONArray candidates = responseObj.getJSONArray("candidates");
            if (candidates.length() > 0) {
                JSONObject firstCandidate = candidates.getJSONObject(0);
                JSONObject content = firstCandidate.getJSONObject("content");
                JSONArray parts = content.getJSONArray("parts");
                if (parts.length() > 0) {
                    JSONObject firstPart = parts.getJSONObject(0);
                    return firstPart.getString("text");
                }
            }
            return "No text found in response.";
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Gemini response", e);
            return "Error: Could not parse AI response.";
        }
    }
}