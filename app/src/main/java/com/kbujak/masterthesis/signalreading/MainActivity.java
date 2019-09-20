package com.kbujak.masterthesis.signalreading;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import com.kontakt.sdk.android.ble.configuration.ActivityCheckConfiguration;
import com.kontakt.sdk.android.ble.configuration.ScanMode;
import com.kontakt.sdk.android.ble.configuration.ScanPeriod;
import com.kontakt.sdk.android.ble.connection.OnServiceReadyListener;
import com.kontakt.sdk.android.ble.filter.ibeacon.IBeaconFilter;
import com.kontakt.sdk.android.ble.manager.ProximityManager;
import com.kontakt.sdk.android.ble.manager.ProximityManagerFactory;
import com.kontakt.sdk.android.ble.manager.listeners.ScanStatusListener;
import com.kontakt.sdk.android.ble.manager.listeners.simple.SimpleIBeaconListener;
import com.kontakt.sdk.android.ble.manager.listeners.simple.SimpleScanStatusListener;
import com.kontakt.sdk.android.common.KontaktSDK;
import com.kontakt.sdk.android.common.profile.IBeaconDevice;
import com.kontakt.sdk.android.common.profile.IBeaconRegion;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private ProximityManager kontaktManager;
    private String TAG = "MyActivity";

    private StringBuffer stringBuffer = new StringBuffer();
    private Uri uri;

    private static final int WRITE_REQUEST_CODE = 43;
    private static final String mimeType = "text/csv";

    private final static int REQUEST_ENABLE_BT=1;

    // Accelerometer
    private SensorManager sensorManager;
    private Sensor sensor;
    private long lastTime = 0;
    private float lastX, lastY, lastZ;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        KontaktSDK.initialize(this);
        kontaktManager = ProximityManagerFactory.create(this);
        oneTimeConfiguration();

        final Button startScanButton = findViewById(R.id.start_scan_button);
        startScanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });

        final Button stopScanButton = findViewById(R.id.stop_scan_button);
        stopScanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopScan();
            }
        });

        final Button createFileButton = findViewById(R.id.create_file_button);
        createFileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createFile();
            }
        });

        final Switch bandpass_filter_switch = findViewById(R.id.bandpass_fiter_switch);
        bandpass_filter_switch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    kontaktManager.filters().iBeaconFilter(distanceFilter);
                    showToast("BandPass filter activated");
                }
            }
        });

        final Switch accelerometer_switch = findViewById(R.id.accelerometer_switch);
        accelerometer_switch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            }
        });
    }

    @Override
    protected void onStart() {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
        super.onStart();
    }


    @Override
    protected void onStop() {
        kontaktManager.disconnect();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        kontaktManager.disconnect();
        kontaktManager = null;
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void showToast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void oneTimeConfiguration() {
        checkPermissions();
        configureProximityManager();
        createFile();

        // Accelerometer
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        assert sensorManager != null;
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);

    }

    private void checkPermissions() {
        int checkSelfPermissionResult = ContextCompat.checkSelfPermission(this, Arrays.toString(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION}));
        if (PackageManager.PERMISSION_GRANTED == checkSelfPermissionResult) {
            //already granted
            Log.d(TAG, "Permission already granted");
        } else {
            //request permission
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION}, 100);
            Log.d(TAG, "Permission request called");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (100 == requestCode) {
                Log.d(TAG, "Permission granted");
            }
        } else {
            Log.d(TAG, "Permission not granted");
            showToast("Kontakt.io SDK requires this permission");
        }
    }

    private void configureProximityManager() {
        kontaktManager.configuration()
                .activityCheckConfiguration(ActivityCheckConfiguration.create(5000,10000))
                .deviceUpdateCallbackInterval(1000)
                .scanMode(ScanMode.LOW_LATENCY)
                .scanPeriod(ScanPeriod.RANGING);

        kontaktManager.setScanStatusListener(createScanStatusListener());
        kontaktManager.setIBeaconListener(new SimpleIBeaconListener(){
            @Override
            public void onIBeaconDiscovered(IBeaconDevice ibeacon, IBeaconRegion region) {
                super.onIBeaconDiscovered(ibeacon, region);
                stringBuffer.append(DateFormat.format("dd-MM-yyyy-hh:mm:ss", System.currentTimeMillis()).toString()).append(";").append(ibeacon.getUniqueId()).append(";").append(ibeacon.getRssi()).append("\n");
                Log.d(TAG,"iBeacon discovered: " + ibeacon.getUniqueId()+" " + ibeacon.getRssi());
            }

            @Override
            public void onIBeaconsUpdated(List<IBeaconDevice> ibeacons, IBeaconRegion region) {
                super.onIBeaconsUpdated(ibeacons, region);
                for (IBeaconDevice ibeacon : ibeacons) {
                    stringBuffer.append(DateFormat.format("dd-MM-yyyy-hh:mm:ss", System.currentTimeMillis()).toString()).append(";").append(ibeacon.getUniqueId()).append(";").append(ibeacon.getRssi()).append("\n");
                    Log.d(TAG,"iBeacon updated: " + ibeacon.getUniqueId()+" " + ibeacon.getRssi());
                }

            }
        });
        Log.d(TAG, "Manager initialised");
    }

    private ScanStatusListener createScanStatusListener() {
        return new SimpleScanStatusListener() {
            @Override
            public void onScanStart() {
                Log.d(TAG, "Scanning started");
                showToast("Scanning started");
            }

            @Override
            public void onScanStop() {
                Log.d(TAG, "Scanning stopped");
                showToast("Scanning stopped");
            }
        };
    }

    private final IBeaconFilter distanceFilter = new IBeaconFilter() {
        @Override
        public boolean apply(IBeaconDevice iBeaconDevice) {
            return iBeaconDevice.getRssi() < -30 && iBeaconDevice.getRssi() > -100;
        }
    };

    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    public boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) ||
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    private void createFile() {
        if (isExternalStorageWritable()) {
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);

                // Filter to only show results that can be "opened", such as
                // a file (as opposed to a list of contacts or timezones).
                intent.addCategory(Intent.CATEGORY_OPENABLE);

                // Create a file with the requested MIME type.
                String fileName = DateFormat.format("dd-MM-yyyy-hh-mm", System.currentTimeMillis()).toString() + ".csv";

                intent.setType(mimeType);
                intent.putExtra(Intent.EXTRA_TITLE, fileName);
                Log.d(TAG,intent.toString());
                startActivityForResult(intent, WRITE_REQUEST_CODE);

            } catch (Exception e){
                Log.d(TAG,"Exception: " + e);
            }
        } else {

            Log.d(TAG,"Media not writable" + "Read only: " + isExternalStorageReadable());
            showToast("Media not writable");
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {

        // The ACTION_OPEN_DOCUMENT intent was sent with the request code
        // READ_REQUEST_CODE. If the request code seen here doesn't match, it's the
        // response to some other intent, and the code below shouldn't run at all.

        super.onActivityResult(requestCode, resultCode, resultData);
        if (requestCode == WRITE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            // The document selected by the user won't be returned in the intent.
            // Instead, a URI to that document will be contained in the return intent
            // provided to this method as a parameter.
            // Pull that URI using resultData.getData().
            if (resultData != null) {
                uri = resultData.getData();
                Log.d(TAG,"Uri: " + uri.toString());
            }
        }
    }

    private void saveFile(Uri uri, String text) {
        try{
            // "wa" for write-only access to append to any existing data
            OutputStream outputStream = this.getContentResolver().openOutputStream(uri,"wa");
            assert outputStream != null;
            outputStream.write(text.getBytes());
            outputStream.flush();
            outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startScan() {
        kontaktManager.connect(new OnServiceReadyListener() {
            @Override
            public void onServiceReady() {
                kontaktManager.startScanning();
            }
        });
    }

    private void stopScan() {
        String buffed = stringBuffer.toString();
        saveFile(uri,buffed);
        stringBuffer.delete(0,stringBuffer.length());
        kontaktManager.stopScanning();
        kontaktManager.disconnect();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Sensor sensor = event.sensor;
        if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            long currentTime = System.currentTimeMillis();
            if ((currentTime - lastTime) > 100) {
                long diffTime = (currentTime - lastTime);
                lastTime = currentTime;
                float speed = Math.abs(x + y + z - lastX - lastY - lastZ)/ diffTime * 10000;
                Log.d(TAG,"The speed: " + speed);
                lastX = x;
                lastY = y;
                lastZ = z;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}
