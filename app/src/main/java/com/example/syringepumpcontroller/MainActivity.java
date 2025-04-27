package com.example.syringepumpcontroller;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

// hoho kütüphanesinin doğru importları (mik3y)
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.google.android.material.navigation.NavigationView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener, SerialInputOutputManager.Listener {

    private static final String TAG = "SyringePumpController";
    private static final String ACTION_USB_PERMISSION = "com.example.syringepumpcontroller.USB_PERMISSION";

    // UI Bileşenleri
    private TextView tvConnectionStatus;
    private SeekBar seekBarSpeed;
    private TextView tvSpeedValue;
    private RadioGroup radioGroupDirection;
    private Button btnStart, btnStop;
    private TableLayout tableSensorData;

    // Navigation Drawer
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    // USB İletişim için gerekli değişkenler
    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;

    // hoho kütüphanesi değişkenleri
    private UsbSerialPort serialPort;
    private SerialInputOutputManager serialIoManager;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    // Kontrol Değişkenleri
    private boolean isPumpRunning = false;
    private int pumpSpeed = 50;
    private boolean isForwardDirection = true;

    // Veri okuma için zamanlanmış görev
    private ScheduledExecutorService scheduler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Navigation Drawer ve Toolbar kurulumu
        setupNavigation();

        // UI bileşenlerini başlat
        initializeUI();

        // USB yöneticisini başlat
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // USB algılama işleyiciyi kaydet
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        // API seviye kontrolü
        if (Build.VERSION.SDK_INT >= 33) { // TIRAMISU
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        // Cihazı otomatik olarak algılamaya çalış
        findSerialPortDevice();
    }

    private void setupNavigation() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        navigationView.setNavigationItemSelectedListener(this);

        // Başlangıçta Pompa Kontrolü seçeneğini işaretle
        navigationView.setCheckedItem(R.id.nav_pump_control);
    }

    private void initializeUI() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        seekBarSpeed = findViewById(R.id.seekBarSpeed);
        tvSpeedValue = findViewById(R.id.tvSpeedValue);
        radioGroupDirection = findViewById(R.id.radioGroupDirection);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tableSensorData = findViewById(R.id.tableSensorData);

        // Hız değişim olayı
        seekBarSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pumpSpeed = progress;
                tvSpeedValue.setText(getString(R.string.speed_value, progress));

                // Eğer pompa çalışıyorsa, hızı anında güncelle
                if (isPumpRunning && serialPort != null) {
                    sendSpeedCommand(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Yön değişim olayı
        radioGroupDirection.setOnCheckedChangeListener((group, checkedId) -> {
            isForwardDirection = checkedId == R.id.radioForward;

            // Eğer pompa çalışıyorsa, yönü anında güncelle
            if (isPumpRunning && serialPort != null) {
                sendDirectionCommand(isForwardDirection);
            }
        });

        // Başlat butonu olayı
        btnStart.setOnClickListener(v -> {
            if (serialPort == null) {
                Toast.makeText(this, R.string.no_usb_connection, Toast.LENGTH_SHORT).show();
                return;
            }

            startPump();
        });

        // Durdur butonu olayı
        btnStop.setOnClickListener(v -> {
            if (serialPort == null) {
                Toast.makeText(this, R.string.no_usb_connection, Toast.LENGTH_SHORT).show();
                return;
            }

            stopPump();
        });
    }

    // USB cihazını bul
    private void findSerialPortDevice() {
        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();

        if (usbDevices.isEmpty()) {
            Log.d(TAG, "USB cihazı bulunamadı");
            return;
        }

        boolean deviceFound = false;

        // USBSerialProber ile kullanılabilir sürücüleri bul
        UsbSerialProber prober = UsbSerialProber.getDefaultProber();

        for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
            device = entry.getValue();
            // Sürücü kontrolü yap
            UsbSerialDriver driver = prober.probeDevice(device);

            if (driver != null) {
                Log.d(TAG, "USB cihazı bulundu: " + device.getDeviceName() +
                        " VID: " + device.getVendorId() +
                        " PID: " + device.getProductId());
                deviceFound = true;

                // USB izni iste (PendingIntent mutability flag için)
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }

                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        this, 0, new Intent(ACTION_USB_PERMISSION), flags);
                usbManager.requestPermission(device, pendingIntent);
                break;
            }
        }

        if (!deviceFound) {
            Log.d(TAG, "Uyumlu bir USB cihazı bulunamadı");
            tvConnectionStatus.setText(getString(R.string.connection_status_no_device));
        }
    }

    // Seri porta bağlan
    private void connectToSerialPort() {
        // USB bağlantısını aç
        connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "USB bağlantısı açılamadı");
            return;
        }

        // Sürücüyü bul
        UsbSerialProber prober = UsbSerialProber.getDefaultProber();
        UsbSerialDriver driver = prober.probeDevice(device);

        if (driver == null) {
            Log.e(TAG, "Sürücü bulunamadı");
            return;
        }

        // İlk portu al
        if (driver.getPorts().size() < 1) {
            Log.e(TAG, "Seri port bulunamadı");
            return;
        }

        serialPort = driver.getPorts().get(0);

        try {
            serialPort.open(connection);
            serialPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            // Seri port veri alma yöneticisini başlat
            serialIoManager = new SerialInputOutputManager(serialPort, this);
            ioExecutor.submit(serialIoManager);

            // Bağlantı durumunu güncelle
            tvConnectionStatus.setText(getString(R.string.connection_status_connected));
            tvConnectionStatus.setTextColor(Color.GREEN);

            Log.d(TAG, "Seri port bağlantısı başarılı");

            // Sensor verilerini okumak için zamanlanmış görev başlat
            startSensorDataScheduler();

        } catch (IOException e) {
            Log.e(TAG, "Seri port açma hatası", e);
        }
    }

    // SerialInputOutputManager.Listener implementasyonu
    @Override
    public void onNewData(byte[] data) {
        try {
            String dataStr = new String(data, StandardCharsets.UTF_8);
            Log.d(TAG, "Alınan veri: " + dataStr);

            // Gelen veriyi işle
            if (dataStr.startsWith("SV:")) { // Sensor Value
                processAndDisplaySensorData(dataStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Veri çözme hatası", e);
        }
    }

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "Serial I/O manager hatası", e);
        mainHandler.post(() -> {
            tvConnectionStatus.setText(getString(R.string.connection_status_error));
            tvConnectionStatus.setTextColor(Color.RED);
        });
    }

    // Sensor verilerini işle ve tabloda göster
    private void processAndDisplaySensorData(final String data) {
        String voltageStr = data.substring(3).trim(); // "SV:" kısmını çıkar

        try {
            final float voltage = Float.parseFloat(voltageStr);

            // Ana iş parçacığında UI güncellemesi yap
            mainHandler.post(() -> {
                // Mevcut tarih/saat
                String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

                // Yeni tablo satırı oluştur
                TableRow row = new TableRow(MainActivity.this);

                // Saat sütunu
                TextView tvTime = new TextView(MainActivity.this);
                tvTime.setPadding(8, 8, 8, 8);
                tvTime.setText(timestamp);
                row.addView(tvTime);

                // Voltaj sütunu
                TextView tvVoltage = new TextView(MainActivity.this);
                tvVoltage.setPadding(8, 8, 8, 8);
                tvVoltage.setText(String.format(Locale.US, "%.2f", voltage));
                row.addView(tvVoltage);

                // Tabloya ekle
                tableSensorData.addView(row);

                // En fazla 100 satır göster (bellek yönetimi)
                if (tableSensorData.getChildCount() > 100) {
                    tableSensorData.removeViewAt(1); // Başlık satırı hariç ilk veri satırını sil
                }
            });

        } catch (NumberFormatException e) {
            Log.e(TAG, "Voltaj değerini ayrıştırma hatası", e);
        }
    }

    // Sensor verilerini almak için zamanlanmış görev
    private void startSensorDataScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();
        // scheduleWithFixedDelay kullan (tavsiye edilen yöntem)
        scheduler.scheduleWithFixedDelay(() -> {
            if (serialPort != null) {
                // Sensor verisi iste
                sendCommand("GET_SENSOR");
            }
        }, 0, 1, TimeUnit.SECONDS); // Her saniye veri iste
    }

    // Pompayı başlat
    private void startPump() {
        if (!isPumpRunning) {
            // Pompa yönünü ayarla
            sendDirectionCommand(isForwardDirection);

            // Pompa hızını ayarla
            sendSpeedCommand(pumpSpeed);

            // Pompayı başlat
            sendCommand("START");

            isPumpRunning = true;
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        }
    }

    // Pompayı durdur
    private void stopPump() {
        if (isPumpRunning) {
            sendCommand("STOP");

            isPumpRunning = false;
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        }
    }

    // Yön komutu gönder
    private void sendDirectionCommand(boolean isForward) {
        String command = isForward ? "DIR_FWD" : "DIR_REV";
        sendCommand(command);
    }

    // Hız komutu gönder
    private void sendSpeedCommand(int speed) {
        String command = "SPEED_" + speed;
        sendCommand(command);
    }

    // Komut gönder
    private void sendCommand(String command) {
        if (serialPort != null) {
            command += "\n"; // Satır sonu ekle (ESP32 tarafında ayrıştırma için)
            try {
                serialPort.write(command.getBytes(), 1000); // 1000ms timeout
                Log.d(TAG, "Gönderilen komut: " + command.trim());
            } catch (IOException e) {
                Log.e(TAG, "Komut gönderme hatası", e);
            }
        }
    }

    // USB olayları için alıcı
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (usbDevice != null) {
                            // İzin verildi, seri porta bağlan
                            Log.d(TAG, "USB izni verildi. Bağlanıyor...");
                            connectToSerialPort();
                        }
                    } else {
                        Log.d(TAG, "USB izni reddedildi");
                        tvConnectionStatus.setText(getString(R.string.connection_status_permission_denied));
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                // USB cihazı takıldı
                Log.d(TAG, "USB cihazı takıldı");
                findSerialPortDevice();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                // USB cihazı çıkarıldı
                Log.d(TAG, "USB cihazı çıkarıldı");
                disconnect();
            }
        }
    };

    // Bağlantıyı kapat
    private void disconnect() {
        if (serialIoManager != null) {
            serialIoManager.stop();
            serialIoManager = null;
        }

        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (IOException e) {
                Log.e(TAG, "Seri port kapatma hatası", e);
            }
            serialPort = null;
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        isPumpRunning = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);

        tvConnectionStatus.setText(getString(R.string.connection_status_disconnected));
        tvConnectionStatus.setTextColor(Color.RED);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_pump_control) {
            // Zaten bu sayfadayız, bir şey yapmaya gerek yok
        } else if (id == R.id.nav_graph) {
            Intent intent = new Intent(this, GraphActivity.class);
            startActivity(intent);
        } else if (id == R.id.nav_history) {
            Intent intent = new Intent(this, HistoryActivity.class);
            startActivity(intent);
        }

        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        // Uygulamadan çıkarken kaynakları temizle
        disconnect();

        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver zaten kayıtlı değilse
            Log.d(TAG, "USB receiver zaten kayıtlı değil");
        }

        ioExecutor.shutdown();
        super.onDestroy();
    }
}