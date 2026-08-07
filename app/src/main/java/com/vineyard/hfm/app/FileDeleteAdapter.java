package com.vineyard.hfm.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileDeleteAdapter extends RecyclerView.Adapter<FileDeleteAdapter.FileViewHolder> {

    private final Context context;
    private List<FileItem> fileList;
    private final OnItemClickListener itemClickListener;

    public interface OnItemClickListener {
        void onItemClick(int position);
        void onItemLongClick(int position);
        void onSelectionChanged();
    }

    public FileDeleteAdapter(Context context, List<File> files, OnItemClickListener itemClickListener) {
        this.context = context;
        this.itemClickListener = itemClickListener;
        this.fileList = new ArrayList<>();
        for (File file : files) {
            this.fileList.add(new FileItem(file));
        }
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.grid_item_file_delete, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final FileViewHolder holder, final int position) {
        final FileItem item = fileList.get(position);
        final File file = item.getFile();

        holder.fileName.setText(file.getName());
        holder.selectionOverlay.setVisibility(item.isSelected() ? View.VISIBLE : View.GONE);

        // Remove previous listener to prevent unwanted calls during binding
        holder.selectionCheckbox.setOnCheckedChangeListener(null);
        holder.selectionCheckbox.setChecked(item.isSelected());

        holder.selectionCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                item.setSelected(isChecked);
                holder.selectionOverlay.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (itemClickListener != null) {
                    itemClickListener.onSelectionChanged();
                }
            }
        });

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (itemClickListener != null) {
                    itemClickListener.onItemClick(holder.getAdapterPosition());
                }
            }
        });

        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (itemClickListener != null) {
                    itemClickListener.onItemLongClick(holder.getAdapterPosition());
                }
                return true; // Consume the long click
            }
        });

        // GLIDE INTEGRATION: Replaces manual threading
        int fallbackIcon = getIconForFileType(file.getName());
        
        Glide.with(context)
            .load(file)
            .apply(new RequestOptions()
                .placeholder(fallbackIcon)
                .error(fallbackIcon)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop())
            .into(holder.thumbnailImage);
    }

    private int getIconForFileType(String fileName) {
        String lowerFileName = fileName.toLowerCase();
        // UPDATED: Replaced system icons with new Google Fonts professional icons
        if (lowerFileName.endsWith(".doc") || lowerFileName.endsWith(".docx") || lowerFileName.endsWith(".pdf")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".xls") || lowerFileName.endsWith(".xlsx")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".ppt") || lowerFileName.endsWith(".pptx")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".txt") || lowerFileName.endsWith(".rtf") || lowerFileName.endsWith(".log")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".html") || lowerFileName.endsWith(".xml") || lowerFileName.endsWith(".js") || lowerFileName.endsWith(".css") || lowerFileName.endsWith(".java") || lowerFileName.endsWith(".py") || lowerFileName.endsWith(".c") || lowerFileName.endsWith(".cpp")) return R.drawable.code_24px;
        if (lowerFileName.endsWith(".zip") || lowerFileName.endsWith(".rar") || lowerFileName.endsWith(".7z")) return R.drawable.category_24px;
        if (lowerFileName.endsWith(".mp3") || lowerFileName.endsWith(".wav") || lowerFileName.endsWith(".ogg")) return R.drawable.audio_file_24px;
        
        return R.drawable.category_24px;
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    public List<FileItem> getItems() {
        return fileList;
    }

    public void selectAll(boolean select) {
        for (FileItem item : fileList) {
            item.setSelected(select);
        }
        notifyDataSetChanged();
        if (itemClickListener != null) {
            itemClickListener.onSelectionChanged();
        }
    }

    public void removeItem(int position) {
        fileList.remove(position);
        notifyItemRemoved(position);
    }

    public static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnailImage;
        TextView fileName;
        View selectionOverlay;
        CheckBox selectionCheckbox;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailImage = itemView.findViewById(R.id.thumbnail_image_delete);
            fileName = itemView.findViewById(R.id.file_name_delete);
            selectionOverlay = itemView.findViewById(R.id.selection_overlay);
            selectionCheckbox = itemView.findViewById(R.id.selection_checkbox);
        }
    }

    public static class FileItem {
        private File file;
        private boolean isSelected;

        public FileItem(File file) {
            this.file = file;
            this.isSelected = false;
        }

        public File getFile() {
            return file;
        }

        public boolean isSelected() {
            return isSelected;
        }

        public void setSelected(boolean selected) {
            isSelected = selected;
        }
    }
}