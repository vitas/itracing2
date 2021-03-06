package net.sylvek.itracing2;

import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Created by sylvek on 18/05/2015.
 */
public class BluetoothLEService extends Service {

    public static final int NO_ALERT = 0x00;
    public static final int MEDIUM_ALERT = 0x01;
    public static final int HIGH_ALERT = 0x02;

    public static final String IMMEDIATE_ALERT_AVAILABLE = "IMMEDIATE_ALERT_AVAILABLE";
    public static final String BATTERY_LEVEL = "BATTERY_LEVEL";
    public static final String GATT_CONNECTED = "GATT_CONNECTED";
    public static final String SERVICES_DISCOVERED = "SERVICES_DISCOVERED";
    public static final String RSSI_RECEIVED = "RSSI_RECEIVED";

    public static final UUID IMMEDIATE_ALERT_SERVICE = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb");
    public static final UUID FIND_ME_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    public static final UUID LINK_LOSS_SERVICE = UUID.fromString("00001803-0000-1000-8000-00805f9b34fb");
    public static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID ALERT_LEVEL_CHARACTERISTIC = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb");
    public static final UUID FIND_ME_CHARACTERISTIC = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    public static final String TAG = BluetoothLEService.class.toString();
    public static final String ACTION_PREFIX = "net.sylvek.itracing2.action.";
    public static final long TRACK_REMOTE_RSSI_DELAY_MILLIS = 5000L;

    private BluetoothDevice mDevice;

    private BluetoothGatt bluetoothGatt = null;

    private BluetoothGattService immediateAlertService;

    private BluetoothGattCharacteristic batteryCharacteristic;

    private BluetoothGattCharacteristic buttonCharacteristic;

    private long lastChange;

    private Runnable r;

    private Handler handler = new Handler();

    private Runnable trackRemoteRssi = null;

    private BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
        {
            Log.d(TAG, "onConnectionStateChange() status => " + status);
            if (BluetoothGatt.GATT_SUCCESS == status) {
                Log.d(TAG, "onConnectionStateChange() newState => " + newState);
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    broadcaster.sendBroadcast(new Intent(GATT_CONNECTED));
                    gatt.discoverServices();
                }
            }

