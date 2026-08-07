package com.vineyard.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Locale;

public class TransferProgressActivity extends Activity {

    // --- Views for Single File (Receiver) ---
    private LinearLayout singleFileProgressView;
    private TextView fileNameTextView;
    private TextView progressTextView;
    private TextView speedTextView;
    private ProgressBar progressBar;

    // --- Views for File Queue (Sender) ---
    private RecyclerView queueRecyclerView;
    private TransferQueueAdapter queueAdapter;

    // --- Common Views ---
    private TextView transferStatusTitle;
    private Button pauseResumeButton;
    private Button cancelButton;

    private BroadcastReceiver progressReceiver;

    private boolean isPaused = false;
    private boolean isTransferComplete = false;
    private boolean isSender = false; // Flag to determine view mode
    private ArrayList<String> filePaths;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer_progress);

        initializeViews();

        filePaths = getIntent().getStringArrayListExtra(FileTransferService.EXTRA_FILE_PATHS);
        // If filePaths are present, this is the sender.
        if (filePaths != null && !filePaths.isEmpty()) {
            isSender = true;
        } else {
            filePaths = new ArrayList<>(); // For receiver, start with an empty list
        }

        setupViewsBasedOnRole();
        setupListeners();
        setupBroadcastReceiver();
    }

    private void initializeViews() {
        // Single File Views
        singleFileProgressView = findViewById(R.id.single_file_progress_view);
        fileNameTextView = findViewById(R.id.file_name_progress);
        progressTextView = findViewById(R.id.progress_text);
        speedTextView = findViewById(R.id.speed_text);
        progressBar = findViewById(R.id.progress_bar);

        // Multi File Queue View
        queueRecyclerView = findViewById(R.id.queue_recycler_view);

        // Common Views
        transferStatusTitle = findViewById(R.id.transfer_status_title);
        pauseResumeButton = findViewById(R.id.button_pause_resume);
        cancelButton = findViewById(R.id.button_cancel);
    }

    private void setupViewsBasedOnRole() {
        if (isSender) {
            // SENDER MODE: Show the queue
            singleFileProgressView.setVisibility(View.GONE);
            queueRecyclerView.setVisibility(View.VISIBLE);
            transferStatusTitle.setText("Sending Files...");

            queueAdapter = new TransferQueueAdapter(this, filePaths);
            queueRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            queueRecyclerView.setAdapter(queueAdapter);
        } else {
            // RECEIVER MODE: Show the single file progress
            singleFileProgressView.setVisibility(View.VISIBLE);
            queueRecyclerView.setVisibility(View.GONE);
            transferStatusTitle.setText("Receiving Files...");
        }
    }


    private void setupListeners() {
        pauseResumeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					isPaused = !isPaused;
					if (isPaused) {
						pauseResumeButton.setText("Resume");
						sendControlCommand(FileTransferService.ACTION_PAUSE_TRANSFER);
					} else {
						pauseResumeButton.setText("Pause");
						sendControlCommand(FileTransferService.ACTION_RESUME_TRANSFER);
					}
				}
			});

        cancelButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					handleDisconnect();
				}
			});
    }

    private void sendControlCommand(String action) {
        Intent intent = new Intent(this, FileTransferService.class);
        intent.setAction(action);
        startService(intent);
    }

    private void handleDisconnect() {
        if (!isTransferComplete) {
            sendControlCommand(FileTransferService.ACTION_CANCEL_TRANSFER);
        }
        // This broadcast is the key to fixing the loop bug.
        // It will be received by ShareHubActivity.
        Intent intent = new Intent(ShareHubActivity.ACTION_DISCONNECT_WIFI_P2P);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        finish();
    }

    // --- FIX: Create a helper method to show the detailed error dialog ---
    private void showErrorDialog(String errorReport) {
        // Ensure the activity is still running before showing a dialog
        if (isFinishing() || isDestroyed()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Transfer Failed: Error Report");
        builder.setMessage(errorReport);
        builder.setPositiveButton("Close", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}
			});

        AlertDialog dialog = builder.create();
        dialog.show();
    }


    private void setupBroadcastReceiver() {
        progressReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }

                if (FileTransferService.ACTION_UPDATE_PROGRESS.equals(action)) {
                    long transferred = intent.getLongExtra(FileTransferService.EXTRA_BYTES_TRANSFERRED, 0);
                    long total = intent.getLongExtra(FileTransferService.EXTRA_TOTAL_BYTES, 0);
                    double speed = intent.getDoubleExtra(FileTransferService.EXTRA_TRANSFER_SPEED, 0.0);
                    String fileName = intent.getStringExtra(FileTransferService.EXTRA_FILE_NAME);

                    if (isSender) {
                        int fileIndex = intent.getIntExtra(FileTransferService.EXTRA_FILE_INDEX, -1);
                        if (fileIndex != -1) {
                            queueAdapter.updateFileProgress(fileIndex, transferred, total);
                        }
                    } else {
                        // Receiver updates the single file view
                        fileNameTextView.setText(fileName);
                        String transferredStr = Formatter.formatFileSize(context, transferred);
                        String totalStr = Formatter.formatFileSize(context, total);
                        progressTextView.setText(String.format(Locale.US, "%s / %s", transferredStr, totalStr));
                        speedTextView.setText(String.format(Locale.US, "%.2f MB/s", speed));
                        if (total > 0) {
                            progressBar.setProgress((int) ((transferred * 100) / total));
                        }
                    }
                } else if (FileTransferService.ACTION_TRANSFER_COMPLETE.equals(action)) {
                    isTransferComplete = true;
                    pauseResumeButton.setVisibility(View.GONE);
                    cancelButton.setText("Done");
                    transferStatusTitle.setText("Transfer Complete");
                    Toast.makeText(context, "All files transferred.", Toast.LENGTH_SHORT).show();

                } else if (FileTransferService.ACTION_TRANSFER_ERROR.equals(action)) {
                    isTransferComplete = true;
                    pauseResumeButton.setVisibility(View.GONE);
                    cancelButton.setText("Done");
                    transferStatusTitle.setText("Transfer Failed");

                    // --- FIX: Get the error report and show the dialog ---
                    String errorReport = intent.getStringExtra(FileTransferService.EXTRA_ERROR_MESSAGE);
                    if (errorReport != null && !errorReport.isEmpty()) {
                        showErrorDialog(errorReport);
                    } else {
                        Toast.makeText(context, "An unknown transfer error occurred.", Toast.LENGTH_LONG).show();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(FileTransferService.ACTION_UPDATE_PROGRESS);
        filter.addAction(FileTransferService.ACTION_TRANSFER_COMPLETE);
        filter.addAction(FileTransferService.ACTION_TRANSFER_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (progressReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
        }
    }

    @Override
    public void onBackPressed() {
        handleDisconnect();
        super.onBackPressed();
    }
}

