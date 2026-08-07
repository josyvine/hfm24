package com.vineyard.hfm.app;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class RecycleBinAdapter extends RecyclerView.Adapter<RecycleBinAdapter.ViewHolder> {

    private static final int TYPE_LIST = 0;
    private static final int TYPE_GRID = 1;

    private final Context context;
    private final List<File> fileList;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(File file);
        void onItemLongClick(File file);
    }

    public RecycleBinAdapter(Context context, List<File> fileList, OnItemClickListener listener) {
        this.context = context;
        this.fileList = fileList;
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        if (context instanceof RecycleBinActivity) {
            return ((RecycleBinActivity) context).isGridView() ? TYPE_GRID : TYPE_LIST;
        }
        return TYPE_LIST;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId;
        if (viewType == TYPE_GRID) {
            layoutId = R.layout.list_item_recycle_bin_grid;
        } else {
            layoutId = R.layout.list_item_recycle_bin_list;
        }
        View view = LayoutInflater.from(context).inflate(layoutId, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final File file = fileList.get(position);
        holder.fileName.setText(file.getName());

        // Determine contrast color based on theme
        int contrastColor;
        String currentTheme = ThemeManager.getTheme(context);
        if (currentTheme.equals(ThemeManager.THEME_DARK) || currentTheme.equals(ThemeManager.THEME_AMOLED) || currentTheme.equals(ThemeManager.THEME_NORDIC)) {
            contrastColor = ContextCompat.getColor(context, android.R.color.white);
        } else {
            contrastColor = ContextCompat.getColor(context, R.color.lt_colorPrimary);
        }

        // --- HANDLE SELECTION CHECKS AND OVERLAYS ---
        if (context instanceof RecycleBinActivity) {
            RecycleBinActivity activity = (RecycleBinActivity) context;
            boolean isSelMode = activity.isSelectionMode();
            boolean isItemSelected = activity.isFileSelected(file);

            if (isSelMode) {
                if (holder.checkBox != null) {
                    holder.checkBox.setVisibility(View.VISIBLE);
                    if (holder.checkBox instanceof CheckBox) {
                        ((CheckBox) holder.checkBox).setChecked(isItemSelected);
                    }
                }
                if (holder.selectionOverlay != null) {
                    holder.selectionOverlay.setVisibility(isItemSelected ? View.VISIBLE : View.GONE);
                }
            } else {
                if (holder.checkBox != null) {
                    holder.checkBox.setVisibility(View.GONE);
                }
                if (holder.selectionOverlay != null) {
                    holder.selectionOverlay.setVisibility(View.GONE);
                }
            }
        } else {
            if (holder.checkBox != null) {
                holder.checkBox.setVisibility(View.GONE);
            }
            if (holder.selectionOverlay != null) {
                holder.selectionOverlay.setVisibility(View.GONE);
            }
        }

        // --- RENDER THUMBNAILS AND VECTOR GRAPHICS ---
        String name = file.getName();
        int fallbackIcon = getIconForFileType(name);

        if (file.isDirectory()) {
            holder.fileIcon.setImageResource(R.drawable.ic_folder_modern);
            holder.fileIcon.setColorFilter(contrastColor, PorterDuff.Mode.SRC_IN);
        } else if (isMediaFile(name)) {
            // Clear tint to display real image/video preview pixels properly
            holder.fileIcon.clearColorFilter();
            
            Glide.with(context)
                .load(file)
                .apply(new RequestOptions()
                    .placeholder(fallbackIcon)
                    .error(fallbackIcon)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop())
                .into(holder.fileIcon);
        } else {
            holder.fileIcon.setImageResource(fallbackIcon);
            holder.fileIcon.setColorFilter(contrastColor, PorterDuff.Mode.SRC_IN);
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onItemClick(file);
                }
            }
        });

        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) {
                    listener.onItemLongClick(file);
                }
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName;
        View checkBox;
        View selectionOverlay;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.file_icon_picker);
            fileName = itemView.findViewById(R.id.file_name_picker);
            checkBox = itemView.findViewById(R.id.file_checkbox_picker);
            selectionOverlay = itemView.findViewById(R.id.selection_overlay_picker);
        }
    }

    private boolean isMediaFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
               lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp") ||
               lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".mkv") ||
               lower.endsWith(".webm") || lower.endsWith(".avi");
    }

    private int getIconForFileType(String fileName) {
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        if (lowerFileName.endsWith(".doc") || lowerFileName.endsWith(".docx") || lowerFileName.endsWith(".pdf")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".xls") || lowerFileName.endsWith(".xlsx")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".ppt") || lowerFileName.endsWith(".pptx")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".txt") || lowerFileName.endsWith(".rtf") || lowerFileName.endsWith(".log")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".zip") || lowerFileName.endsWith(".rar") || lowerFileName.endsWith(".7z")) return R.drawable.category_24px;
        if (lowerFileName.endsWith(".mp3") || lowerFileName.endsWith(".wav") || lowerFileName.endsWith(".ogg")) return R.drawable.audio_file_24px;
        if (lowerFileName.endsWith(".jpg") || lowerFileName.endsWith(".jpeg") || lowerFileName.endsWith(".png") || lowerFileName.endsWith(".gif")) return R.drawable.image_24px;
        if (lowerFileName.endsWith(".mp4") || lowerFileName.endsWith(".mkv") || lowerFileName.endsWith(".avi")) return R.drawable.video_file_24px;
        return R.drawable.category_24px;
    }
}