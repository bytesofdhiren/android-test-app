package com.vaporcommunications.onotestestapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.blescent.library.BluetoothLeService;
import com.crashlytics.android.Crashlytics;

import io.fabric.sdk.android.Fabric;

public class MainActivity extends AppCompatActivity implements Thread.UncaughtExceptionHandler {

    private static final String TAG = MainActivity.class.getName();
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final long SCAN_PERIOD = 10000;

    private static boolean mConnected;
    private static boolean mScanning = false;
    private static boolean mPermission = false;
    private static boolean mTest = false;
    private static boolean mFirstStop = true;
    private static String deviceAddress;
    private static String deviceName;

    private Button btnConnect;
    private Button btnTest;
    private TextView tvMessages;
    private TextView deviceId;


    private BluetoothLeService mBluetoothLeService;
    private Handler mHandler;
    private Runnable mRunnable;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Fabric.with(this, new Crashlytics());
        btnConnect = (Button) findViewById(R.id.button_connect);
        btnTest = (Button) findViewById(R.id.button_test);
        tvMessages = (TextView) findViewById(R.id.text_messages);
        deviceId = (TextView) findViewById(R.id.deviceId);
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mPermission) {
                    if (mConnected) {
                        tvMessages.setTextColor(getResources().getColor(R.color.colorYellow));
                        tvMessages.setText("Disconnecting..");
                        mBluetoothLeService.disconnect();
                    } else {
                        scanLeDevice(true);
                    }
                } else {
                    getPermission();
                }
            }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTest) {
                    mTest = false;
                    tvMessages.setText("Play Smell Started");
                    tvMessages.setTextColor(getResources().getColor(R.color.colorYellow));
                    mBluetoothLeService.stopScent();
                } else {
                    mTest = true;
                    mBluetoothLeService.playScent(60, 80, "EJO");
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        initialize();
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
        scanLeDevice(false);
        if (mBluetoothLeService != null)
            mBluetoothLeService.disconnect();
        unbindService(mServiceConnection);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_OK) {
            bleEnable();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[],
                                           int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "coarse location permission granted");
                    bleEnable();
                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Functionality limited");
                    builder.setMessage("Since location access has not been granted, this app will not be able to discover beacons when in the background.");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                        }
                    });
                    builder.show();
                }
                return;
            }
        }
    }

    private void initialize() {
        mTest = false;
        mConnected = false;
        tvMessages.setTextColor(getResources().getColor(R.color.colorRed));
        tvMessages.setText("Disconnected");
        btnConnect.setEnabled(true);
        btnConnect.setText("Connect");
        btnTest.setText("Test");
        btnTest.setEnabled(false);
        deviceId.setVisibility(View.INVISIBLE);
    }

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                tvMessages.setText("Confirming...");
                btnTest.setText("Test");
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                if (mBluetoothLeService != null && mBluetoothLeService.isCharacteristicsPresent()) {
                    tvMessages.setText("getting Firmware Revision...");
                } else {
                    tvMessages.setText("Failed to Connect");
                    mBluetoothLeService.disconnect();
                    initialize();
                }
            } else if (BluetoothLeService.notification.OPBTPeripheralFirmwareRevisionNotification.name().equals(action)) {
                mFirstStop = true;
                tvMessages.setTextColor(getResources().getColor(R.color.colorPrimary));
                tvMessages.setText("Successfully connected");
                String generatedDeviceAddress = deviceAddress.replace(":", "");
                String generatedName = deviceName + " " + generatedDeviceAddress.substring(generatedDeviceAddress.length() - 4, generatedDeviceAddress.length());
                deviceId.setText(generatedName);
                deviceId.setVisibility(View.VISIBLE);
                mConnected = true;
                btnConnect.setText("Disconnect");
                btnTest.setEnabled(true);
                mBluetoothLeService.stopScent();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                initialize();
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                if (mTest) {
                    tvMessages.setTextColor(getResources().getColor(R.color.colorYellow));
                    tvMessages.setText("Playing Scent");
                    btnConnect.setEnabled(false);
                    btnTest.setText("Stop");
                    mHandler = new Handler();
                    mRunnable = new Runnable() {
                        @Override
                        public void run() {
                            mTest = false;
                            btnConnect.setEnabled(true);
                            tvMessages.setTextColor(getResources().getColor(R.color.colorYellow));
                            btnTest.setText("Test");
                            tvMessages.setText("Scent Stopped");
                            mHandler.removeCallbacks(this);
                        }
                    };
                    mHandler.postDelayed(mRunnable, 60000);
                } else {
                    if (mFirstStop) {
                        mFirstStop = false;
                    } else {
                        tvMessages.setText("Scent Stopped");
                        tvMessages.setTextColor(getResources().getColor(R.color.colorYellow));
                    }
                    btnConnect.setEnabled(true);
                    btnTest.setText("Test");
                    if (mHandler != null) {
                        mHandler.removeCallbacks(mRunnable);
                    }
                }
            }
        }
    };

    protected final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                finish();
            }
            getPermission();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
            dialogOnBleServiceDisconnected();

        }
    };

    private void dialogOnBleServiceDisconnected() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Ble Service Disconnected");
        builder.setMessage("Since Ble service disconnected, restart App..");
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                restartApp();
            }
        });
        builder.show();
    }

    private void restartApp() {
        Intent intent = getIntent();
        finish();
        startActivity(intent);
    }

    private void getPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
            } else {
                bleEnable();
            }
        } else {
            bleEnable();
        }
    }

    private void bleEnable() {
        if (!mBluetoothLeService.isBluetoothEnable()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            mPermission = true;
        }
    }

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            final Handler mHandler = new Handler();
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mBluetoothLeService != null)
                        mBluetoothLeService.stopScanning(mLeScanCallback);
                    if (mScanning) {
                        mScanning = false;
                        tvMessages.setTextColor(getResources().getColor(R.color.colorRed));
                        tvMessages.setText("Didn't get device!! Try again...");
                        mHandler.removeCallbacks(this);
                    }
                }
            }, SCAN_PERIOD);
            tvMessages.setTextColor(getResources().getColor(R.color.colorYellow));
            tvMessages.setText("Scanning...");
            mScanning = true;
            if (mBluetoothLeService != null) mBluetoothLeService.scanForDevices(mLeScanCallback);
        } else {
            mScanning = false;
            if (mBluetoothLeService != null) mBluetoothLeService.stopScanning(mLeScanCallback);
        }
    }

    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {

                @Override
                public void onLeScan(final BluetoothDevice device, final int rssi, byte[] scanRecord) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            deviceAddress = device.getAddress();
                            deviceName = device.getName();
                            String generatedDeviceAddress = deviceAddress.replace(":", "");
                            String generatedName = deviceName + " " + generatedDeviceAddress.substring(generatedDeviceAddress.length() - 4, generatedDeviceAddress.length());
                            tvMessages.setText("Found \"" + generatedName + "\"");
                            scanLeDevice(false);
                            mBluetoothLeService.stopScanning(mLeScanCallback);
                            mBluetoothLeService.connect(deviceAddress);
                            tvMessages.setText("Connecting to \"" + generatedName + "\"");

                        }
                    });
                }
            };

    private IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction("PARSE_RESPONSE");
        intentFilter.addAction(BluetoothLeService.notification.OPBTPeripheralFirmwareRevisionNotification.name());
        return intentFilter;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        Log.d(TAG, "uncaughtException");
        Crashlytics.setUserName("From Android App");
        Crashlytics.logException(ex);
        System.exit(0);
    }
}
