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
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SyringePumpController";
    private static final String ACTION_USB_PERMISSION = "com.example.syringepumpcontroller.USB_PERMISSION";

    // UI Bileşenleri
    private TextView tvConnectionStatus;
    private SeekBar seekBarSpeed;
    private TextView tvSpeedValue;
    private RadioGroup radioGroupDirection;
    private Button btnStart, btnStop;
    private TableLayout tableSensorData;

    // USB İletişim için gerekli değişkenler
    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbSerialDevice serialPort;

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

        // UI bileşenlerini başlat
        initializeUI();

        // USB yöneticisini başlat
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // USB algılama işleyiciyi kaydet
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);

        // Cihazı otomatik olarak algılamaya çalış
        findSerialPortDevice();
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

        for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
            device = entry.getValue();
            // Tüm cihazlar için izin iste - S7 Edge'de daha iyi çalışması için
            Log.d(TAG, "USB cihazı bulundu: " + device.getDeviceName() +
                    " VID: " + device.getVendorId() +
                    " PID: " + device.getProductId());
            deviceFound = true;

            // USB izni iste (PendingIntent mutability flag için)
            int flags;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = PendingIntent.FLAG_UPDATE_CURRENT;
            } else {
                flags = 0;
            }

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, pendingIntent);
            break;
        }

        if (!deviceFound) {
            Log.d(TAG, "Uyumlu bir USB cihazı bulunamadı");
            tvConnectionStatus.setText(getString(R.string.connection_status_no_device));
        }
    }

    // Seri porta bağlan
    private void connectToSerialPort() {
        connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "USB bağlantısı açılamadı");
            return;
        }

        serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);

        if (serialPort == null) {
            Log.e(TAG, "Seri port oluşturulamadı");
            return;
        }

        if (!serialPort.open()) {
            Log.e(TAG, "Seri port açılamadı");
            return;
        }

        // Seri port ayarları
        serialPort.setBaudRate(115200); // ESP32 ile 115200 yaygın kullanılır
        serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
        serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
        serialPort.setParity(UsbSerialInterface.PARITY_NONE);
        serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);

        // Veri alma dinleyicisi
        serialPort.read(mCallback);

        // Bağlantı durumunu güncelle
        tvConnectionStatus.setText(getString(R.string.connection_status_connected));
        tvConnectionStatus.setTextColor(Color.GREEN);

        Log.d(TAG, "Seri port bağlantısı başarılı");

        // Sensor verilerini okumak için zamanlanmış görev başlat
        startSensorDataScheduler();
    }

    // Seri porttan veri alma geri çağrısı
    private final UsbSerialInterface.UsbReadCallback mCallback = data -> {
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
    };

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
        scheduler.scheduleAtFixedRate(() -> {
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
            serialPort.write(command.getBytes());
            Log.d(TAG, "Gönderilen komut: " + command.trim());
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
                if (serialPort != null) {
                    serialPort.close();
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
        }
    };

    @Override
    protected void onDestroy() {
        // Uygulamadan çıkarken kaynakları temizle
        if (serialPort != null) {
            serialPort.close();
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        unregisterReceiver(usbReceiver);
        super.onDestroy();
    }
}