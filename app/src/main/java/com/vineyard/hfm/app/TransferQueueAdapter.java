package com.vineyard.hfm.app;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TransferQueueAdapter extends RecyclerView.Adapter<TransferQueueAdapter.ViewHolder> {

    private final Context context;
    private final List<QueueItem> queueItems;

    // Data class to hold state for each item in the queue
    public static class QueueItem {
        String fileName;
        String status;
        long bytesTransferred;
        long totalBytes;
        int progress;

        QueueItem(String filePath) {
            this.fileName = new File(filePath).getName();
            this.status = "Pending";
            this.bytesTransferred = 0;
            this.totalBytes = 0; // Will be known when transfer starts
            this.progress = 0;
        }
    }

    public TransferQueueAdapter(Context context, ArrayList<String> filePaths) {
        this.context = context;
        this.queueItems = new ArrayList<>();
        for (String path : filePaths) {
            this.queueItems.add(new QueueItem(path));
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.list_item_transfer_queue, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        QueueItem item = queueItems.get(position);
        holder.fileNameTextView.setText(item.fileName);
        holder.statusTextView.setText(item.status);
        holder.fileProgressBar.setProgress(item.progress);

        if (item.totalBytes > 0) {
            String transferredStr = Formatter.formatFileSize(context, item.bytesTransferred);
            String totalStr = Formatter.formatFileSize(context, item.totalBytes);
            holder.progressDetailsTextView.setText(String.format(Locale.US, "%s / %s", transferredStr, totalStr));
            holder.progressDetailsTextView.setVisibility(View.VISIBLE);
        } else {
            holder.progressDetailsTextView.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return queueItems.size();
    }

    public void updateFileProgress(int fileIndex, long transferred, long total) {
        if (fileIndex >= 0 && fileIndex < queueItems.size()) {
            QueueItem item = queueItems.get(fileIndex);
            item.bytesTransferred = transferred;
            item.totalBytes = total;
            item.status = "Sending...";
            if (total > 0) {
                item.progress = (int) ((transferred * 100) / total);
            }
            if (transferred == total && total > 0) {
                item.status = "Complete";
            }
            notifyItemChanged(fileIndex);
        }
    }

    public void setFileStatus(int fileIndex, String status) {
        if (fileIndex >= 0 && fileIndex < queueItems.size()) {
            QueueItem item = queueItems.get(fileIndex);
            item.status = status;
            notifyItemChanged(fileIndex);
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView fileNameTextView;
        TextView statusTextView;
        ProgressBar fileProgressBar;
        TextView progressDetailsTextView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            fileNameTextView = itemView.findViewById(R.id.file_name_text_view);
            statusTextView = itemView.findViewById(R.id.status_text_view);
            fileProgressBar = itemView.findViewById(R.id.file_progress_bar);
            progressDetailsTextView = itemView.findViewById(R.id.progress_details_text_view);
        }
    }
}

