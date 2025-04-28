package com.example.syringepumpcontroller;

import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class GraphActivity extends AppCompatActivity {

    private LineChart chart;
    private TextView tvMeasurementInfo;
    private int measurementId = -1;
    private String dateTime = "";
    private double flowRate = 0;
    private double volume = 0;
    private String duration = "";
    private String notes = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ekranı yatay moduna zorla
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_graph);

        // Toolbar'ı ayarla
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Ölçüm Grafiği");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // UI bileşenlerini başlat
        chart = findViewById(R.id.lineChart);
        tvMeasurementInfo = findViewById(R.id.tvMeasurementInfo);

        // Intent'ten verileri al
        if (getIntent().hasExtra("MEASUREMENT_ID")) {
            measurementId = getIntent().getIntExtra("MEASUREMENT_ID", -1);
            dateTime = getIntent().getStringExtra("MEASUREMENT_DATETIME");
            flowRate = getIntent().getDoubleExtra("MEASUREMENT_FLOW_RATE", 0);
            volume = getIntent().getDoubleExtra("MEASUREMENT_VOLUME", 0);
            duration = getIntent().getStringExtra("MEASUREMENT_DURATION");
            notes = getIntent().getStringExtra("MEASUREMENT_NOTES");

            // Ölçüm bilgilerini göster
            showMeasurementInfo();

            // Grafiği hazırla
            setupChart();
        }
    }

    private void showMeasurementInfo() {
        String infoText = String.format(Locale.getDefault(),
                "Tarih: %s | Akış Hızı: %.2f mL/h | Hacim: %.2f mL | Süre: %s",
                dateTime, flowRate, volume, duration);

        tvMeasurementInfo.setText(infoText);
    }

    private void setupChart() {
        // Grafiği yapılandır
        chart.setDrawGridBackground(false);
        chart.setBackgroundColor(Color.WHITE);
        chart.setDrawBorders(true);

        // Açıklama ekle
        Description description = new Description();
        description.setText("Zaman (saniye) - Hacim (mL)");
        description.setTextSize(12f);
        chart.setDescription(description);

        // X ekseni (zaman) ayarları
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(15f); // 15 saniyelik aralıklar
        xAxis.setLabelRotationAngle(0);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.getDefault(), "%.0fs", value);
            }
        });

        // Y ekseni (hacim) ayarları
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setGranularity(0.1f);
        leftAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.getDefault(), "%.1f mL", value);
            }
        });

        chart.getAxisRight().setEnabled(false); // Sağ ekseni devre dışı bırak

        // Veri noktaları oluştur (simüle edilen veriler)
        createAndSetChartData();

        // Animasyon ekle
        chart.animateX(1000);
    }

    private void createAndSetChartData() {
        // Simüle edilmiş veri noktaları oluştur
        List<Entry> entries = new ArrayList<>();

        // Süreyi saniyeye çevir
        int durationInSeconds = parseDurationToSeconds(duration);
        if (durationInSeconds <= 0) durationInSeconds = 300; // Varsayılan 5 dakika

        // Akış hızını ve hacmi kullanarak gerçekçi veri noktaları oluştur
        // Formül: Hacim = Akış Hızı * (Süre / 3600) [mL/saat'i mL/saniye'ye çevirmek için]
        double flowRatePerSecond = flowRate / 3600.0;

        Random random = new Random();
        double currentVolume = 0;

        // 15 saniyelik aralıklarla veri noktaları oluştur
        for (int time = 0; time <= durationInSeconds; time += 15) {
            // Gerçek hesaplanan hacim
            double calculatedVolume = flowRatePerSecond * time;

            // Gerçekçi olmak için küçük rastgele sapmalar ekle (%2 sapma)
            double randomFactor = 1.0 + (random.nextDouble() * 0.04 - 0.02);
            currentVolume = calculatedVolume * randomFactor;

            // Toplam hacmi geçmemesini sağla
            if (currentVolume > volume) {
                currentVolume = volume;
            }

            entries.add(new Entry(time, (float)currentVolume));
        }

        // Son noktayı ekle
        entries.add(new Entry(durationInSeconds, (float)volume));

        // Veri setini oluştur ve özelleştir
        LineDataSet dataSet = new LineDataSet(entries, "Hacim (mL)");
        dataSet.setColor(Color.BLUE);
        dataSet.setCircleColor(Color.BLUE);
        dataSet.setCircleRadius(3f);
        dataSet.setDrawCircleHole(false);
        dataSet.setLineWidth(2f);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        // Veriyi grafiğe ayarla
        LineData lineData = new LineData(dataSet);
        chart.setData(lineData);
        chart.invalidate(); // Grafiği yenile
    }

    // Süre formatını (HH:mm:ss) saniyelere çevirme
    private int parseDurationToSeconds(String durationStr) {
        try {
            String[] parts = durationStr.split(":");
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = Integer.parseInt(parts[2]);

            return hours * 3600 + minutes * 60 + seconds;
        } catch (Exception e) {
            e.printStackTrace();
            return 300; // Hata durumunda varsayılan 5 dakika
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}