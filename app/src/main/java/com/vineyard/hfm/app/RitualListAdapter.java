package com.vineyard.hfm.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Locale;

public class RitualListAdapter extends RecyclerView.Adapter<RitualListAdapter.RitualViewHolder> {

    private final Context context;
    private final List<RitualManager.Ritual> ritualList;
    private final OnRitualClickListener listener;

    public interface OnRitualClickListener {
        void onRitualClick(int position);
    }

    public RitualListAdapter(Context context, List<RitualManager.Ritual> ritualList, OnRitualClickListener listener) {
        this.context = context;
        this.ritualList = ritualList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public RitualViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.list_item_ritual, parent, false);
        return new RitualViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RitualViewHolder holder, final int position) {
        RitualManager.Ritual ritual = ritualList.get(position);

        // Set the name of the ritual (e.g., "Ritual #1", "Ritual #2")
        holder.ritualName.setText(String.format(Locale.US, "Ritual #%d", position + 1));

        // Set the number of files hidden in this ritual
        int fileCount = 0;
        if (ritual.hiddenFiles != null) {
            fileCount = ritual.hiddenFiles.size();
        }
        holder.fileCount.setText(String.format(Locale.US, "(%d files)", fileCount));

        holder.itemView.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (listener != null) {
						listener.onRitualClick(position);
					}
				}
			});
    }

    @Override
    public int getItemCount() {
        return ritualList.size();
    }

    public static class RitualViewHolder extends RecyclerView.ViewHolder {
        ImageView ritualIcon;
        TextView ritualName;
        TextView fileCount;

        public RitualViewHolder(@NonNull View itemView) {
            super(itemView);
            ritualIcon = itemView.findViewById(R.id.ritual_icon);
            ritualName = itemView.findViewById(R.id.ritual_name);
            fileCount = itemView.findViewById(R.id.ritual_file_count);
        }
    }
}

