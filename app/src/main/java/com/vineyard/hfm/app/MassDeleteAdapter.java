package com.vineyard.hfm.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MassDeleteAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final Context context;
    private List<Object> listItems;
    private final OnItemClickListener itemClickListener;
    private final OnHeaderCheckedChangeListener headerCheckedListener;
    private final OnHeaderClickListener headerClickListener;

    // Executor for PDF/APK manual generation
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(4);

    public interface OnItemClickListener {
        void onItemClick(SearchResult item);
        void onItemLongClick(SearchResult item);
    }

    public interface OnHeaderCheckedChangeListener {
        void onHeaderCheckedChanged(MassDeleteActivity.DateHeader header, boolean isChecked);
    }

    public interface OnHeaderClickListener {
        void onHeaderClick(MassDeleteActivity.DateHeader header);
    }

    public MassDeleteAdapter(Context context, List<Object> listItems,
                             OnItemClickListener itemClickListener,
                             OnHeaderCheckedChangeListener headerCheckedListener,
                             OnHeaderClickListener headerClickListener) {
        this.context = context;
        this.listItems = listItems;
        this.itemClickListener = itemClickListener;
        this.headerCheckedListener = headerCheckedListener;
        this.headerClickListener = headerClickListener;
    }

    public MassDeleteAdapter(Context context, List<Object> listItems, OnItemClickListener itemClickListener) {
        this(context, listItems, itemClickListener, null, null);
    }

    public void updateData(List<Object> newItems) {
        this.listItems = newItems;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (listItems.get(position) instanceof MassDeleteActivity.DateHeader) {
            return TYPE_HEADER;
        } else {
            return TYPE_ITEM;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_date_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.grid_item_search_result, parent, false);
            return new ItemViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, int position) {
        int viewType = getItemViewType(position);

        if (viewType == TYPE_HEADER) {
            HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
            final MassDeleteActivity.DateHeader dateHeader = (MassDeleteActivity.DateHeader) listItems.get(position);

            headerHolder.dateHeaderText.setText(dateHeader.getDateString());

            headerHolder.dateHeaderCheckbox.setOnCheckedChangeListener(null);
            headerHolder.dateHeaderCheckbox.setChecked(dateHeader.isChecked());
            headerHolder.dateHeaderCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (headerCheckedListener != null) {
                        headerCheckedListener.onHeaderCheckedChanged(dateHeader, isChecked);
                    }
                }
            });

            // Expand / Collapse Arrow Rotation
            headerHolder.arrowIcon.setRotation(dateHeader.isExpanded() ? 0f : 180f);
            headerHolder.arrowIcon.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (headerClickListener != null) {
                        headerClickListener.onHeaderClick(dateHeader);
                    }
                }
            });

        } else {
            final ItemViewHolder itemHolder = (ItemViewHolder) holder;
            final SearchResult item = (SearchResult) listItems.get(position);

            itemHolder.indexNumber.setText(String.valueOf(position + 1));
            itemHolder.exclusionOverlay.setVisibility(item.isExcluded() ? View.GONE : View.VISIBLE);
            itemHolder.thumbnailImage.setTag(item.getUri().toString());

            // Determine contrast color based on theme for generic icons
            int contrastColor;
            String currentTheme = ThemeManager.getTheme(context);
            if (currentTheme.equals(ThemeManager.THEME_DARK) || currentTheme.equals(ThemeManager.THEME_AMOLED) || currentTheme.equals(ThemeManager.THEME_NORDIC)) {
                contrastColor = ContextCompat.getColor(context, android.R.color.white);
            } else {
                contrastColor = ContextCompat.getColor(context, R.color.lt_colorPrimary);
            }

            // Display Filename logic
            if (isMediaFile(item.getDisplayName())) {
                itemHolder.fileNameText.setVisibility(View.GONE);
            } else {
                itemHolder.fileNameText.setVisibility(View.VISIBLE);
                itemHolder.fileNameText.setText(item.getDisplayName());
            }

            String displayName = item.getDisplayName();
            int fallbackIcon = getIconForFileType(displayName);

            boolean isPdfOrApk = displayName != null && (displayName.toLowerCase().endsWith(".pdf") || displayName.toLowerCase().endsWith(".apk"));

            if (isPdfOrApk) {
                // Set placeholder
                itemHolder.thumbnailImage.setImageResource(fallbackIcon);
                itemHolder.thumbnailImage.setColorFilter(contrastColor, PorterDuff.Mode.SRC_IN);

                thumbnailExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final Bitmap thumbnail = createSpecialThumbnail(item);
                        if (thumbnail != null && itemHolder.thumbnailImage.getTag().equals(item.getUri().toString())) {
                            itemHolder.thumbnailImage.post(new Runnable() {
                                @Override
                                public void run() {
                                    itemHolder.thumbnailImage.clearColorFilter();
                                    itemHolder.thumbnailImage.setImageBitmap(thumbnail);
                                }
                            });
                        }
                    }
                });
            } else {
                itemHolder.thumbnailImage.clearColorFilter();

                // Use Glide for images and videos
                Glide.with(context)
                    .load(item.getUri())
                    .apply(new RequestOptions()
                        .placeholder(fallbackIcon)
                        .error(fallbackIcon)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop())
                    .into(itemHolder.thumbnailImage);

                if (!isMediaFile(displayName)) {
                    itemHolder.thumbnailImage.setColorFilter(contrastColor, PorterDuff.Mode.SRC_IN);
                }
            }

            // Click listener for item selection toggle
            itemHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (itemClickListener != null) {
                        itemClickListener.onItemClick(item);
                    }
                }
            });

            // Long click listener for pop-up menu (Open, Details, Compress)
            itemHolder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (itemClickListener != null) {
                        itemClickListener.onItemLongClick(item);
                    }
                    return true;
                }
            });
        }
    }

    private boolean isMediaFile(String fileName) {
        if (fileName == null) return false;
        String lowerFileName = fileName.toLowerCase();
        List<String> mediaExtensions = Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
            ".mp4", ".3gp", ".mkv", ".webm", ".avi"
        );

        for (String ext : mediaExtensions) {
            if (lowerFileName.endsWith(ext)) return true;
        }
        return false;
    }

    private int getIconForFileType(String fileName) {
        if (fileName == null) return R.drawable.ic_folder_modern;
        String lower = fileName.toLowerCase();

        if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".pdf")) return R.drawable.docs_24px;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return R.drawable.docs_24px;
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return R.drawable.docs_24px;
        if (lower.endsWith(".txt") || lower.endsWith(".rtf") || lower.endsWith(".log")) return R.drawable.docs_24px;
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) return R.drawable.category_24px;
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")) return R.drawable.audio_file_24px;
        if (isMediaFile(fileName)) return R.drawable.image_24px;

        return R.drawable.ic_folder_modern;
    }

    private Bitmap createSpecialThumbnail(SearchResult item) {
        Uri uri = item.getUri();
        String displayName = item.getDisplayName();
        if (displayName == null) return null;
        String lower = displayName.toLowerCase();

        if (lower.endsWith(".apk")) {
            String path = "file".equals(uri.getScheme()) ? uri.getPath() : item.getPath();
            if (path != null) return getApkIcon(path);
        }
        if (lower.endsWith(".pdf")) {
            return createPdfThumbnail(uri);
        }
        return null;
    }

    private Bitmap getApkIcon(String filePath) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(filePath, 0);
            if (pi != null) {
                ApplicationInfo appInfo = pi.applicationInfo;
                appInfo.sourceDir = filePath;
                appInfo.publicSourceDir = filePath;
                Drawable icon = appInfo.loadIcon(pm);
                return drawableToBitmap(icon);
            }
        } catch (Exception e) {
            Log.e("MassDeleteAdapter", "Could not get APK icon", e);
        }
        return null;
    }

    private Bitmap createPdfThumbnail(Uri uri) {
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;
            renderer = new PdfRenderer(pfd);
            page = renderer.openPage(0);
            Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (Exception e) {
            Log.e("MassDeleteAdapter", "Could not render PDF thumbnail", e);
            return null;
        } finally {
            try {
                if (page != null) page.close();
                if (renderer != null) renderer.close();
                if (pfd != null) pfd.close();
            } catch (IOException ignored) {}
        }
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 96;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 96;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    @Override
    public int getItemCount() {
        return listItems.size();
    }

    public static class ItemViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnailImage;
        TextView indexNumber;
        View exclusionOverlay;
        TextView fileNameText;

        public ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailImage = itemView.findViewById(R.id.thumbnail_image);
            indexNumber = itemView.findViewById(R.id.index_number);
            exclusionOverlay = itemView.findViewById(R.id.exclusion_overlay);
            fileNameText = itemView.findViewById(R.id.file_name_text);
        }
    }

    public static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView dateHeaderText;
        CheckBox dateHeaderCheckbox;
        ImageView arrowIcon;

        public HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            dateHeaderText = itemView.findViewById(R.id.date_header_text);
            dateHeaderCheckbox = itemView.findViewById(R.id.date_header_checkbox);
            arrowIcon = itemView.findViewById(R.id.header_arrow);
        }
    }

    public static class SearchResult {
        private final Uri uri;
        private final long mediaStoreId;
        private final long lastModifiedForGrouping;
        private final String displayName;
        private final String path;
        private boolean isExcluded;

        public SearchResult(Uri uri, long mediaStoreId, long lastModifiedMillis, String displayName, String path) {
            this.uri = uri;
            this.mediaStoreId = mediaStoreId;
            this.lastModifiedForGrouping = lastModifiedMillis;
            this.displayName = displayName;
            this.path = path;
            this.isExcluded = true;
        }

        public SearchResult(Uri uri, long mediaStoreId, String displayName) {
            this(uri, mediaStoreId, System.currentTimeMillis(), displayName, "file".equals(uri.getScheme()) ? uri.getPath() : null);
        }

        public Uri getUri() { return uri; }
        public long getMediaStoreId() { return mediaStoreId; }
        public long getLastModifiedForGrouping() { return lastModifiedForGrouping; }
        public String getDisplayName() { return displayName; }
        public String getPath() { return path; }
        public boolean isExcluded() { return isExcluded; }
        public void setExcluded(boolean excluded) { isExcluded = excluded; }
    }
}