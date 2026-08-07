package com.vineyard.hfm.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class AppPickerAdapter extends RecyclerView.Adapter<AppPickerAdapter.AppViewHolder> {

    private final Context context;
    private final PackageManager packageManager;
    private List<AppItem> appList;
    private final OnItemClickListener itemClickListener;

    public interface OnItemClickListener {
        void onSelectionChanged();
    }

    public AppPickerAdapter(Context context, List<ApplicationInfo> apps, OnItemClickListener itemClickListener) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.itemClickListener = itemClickListener;
        this.appList = new ArrayList<>();
        for (ApplicationInfo appInfo : apps) {
            this.appList.add(new AppItem(appInfo));
        }
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.grid_item_app_picker, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final AppViewHolder holder, int position) {
        final AppItem item = appList.get(position);
        final ApplicationInfo appInfo = item.getAppInfo();

        // Set the app icon and name
        holder.appIcon.setImageDrawable(packageManager.getApplicationIcon(appInfo));
        holder.appName.setText(packageManager.getApplicationLabel(appInfo));

        // Update the selection state of the views
        holder.selectionOverlay.setVisibility(item.isSelected() ? View.VISIBLE : View.GONE);

        // Remove previous listener to prevent unwanted calls during binding
        holder.selectionCheckbox.setOnCheckedChangeListener(null);
        holder.selectionCheckbox.setChecked(item.isSelected());

        // Set the listener to handle user interaction on the checkbox
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

        // Set a click listener for the entire item to toggle the checkbox
        holder.itemView.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					holder.selectionCheckbox.setChecked(!holder.selectionCheckbox.isChecked());
				}
			});
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    public List<AppItem> getItems() {
        return appList;
    }

    public static class AppViewHolder extends RecyclerView.ViewHolder {
        ImageView appIcon;
        TextView appName;
        View selectionOverlay;
        CheckBox selectionCheckbox;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.app_icon_picker);
            appName = itemView.findViewById(R.id.app_name_picker);
            selectionOverlay = itemView.findViewById(R.id.selection_overlay_app);
            selectionCheckbox = itemView.findViewById(R.id.selection_checkbox_app);
        }
    }

    // Data model to hold ApplicationInfo and its selection state
    public static class AppItem {
        private ApplicationInfo appInfo;
        private boolean isSelected;

        public AppItem(ApplicationInfo appInfo) {
            this.appInfo = appInfo;
            this.isSelected = false;
        }

        public ApplicationInfo getAppInfo() {
            return appInfo;
        }

        public boolean isSelected() {
            return isSelected;
        }

        public void setSelected(boolean selected) {
            isSelected = selected;
        }
    }
}

