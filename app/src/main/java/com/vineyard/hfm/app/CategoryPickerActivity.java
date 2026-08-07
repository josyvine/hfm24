package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.cardview.widget.CardView;

import java.util.ArrayList;

public class CategoryPickerActivity extends Activity {

    // Request code for starting picker activities
    private static final int PICKER_REQUEST_CODE = 201;

    // Constants to identify which category was selected
    public static final String EXTRA_CATEGORY_TYPE = "category_type";
    public static final String CATEGORY_VIDEOS = "videos";
    public static final String CATEGORY_IMAGES = "images";
    public static final String CATEGORY_APPS = "apps";
    public static final String CATEGORY_DOCUMENTS = "documents";
    public static final String CATEGORY_AUDIO = "audio";

    // UI Elements
    private ImageButton backButton;
    private CardView videosCard, imagesCard, appsCard, documentsCard, audioCard;
    private Button browseFilesButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category_picker);

        initializeViews();
        setupListeners();
    }

    private void initializeViews() {
        backButton = findViewById(R.id.back_button_category_picker);
        videosCard = findViewById(R.id.card_videos);
        imagesCard = findViewById(R.id.card_images);
        appsCard = findViewById(R.id.card_apps);
        documentsCard = findViewById(R.id.card_documents);
        audioCard = findViewById(R.id.card_audio);
        browseFilesButton = findViewById(R.id.button_browse_files);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        videosCard.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// We will create MediaPickerActivity next
					Intent intent = new Intent(CategoryPickerActivity.this, MediaPickerActivity.class);
					intent.putExtra(EXTRA_CATEGORY_TYPE, CATEGORY_VIDEOS);
					startActivityForResult(intent, PICKER_REQUEST_CODE);
				}
			});

        imagesCard.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// Reuses the same MediaPickerActivity for images
					Intent intent = new Intent(CategoryPickerActivity.this, MediaPickerActivity.class);
					intent.putExtra(EXTRA_CATEGORY_TYPE, CATEGORY_IMAGES);
					startActivityForResult(intent, PICKER_REQUEST_CODE);
				}
			});

        audioCard.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// Reuses the same MediaPickerActivity for audio
					Intent intent = new Intent(CategoryPickerActivity.this, MediaPickerActivity.class);
					intent.putExtra(EXTRA_CATEGORY_TYPE, CATEGORY_AUDIO);
					startActivityForResult(intent, PICKER_REQUEST_CODE);
				}
			});

        documentsCard.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// Reuses the same MediaPickerActivity for documents
					Intent intent = new Intent(CategoryPickerActivity.this, MediaPickerActivity.class);
					intent.putExtra(EXTRA_CATEGORY_TYPE, CATEGORY_DOCUMENTS);
					startActivityForResult(intent, PICKER_REQUEST_CODE);
				}
			});

        appsCard.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// We will create a dedicated AppPickerActivity for this
					Intent intent = new Intent(CategoryPickerActivity.this, AppPickerActivity.class);
					startActivityForResult(intent, PICKER_REQUEST_CODE);
				}
			});

        browseFilesButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// This will be a brand new, powerful file browser. We will create it later.
					Intent intent = new Intent(CategoryPickerActivity.this, AdvancedFilePickerActivity.class);
					startActivityForResult(intent, PICKER_REQUEST_CODE);
				}
			});
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICKER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.hasExtra("picked_files")) {
                ArrayList<String> selectedPaths = data.getStringArrayListExtra("picked_files");

                // Pass the result back to the activity that started this one (ShareHubActivity)
                Intent resultIntent = new Intent();
                resultIntent.putStringArrayListExtra("picked_files", selectedPaths);
                setResult(Activity.RESULT_OK, resultIntent);
                finish();
            }
        }
    }
}

