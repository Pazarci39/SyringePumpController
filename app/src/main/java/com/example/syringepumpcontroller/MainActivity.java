package com.example.syringepumpcontroller;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ImageButton;
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


    // UI Components
    private TextView tvConnectionStatus;
    private SeekBar seekBarSpeed;
    private TextView tvSpeedValue;
    private RadioGroup radioGroupDirection;
    private ImageButton btnStart, btnStop; // Changed from Button to ImageButton
    private TableLayout tableSensorData;
    private EditText editTextNotes;

    // Navigation Drawer
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    // USB Communication variables
    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;

    // hoho library variables
    private UsbSerialPort serialPort;
    private SerialInputOutputManager serialIoManager;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    // Control Variables
    private boolean isPumpRunning = false;
    private int pumpSpeed = 50;
    private boolean isForwardDirection = true;

    // Scheduled task for data reading
    private ScheduledExecutorService scheduler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Database helper
    private DatabaseHelper dbHelper;

    // Measurement timing variables
    private long pumpStartTime = 0;
    private float lastSensorValue = 0;
    private TextView tvDuration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Force landscape orientation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Setup Navigation Drawer and Toolbar
        setupNavigation();

        // Initialize UI components
        initializeUI();

        // Initialize USB manager
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Register USB detection handler
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        // API level check
        if (Build.VERSION.SDK_INT >= 33) { // TIRAMISU
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        // Initialize database helper
        dbHelper = new DatabaseHelper(this);

        // Try to detect device automatically
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

        // Mark Pump Control option at start
        navigationView.setCheckedItem(R.id.nav_pump_control);
    }

    private void initializeUI() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        seekBarSpeed = findViewById(R.id.seekBarSpeed);
        tvSpeedValue = findViewById(R.id.tvSpeedValue);
        radioGroupDirection = findViewById(R.id.radioGroupDirection);

        // Changed from Button to ImageButton
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        tableSensorData = findViewById(R.id.tableSensorData);

        // Find notes field
        editTextNotes = findViewById(R.id.editTextNotes);

        // Find duration TextView
        tvDuration = findViewById(R.id.tvDuration);
        if (tvDuration != null) {
            tvDuration.setText("00:00:00");
        }

        // Speed change event
        seekBarSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pumpSpeed = progress;
                tvSpeedValue.setText(getString(R.string.speed_value, progress));

                // If pump is running, update speed instantly
                if (isPumpRunning && serialPort != null) {
                    sendSpeedCommand(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Direction change event
        radioGroupDirection.setOnCheckedChangeListener((group, checkedId) -> {
            isForwardDirection = checkedId == R.id.radioForward;

            // If pump is running, update direction instantly
            if (isPumpRunning && serialPort != null) {
                sendDirectionCommand(isForwardDirection);
            }
        });

        // Start button event
        btnStart.setOnClickListener(v -> {
            if (serialPort == null) {
                Toast.makeText(this, R.string.no_usb_connection, Toast.LENGTH_SHORT).show();
                return;
            }

            // Record pump start time
            pumpStartTime = SystemClock.elapsedRealtime();

            // Start duration counter
            startDurationCounter();

            startPump();
        });

        // Stop button event
        btnStop.setOnClickListener(v -> {
            if (serialPort == null) {
                Toast.makeText(this, R.string.no_usb_connection, Toast.LENGTH_SHORT).show();
                return;
            }

            stopPump();

            // Stop duration counter
            stopDurationCounter();

            // Save measurement when pump stops
            saveMeasurement();
        });
    }

    // Duration counter Handler and Runnable
    private Handler durationHandler = new Handler(Looper.getMainLooper());
    private Runnable durationRunnable;

    // Start duration counter
    private void startDurationCounter() {
        if (durationRunnable != null) {
            durationHandler.removeCallbacks(durationRunnable);
        }

        durationRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPumpRunning && tvDuration != null) {
                    long elapsedMillis = SystemClock.elapsedRealtime() - pumpStartTime;
                    updateDurationDisplay(elapsedMillis);
                    durationHandler.postDelayed(this, 1000); // Update every second
                }
            }
        };

        durationHandler.post(durationRunnable);
    }

    // Stop duration counter
    private void stopDurationCounter() {
        if (durationRunnable != null) {
            durationHandler.removeCallbacks(durationRunnable);
        }
    }

    // Update duration display
    private void updateDurationDisplay(long elapsedMillis) {
        if (tvDuration != null) {
            int seconds = (int) (elapsedMillis / 1000) % 60;
            int minutes = (int) ((elapsedMillis / (1000 * 60)) % 60);
            int hours = (int) ((elapsedMillis / (1000 * 60 * 60)) % 24);

            String durationStr = String.format(Locale.getDefault(),
                    "%02d:%02d:%02d", hours, minutes, seconds);
            tvDuration.setText(durationStr);
        }
    }

    // Save measurement method
    private void saveMeasurement() {
        if (pumpStartTime == 0) {
            Toast.makeText(this, "Measurement could not be saved: Invalid start time", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get current date and time
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String currentDateTime = sdf.format(new Date());

        // Calculate pump operation time
        long elapsedMillis = SystemClock.elapsedRealtime() - pumpStartTime;
        int seconds = (int) (elapsedMillis / 1000) % 60;
        int minutes = (int) ((elapsedMillis / (1000 * 60)) % 60);
        int hours = (int) ((elapsedMillis / (1000 * 60 * 60)) % 24);

        String duration = String.format(Locale.getDefault(),
                "%02d:%02d:%02d", hours, minutes, seconds);

        // Get notes or use empty string if not available
        String notes = (editTextNotes != null) ? editTextNotes.getText().toString() : "";

        // Get flow rate
        double flowRate = pumpSpeed; // From SeekBar

        // Calculate actual volume (Volume = Flow Rate × Time / 3600)
        // Note: Flow rate in ml/hour, time in seconds
        double totalTimeInHours = elapsedMillis / (1000.0 * 60.0 * 60.0); // convert milliseconds to hours
        double volume = flowRate * totalTimeInHours; // volume in ml

        // Create measurement object
        Measurement measurement = new Measurement(
                currentDateTime,
                flowRate,
                volume,
                duration,
                notes
        );

        // Save to database
        long id = dbHelper.addMeasurement(measurement);

        if (id != -1) {
            Toast.makeText(this, "Measurement saved successfully!", Toast.LENGTH_SHORT).show();

            // Clear notes field if available
            if (editTextNotes != null) {
                editTextNotes.setText("");
            }

            // Reset duration display
            if (tvDuration != null) {
                tvDuration.setText("00:00:00");
            }

            // Reset start time
            pumpStartTime = 0;
        } else {
            Toast.makeText(this, "Error saving measurement!", Toast.LENGTH_SHORT).show();
        }
    }

    // Find USB device
    private void findSerialPortDevice() {
        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();

        if (usbDevices.isEmpty()) {
            Log.d(TAG, "No USB device found");
            return;
        }

        boolean deviceFound = false;

        // Find available drivers with USBSerialProber
        UsbSerialProber prober = UsbSerialProber.getDefaultProber();

        for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
            device = entry.getValue();
            // Check driver
            UsbSerialDriver driver = prober.probeDevice(device);

            if (driver != null) {
                Log.d(TAG, "USB device found: " + device.getDeviceName() +
                        " VID: " + device.getVendorId() +
                        " PID: " + device.getProductId());
                deviceFound = true;

                // Request USB permission (for PendingIntent mutability flag)
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
            Log.d(TAG, "No compatible USB device found");
            tvConnectionStatus.setText(getString(R.string.connection_status_no_device));
        }
    }

    // Connect to serial port
    private void connectToSerialPort() {
        // Open USB connection
        connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Could not open USB connection");
            return;
        }

        // Find driver
        UsbSerialProber prober = UsbSerialProber.getDefaultProber();
        UsbSerialDriver driver = prober.probeDevice(device);

        if (driver == null) {
            Log.e(TAG, "Driver not found");
            return;
        }

        // Get first port
        if (driver.getPorts().size() < 1) {
            Log.e(TAG, "Serial port not found");
            return;
        }

        serialPort = driver.getPorts().get(0);

        try {
            serialPort.open(connection);
            serialPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            // Start serial port data receiver manager
            serialIoManager = new SerialInputOutputManager(serialPort, this);
            ioExecutor.submit(serialIoManager);

            // Update connection status
            tvConnectionStatus.setText(getString(R.string.connection_status_connected));
            tvConnectionStatus.setTextColor(Color.GREEN);

            Log.d(TAG, "Serial port connection successful");

            // Start scheduled task to read sensor data
            startSensorDataScheduler();

        } catch (IOException e) {
            Log.e(TAG, "Error opening serial port", e);
        }
    }

    // SerialInputOutputManager.Listener implementation
    @Override
    public void onNewData(byte[] data) {
        try {
            String dataStr = new String(data, StandardCharsets.UTF_8);
            Log.d(TAG, "Received data: " + dataStr);

            // Process incoming data
            if (dataStr.startsWith("SV:")) { // Sensor Value
                // Save sensor value
                try {
                    lastSensorValue = Float.parseFloat(dataStr.substring(3).trim());
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parsing sensor value", e);
                }

                processAndDisplaySensorData(dataStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding data", e);
        }
    }

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "Serial I/O manager error", e);
        mainHandler.post(() -> {
            tvConnectionStatus.setText(getString(R.string.connection_status_error));
            tvConnectionStatus.setTextColor(Color.RED);
        });
    }

    // Process and display sensor data in table
    private void processAndDisplaySensorData(final String data) {
        String voltageStr = data.substring(3).trim(); // Remove "SV:" part

        try {
            final float voltage = Float.parseFloat(voltageStr);

            // UI update on main thread
            mainHandler.post(() -> {
                // Current date/time
                String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

                // Create new table row
                TableRow row = new TableRow(MainActivity.this);

                // Time column
                TextView tvTime = new TextView(MainActivity.this);
                tvTime.setPadding(8, 8, 8, 8);
                tvTime.setText(timestamp);
                row.addView(tvTime);

                // Voltage column
                TextView tvVoltage = new TextView(MainActivity.this);
                tvVoltage.setPadding(8, 8, 8, 8);
                tvVoltage.setText(String.format(Locale.US, "%.2f", voltage));
                row.addView(tvVoltage);

                // Add to table
                tableSensorData.addView(row);

                // Show maximum 100 rows (memory management)
                if (tableSensorData.getChildCount() > 100) {
                    tableSensorData.removeViewAt(1); // Remove first data row except header
                }
            });

        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing voltage value", e);
        }
    }

    // Scheduled task to get sensor data
    private void startSensorDataScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Use scheduleWithFixedDelay (recommended method)
        scheduler.scheduleWithFixedDelay(() -> {
            if (serialPort != null) {
                // Request sensor data
                sendCommand("GET_SENSOR");
            }
        }, 0, 1, TimeUnit.SECONDS); // Request data every second
    }

    // Start pump
    private void startPump() {
        if (!isPumpRunning) {
            // Set pump direction
            sendDirectionCommand(isForwardDirection);

            // Set pump speed
            sendSpeedCommand(pumpSpeed);

            // Start pump
            sendCommand("START");

            isPumpRunning = true;
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        }
    }

    // Stop pump
    private void stopPump() {
        if (isPumpRunning) {
            sendCommand("STOP");

            isPumpRunning = false;
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        }
    }

    // Send direction command
    private void sendDirectionCommand(boolean isForward) {
        String command = isForward ? "DIR_FWD" : "DIR_REV";
        sendCommand(command);
    }

    // Send speed command
    private void sendSpeedCommand(int speed) {
        String command = "SPEED_" + speed;
        sendCommand(command);
    }

    // Send command
    private void sendCommand(String command) {
        if (serialPort != null) {
            command += "\n"; // Add line ending (for parsing on ESP32 side)
            try {
                serialPort.write(command.getBytes(), 1000); // 1000ms timeout
                Log.d(TAG, "Sent command: " + command.trim());
            } catch (IOException e) {
                Log.e(TAG, "Error sending command", e);
            }
        }
    }

    // USB events receiver
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (usbDevice != null) {
                            // Permission granted, connect to serial port
                            Log.d(TAG, "USB permission granted. Connecting...");
                            connectToSerialPort();
                        }
                    } else {
                        Log.d(TAG, "USB permission denied");
                        tvConnectionStatus.setText(getString(R.string.connection_status_permission_denied));
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                // USB device attached
                Log.d(TAG, "USB device attached");
                findSerialPortDevice();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                // USB device detached
                Log.d(TAG, "USB device detached");
                disconnect();
            }
        }
    };

    // Close connection
    private void disconnect() {
        if (serialIoManager != null) {
            serialIoManager.stop();
            serialIoManager = null;
        }

        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing serial port", e);
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
            // Already on this page, no action needed
        } else if (id == R.id.nav_graph) {
            Intent intent = new Intent(this, GraphActivity.class);
            startActivity(intent);
        } else if (id == R.id.nav_history) {
            Intent intent = new Intent(this, HistoryActivity.class);
            startActivity(intent);
        } else if (id == R.id.nav_measurements) {
            // Redirect to measurement records page
            Intent intent = new Intent(this, MeasurementsActivity.class);
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
        // Stop duration counter
        stopDurationCounter();

        // Clean up resources when exiting the app
        disconnect();

        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
            Log.d(TAG, "USB receiver not registered");
        }

        ioExecutor.shutdown();
        super.onDestroy();
    }
}