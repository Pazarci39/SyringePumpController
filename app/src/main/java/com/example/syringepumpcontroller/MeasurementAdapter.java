package com.example.syringepumpcontroller;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MeasurementAdapter extends RecyclerView.Adapter<MeasurementAdapter.MeasurementViewHolder> {

    private Context context;
    private List<Measurement> measurementList;
    private OnItemClickListener listener;

    // Öğe tıklama için interface
    public interface OnItemClickListener {
        void onItemClick(int position);
        void onDeleteClick(int position);
        void onViewGraphClick(int position); // Grafik görüntüleme için yeni callback
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public MeasurementAdapter(Context context, List<Measurement> measurementList) {
        this.context = context;
        this.measurementList = measurementList;
    }

    @NonNull
    @Override
    public MeasurementViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_measurement, parent, false);
        return new MeasurementViewHolder(view, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull MeasurementViewHolder holder, int position) {
        Measurement measurement = measurementList.get(position);

        holder.textDateTime.setText(measurement.getDateTime());
        holder.textFlowRate.setText(String.format("%.2f mL/h", measurement.getFlowRate()));
        holder.textVolume.setText(String.format("%.2f mL", measurement.getVolume()));
        holder.textDuration.setText(measurement.getDuration());

        if (measurement.getNotes() != null && !measurement.getNotes().isEmpty()) {
            holder.textNotes.setVisibility(View.VISIBLE);
            holder.textNotes.setText(measurement.getNotes());
        } else {
            holder.textNotes.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return measurementList.size();
    }

    public void updateData(List<Measurement> newMeasurements) {
        this.measurementList = newMeasurements;
        notifyDataSetChanged();
    }

    public Measurement getMeasurementAt(int position) {
        return measurementList.get(position);
    }

    public static class MeasurementViewHolder extends RecyclerView.ViewHolder {
        TextView textDateTime, textFlowRate, textVolume, textDuration, textNotes;
        TextView btnDelete, btnViewGraph;
        CardView cardView;

        public MeasurementViewHolder(@NonNull View itemView, final OnItemClickListener listener) {
            super(itemView);

            cardView = itemView.findViewById(R.id.cardViewMeasurement);
            textDateTime = itemView.findViewById(R.id.textDateTime);
            textFlowRate = itemView.findViewById(R.id.textFlowRate);
            textVolume = itemView.findViewById(R.id.textVolume);
            textDuration = itemView.findViewById(R.id.textDuration);
            textNotes = itemView.findViewById(R.id.textNotes);
            btnDelete = itemView.findViewById(R.id.btnDelete);
            btnViewGraph = itemView.findViewById(R.id.btnViewGraph);

            // Öğeye tıklama olayı
            cardView.setOnClickListener(v -> {
                if (listener != null) {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onItemClick(position);
                    }
                }
            });

            // Silme düğmesine tıklama olayı
            btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onDeleteClick(position);
                    }
                }
            });

            // Grafik görüntüleme düğmesine tıklama olayı
            btnViewGraph.setOnClickListener(v -> {
                if (listener != null) {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onViewGraphClick(position);
                    }
                }
            });
        }
    }
}