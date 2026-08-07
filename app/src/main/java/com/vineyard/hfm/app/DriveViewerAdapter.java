package com.vineyard.hfm.app;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.api.services.drive.model.File;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DriveViewerAdapter extends RecyclerView.Adapter<DriveViewerAdapter.DriveViewHolder> {

    private final Context context;
    private final List<File> fileList;
    private final OnDriveItemClickListener listener;

    // Interface to pass click events back to the Activity
    public interface OnDriveItemClickListener {
        void onDriveItemClick(File driveFile);
    }

    public DriveViewerAdapter(Context context, List<File> fileList, OnDriveItemClickListener listener) {
        this.context = context;
        this.fileList = fileList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public DriveViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // We will inflate a new custom layout for this specific view
        View view = LayoutInflater.from(context).inflate(R.layout.item_drive_file, parent, false);
        return new DriveViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DriveViewHolder holder, int position) {
        final File driveFile = fileList.get(position);
        boolean isFolder = "application/vnd.google-apps.folder".equals(driveFile.getMimeType());

        // Set the name
        holder.fileName.setText(driveFile.getName());

        // Set the icon based on whether it is a folder or a shard/file
        if (isFolder) {
            // Using standard Android folder icon
            holder.fileIcon.setImageResource(android.R.drawable.ic_menu_myplaces);
            holder.fileDetails.setText("Google Drive Folder");
        } else {
            // Using standard Android file info icon for the shards/decoys
            holder.fileIcon.setImageResource(android.R.drawable.ic_menu_info_details);
            
            // Format size and date for files
            String details = "";
            if (driveFile.getSize() != null) {
                details += Formatter.formatFileSize(context, driveFile.getSize());
            } else {
                details += "Unknown Size";
            }
            
            if (driveFile.getModifiedTime() != null) {
                long timeMillis = driveFile.getModifiedTime().getValue();
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
                details += "  •  " + sdf.format(new Date(timeMillis));
            }
            
            holder.fileDetails.setText(details);
        }

        // Handle clicks (Navigation into folders or warning for files)
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onDriveItemClick(driveFile);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    // ViewHolder class mapping to item_drive_file.xml
    public static class DriveViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName;
        TextView fileDetails;

        public DriveViewHolder(@NonNull View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.drive_file_icon);
            fileName = itemView.findViewById(R.id.drive_file_name);
            fileDetails = itemView.findViewById(R.id.drive_file_details);
        }
    }
}