            final boolean actionOnPowerOff = Preferences.isActionOnPowerOff(BluetoothLEService.this);
            if (actionOnPowerOff || status == 8) {
                Log.d(TAG, "onConnectionStateChange() newState => " + newState);
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    String action = Preferences.getActionOutOfBand(getApplicationContext());
                    sendBroadcast(new Intent(ACTION_PREFIX + action));
                    enablePeerDeviceNotifyMe(gatt, false);
                }
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status)
        {
            final Intent rssiIntent = new Intent(RSSI_RECEIVED);
            final int quality = 2 * (rssi + 100);
            rssiIntent.putExtra(RSSI_RECEIVED, quality + "%");
            broadcaster.sendBroadcast(rssiIntent);
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status)
        {
            Log.d(TAG, "onServicesDiscovered()");

            launchTrackingRemoteRssi(gatt);

            broadcaster.sendBroadcast(new Intent(SERVICES_DISCOVERED));
            if (BluetoothGatt.GATT_SUCCESS == status) {

                for (BluetoothGattService service : gatt.getServices()) {
                    if (IMMEDIATE_ALERT_SERVICE.equals(service.getUuid())) {
                        immediateAlertService = service;
                        broadcaster.sendBroadcast(new Intent(IMMEDIATE_ALERT_AVAILABLE));
                        gatt.readCharacteristic(getCharacteristic(gatt, IMMEDIATE_ALERT_SERVICE, ALERT_LEVEL_CHARACTERISTIC));
                    }

                    if (BATTERY_SERVICE.equals(service.getUuid())) {
                        batteryCharacteristic = service.getCharacteristics().get(0);
                        gatt.readCharacteristic(batteryCharacteristic);
                    }

                    if (FIND_ME_SERVICE.equals(service.getUuid())) {
                        if (!service.getCharacteristics().isEmpty()) {
                            buttonCharacteristic = service.getCharacteristics().get(0);
                            setCharacteristicNotification(gatt, buttonCharacteristic, true);
                        }
                    }
                }
                enablePeerDeviceNotifyMe(gatt, true);
            }
        }

        private void launchTrackingRemoteRssi(final BluetoothGatt gatt)
        {
            if (trackRemoteRssi != null) {
                handler.removeCallbacks(trackRemoteRssi);
            }

            trackRemoteRssi = new Runnable() {
                @Override
                public void run()
                {
                    gatt.readRemoteRssi();
                    handler.postDelayed(this, TRACK_REMOTE_RSSI_DELAY_MILLIS);
                }
            };
            handler.post(trackRemoteRssi);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
        {
            Log.d(TAG, "onDescriptorWrite()");
            gatt.readCharacteristic(batteryCharacteristic);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
        {
            Log.d(TAG, "onCharacteristicChanged()");
            final long delayDoubleClick = Preferences.getDoubleButtonDelay(getApplicationContext());

            final long now = SystemClock.elapsedRealtime();
            if (lastChange + delayDoubleClick > now) {
                Log.d(TAG, "onCharacteristicChanged() - double click");
                lastChange = 0;
                handler.removeCallbacks(r);
                String action = Preferences.getActionDoubleButton(getApplicationContext());
                sendBroadcast(new Intent(ACTION_PREFIX + action));
            } else {
                lastChange = now;
                r = new Runnable() {
                    @Override
                    public void run()
                    {
                        Log.d(TAG, "onCharacteristicChanged() - simple click");
                        String action = Preferences.getActionSimpleButton(getApplicationContext());
                        sendBroadcast(new Intent(ACTION_PREFIX + action));
                    }
                };
                handler.postDelayed(r, delayDoubleClick);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
        {
            Log.d(TAG, "onCharacteristicRead()");

            final Intent batteryLevel = new Intent(BATTERY_LEVEL);
            batteryLevel.putExtra(BATTERY_LEVEL, Integer.valueOf(characteristic.getValue()[0]) + "%");
            broadcaster.sendBroadcast(batteryLevel);


        }
    };

    private void setCharacteristicNotification(BluetoothGatt bluetoothgatt, BluetoothGattCharacteristic bluetoothgattcharacteristic, boolean flag)
    {
        bluetoothgatt.setCharacteristicNotification(bluetoothgattcharacteristic, flag);
        if (FIND_ME_CHARACTERISTIC.equals(bluetoothgattcharacteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = bluetoothgattcharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                bluetoothgatt.writeDescriptor(descriptor);
            }
        }
    }

    public void enablePeerDeviceNotifyMe(BluetoothGatt bluetoothgatt, boolean flag)
    {
        BluetoothGattCharacteristic bluetoothgattcharacteristic = getCharacteristic(bluetoothgatt, FIND_ME_SERVICE, FIND_ME_CHARACTERISTIC);
        if (bluetoothgattcharacteristic != null && (bluetoothgattcharacteristic.getProperties() | 0x10) > 0) {
            setCharacteristicNotification(bluetoothgatt, bluetoothgattcharacteristic, flag);
        }
    }

    private BluetoothGattCharacteristic getCharacteristic(BluetoothGatt bluetoothgatt, UUID serviceUuid, UUID characteristicUuid)
    {
        if (bluetoothgatt != null) {
            BluetoothGattService service = bluetoothgatt.getService(serviceUuid);
            if (service != null) {
                return service.getCharacteristic(characteristicUuid);
            }
        }

        return null;
    }

    public class BackgroundBluetoothLEBinder extends Binder {
        public BluetoothLEService service()
        {
            return BluetoothLEService.this;
        }
    }

    private BackgroundBluetoothLEBinder myBinder = new BackgroundBluetoothLEBinder();

    private LocalBroadcastManager broadcaster;

    @Override
    public IBinder onBind(Intent intent)
    {
        return myBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        this.connect();
        return START_STICKY;
    }

    @Override
    public void onCreate()
    {
        super.onCreate();
        this.broadcaster = LocalBroadcastManager.getInstance(this);
    }

    @Override
    public void onDestroy()
    {
        if (trackRemoteRssi != null) {
            handler.removeCallbacks(trackRemoteRssi);
        }

        super.onDestroy();
        Log.d(TAG, "onDestroy()");
    }

    public void immediateAlert(int alertType)
    {
        if (immediateAlertService == null || immediateAlertService.getCharacteristics() == null || immediateAlertService.getCharacteristics().size() == 0) {
            somethingGoesWrong();
            return;
        }
        final BluetoothGattCharacteristic characteristic = immediateAlertService.getCharacteristics().get(0);
        characteristic.setValue(alertType, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        this.bluetoothGatt.writeCharacteristic(characteristic);
    }

    private void somethingGoesWrong()
    {
        Toast.makeText(this, R.string.something_goes_wrong, Toast.LENGTH_LONG).show();
    }

    public void connect()
    {
        if (this.bluetoothGatt == null) {
            Log.d(TAG, "connect() - connecting GATT");
            mDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(Preferences.getKeyringUUID(this));
            this.bluetoothGatt = mDevice.connectGatt(this, true, bluetoothGattCallback);
        } else {
            Log.d(TAG, "connect() - discovering services");
            this.bluetoothGatt.discoverServices();
        }
    }

    public void disconnect()
    {
        Log.d(TAG, "disconnect()");
        this.bluetoothGatt.disconnect();
        this.bluetoothGatt.close();
        this.bluetoothGatt = null;
    }

}
