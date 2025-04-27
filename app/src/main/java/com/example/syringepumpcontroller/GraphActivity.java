package com.example.syringepumpcontroller;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.Random;

public class GraphActivity extends AppCompatActivity {
    private LineChart voltageChart;
    private ArrayList<Entry> voltageEntries;
    private Random random = new Random();
    private int timeCounter = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateDataRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);

        // Toolbar'ı ayarla
        Toolbar toolbar = findViewById(R.id.toolbar_graph);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle("Voltaj Grafiği");
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        // Grafik ayarlarını yap
        voltageChart = findViewById(R.id.voltage_chart);
        setupChart();

        // Örnek veri oluştur
        createSampleData();

        // Simülasyon için veri güncelleme zamanlayıcısını başlat
        startDataUpdates();
    }

    private void setupChart() {
        voltageChart.getDescription().setEnabled(false);
        voltageChart.setTouchEnabled(true);
        voltageChart.setDragEnabled(true);
        voltageChart.setScaleEnabled(true);
        voltageChart.setPinchZoom(true);
        voltageChart.setDrawGridBackground(false);

        // X ekseni ayarları
        XAxis xAxis = voltageChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setGranularity(1f);
        xAxis.setLabelCount(10);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.0fs", value);
            }
        });

        // Sol Y ekseni ayarları
        YAxis leftAxis = voltageChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(5f); // Arduino analog giriş için 0-5V
        leftAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.1fV", value);
            }
        });

        // Sağ Y ekseni gizle
        voltageChart.getAxisRight().setEnabled(false);

        // Gösterge (legend) ayarları
        Legend legend = voltageChart.getLegend();
        legend.setForm(Legend.LegendForm.LINE);
        legend.setTextSize(11f);
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.LEFT);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
    }

    private void createSampleData() {
        voltageEntries = new ArrayList<>();

        // Örnek veri için ilk 10 nokta ekle
        for (int i = 0; i < 10; i++) {
            float value = random.nextFloat() * 4.0f + 0.5f; // 0.5V ile 4.5V arası
            voltageEntries.add(new Entry(i, value));
            timeCounter = i;
        }

        // Veri setini oluştur
        LineDataSet voltageDataSet = new LineDataSet(voltageEntries, "Voltaj (V)");
        voltageDataSet.setColor(Color.BLUE);
        voltageDataSet.setCircleColor(Color.BLUE);
        voltageDataSet.setLineWidth(2f);
        voltageDataSet.setCircleRadius(3f);
        voltageDataSet.setDrawCircleHole(false);
        voltageDataSet.setValueTextSize(9f);
        voltageDataSet.setDrawValues(false);
        voltageDataSet.setHighlightEnabled(true);
        voltageDataSet.setDrawFilled(true);
        voltageDataSet.setFillColor(Color.parseColor("#80BDBDFF"));

        // LineData oluştur ve grafiğe ayarla
        LineData lineData = new LineData(voltageDataSet);
        voltageChart.setData(lineData);

        // Grafiği güncelle
        voltageChart.invalidate();
    }

    private void startDataUpdates() {
        updateDataRunnable = new Runnable() {
            @Override
            public void run() {
                // Yeni veri noktası ekle (gerçekte USB'den alınacak)
                timeCounter++;
                float newValue = random.nextFloat() * 4.0f + 0.5f; // 0.5V ile 4.5V arası
                voltageEntries.add(new Entry(timeCounter, newValue));

                // Veri setini güncelle
                LineDataSet dataSet = (LineDataSet) voltageChart.getData().getDataSetByIndex(0);
                dataSet.notifyDataSetChanged();
                voltageChart.getData().notifyDataChanged();
                voltageChart.notifyDataSetChanged();

                // Grafiğe otomatik kaydırma
                voltageChart.setVisibleXRangeMaximum(20); // Son 20 veri noktasını göster
                voltageChart.moveViewToX(timeCounter - 19); // En sona kaydır

                // 500ms sonra tekrar çalıştır
                handler.postDelayed(this, 500);
            }
        };

        // İlk çalıştırma
        handler.postDelayed(updateDataRunnable, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Activity duraklatıldığında güncellemeyi durdur
        handler.removeCallbacks(updateDataRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Activity devam ettiğinde güncellemeyi yeniden başlat
        handler.postDelayed(updateDataRunnable, 500);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}