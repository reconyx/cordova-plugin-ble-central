// (c) 2104 Don Coleman
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.megster.cordova.ble.central;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;

import android.os.ParcelUuid;
import android.provider.Settings;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;


import java.util.*;

public class BLECentralPlugin extends CordovaPlugin implements BluetoothAdapter.LeScanCallback {

    // actions
    private static final String SCAN = "scan";
    private static final String START_SCAN = "startScan";
    private static final String STOP_SCAN = "stopScan";

    private static final String LIST = "list";

    private static final String CONNECT = "connect";
    private static final String DISCONNECT = "disconnect";

    private static final String READ = "read";
    private static final String WRITE = "write";
    private static final String WRITE_WITHOUT_RESPONSE = "writeWithoutResponse";

    private static final String NOTIFY = "startNotification"; // register for characteristic notification

    private static final String IS_ENABLED = "isEnabled";
    private static final String IS_CONNECTED  = "isConnected";
    private static final String IS_CAPABLE = "isCapable";

    private static final String SETTINGS = "showBluetoothSettings";
    private static final String ENABLE = "enable";
    // callbacks
    CallbackContext discoverCallback;
    int discoverSeconds;
    private CallbackContext enableBluetoothCallback;

    private static final String TAG = "BLEPlugin";
    private static final int REQUEST_ENABLE_BLUETOOTH = 1;
	private static final int RESCAN_INTERVAL = 30000;

	private boolean isScanning = false;

    BluetoothManager bluetoothManager;
    BluetoothAdapter bluetoothAdapter;

    // key is the MAC Address
    Map<String, Peripheral> peripherals = new LinkedHashMap<String, Peripheral>();

    UUID[] scanFilterUuids = null;

    private static final String BLUETOOTH_PERMISSION = Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERMISSION2 = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final String BLUETOOTH_PERMISSION3 = Manifest.permission.ACCESS_FINE_LOCATION;

    private void getPermission(int requestCode)
    {
        ArrayList<String> permissions = new ArrayList<String>();
        if (!cordova.hasPermission(BLUETOOTH_PERMISSION)) {
            permissions.add(BLUETOOTH_PERMISSION);
        }
        if (!cordova.hasPermission(BLUETOOTH_PERMISSION2)) {
            permissions.add(BLUETOOTH_PERMISSION2);
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (!cordova.hasPermission(BLUETOOTH_PERMISSION3)) {
                permissions.add(BLUETOOTH_PERMISSION3);
            }
        }
        cordova.requestPermissions(this, requestCode, permissions.toArray(new String[0]));
    }

    private boolean hasPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            LOG.d(TAG, "hasPermission >= 29 BLUETOOTH_ADMIN: " + cordova.hasPermission(BLUETOOTH_PERMISSION) );
            LOG.d(TAG, "hasPermission >= 29 ACCESS_COARSE_LOCATION: " + cordova.hasPermission(BLUETOOTH_PERMISSION2) );
            LOG.d(TAG, "hasPermission >= 29 ACCESS_FINE_LOCATION: " + cordova.hasPermission(BLUETOOTH_PERMISSION3) );
            return cordova.hasPermission(BLUETOOTH_PERMISSION) && cordova.hasPermission(BLUETOOTH_PERMISSION2) && cordova.hasPermission(BLUETOOTH_PERMISSION3);
        }
        else {
            LOG.d(TAG, "hasPermission < 29 BLUETOOTH_ADMIN: " + cordova.hasPermission(BLUETOOTH_PERMISSION) );
            LOG.d(TAG, "hasPermission < 29 ACCESS_COARSE_LOCATION: " + cordova.hasPermission(BLUETOOTH_PERMISSION2) );
            return cordova.hasPermission(BLUETOOTH_PERMISSION) && cordova.hasPermission(BLUETOOTH_PERMISSION2);
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException
    {
        for(int r:grantResults)
        {
            if(r == PackageManager.PERMISSION_DENIED)
            {
                LOG.i(TAG, "Permission Denied");
                if (discoverCallback != null) {
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.ERROR, "permission denied");
                    discoverCallback.sendPluginResult(pluginResult);
                    discoverCallback = null;
                }
                return;
            }
        }
        LOG.i(TAG, "Permission Granted");
        if (discoverCallback != null) {
            findLowEnergyDevices();
        }
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {

        LOG.d(TAG, "action = " + action);

        if (bluetoothAdapter == null) {
            Activity activity = cordova.getActivity();
            bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        boolean validAction = true;

        if (action.equals(SCAN)) {

            UUID[] serviceUUIDs = parseServiceUUIDList(args.getJSONArray(0));
            int scanSeconds = args.getInt(1);
            findLowEnergyDevices(callbackContext, serviceUUIDs, scanSeconds);

        } else if (action.equals(START_SCAN)) {

            UUID[] serviceUUIDs = parseServiceUUIDList(args.getJSONArray(0));
            findLowEnergyDevices(callbackContext, serviceUUIDs, -1);

        } else {
            if (action.equals(STOP_SCAN)) {
                isScanning = false;
                if (android.os.Build.VERSION.SDK_INT < 21) {
                    bluetoothAdapter.stopLeScan(this);
                } else {
                    bluetoothLeScanner().stopScan(this.scanCallback());
                }
                callbackContext.success();

            } else if (action.equals(LIST)) {

                listKnownDevices(callbackContext);

            } else if (action.equals(CONNECT)) {

                String macAddress = args.getString(0);
                connect(callbackContext, macAddress);

            } else if (action.equals(DISCONNECT)) {

                String macAddress = args.getString(0);
                disconnect(callbackContext, macAddress);

            } else if (action.equals(READ)) {

                String macAddress = args.getString(0);
                UUID serviceUUID = uuidFromString(args.getString(1));
                UUID characteristicUUID = uuidFromString(args.getString(2));
                read(callbackContext, macAddress, serviceUUID, characteristicUUID);

            } else if (action.equals(WRITE)) {

                String macAddress = args.getString(0);
                UUID serviceUUID = uuidFromString(args.getString(1));
                UUID characteristicUUID = uuidFromString(args.getString(2));
                byte[] data = args.getArrayBuffer(3);
                int type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                write(callbackContext, macAddress, serviceUUID, characteristicUUID, data, type);

            } else if (action.equals(WRITE_WITHOUT_RESPONSE)) {

                String macAddress = args.getString(0);
                UUID serviceUUID = uuidFromString(args.getString(1));
                UUID characteristicUUID = uuidFromString(args.getString(2));
                byte[] data = args.getArrayBuffer(3);
                int type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                write(callbackContext, macAddress, serviceUUID, characteristicUUID, data, type);

            } else if (action.equals(NOTIFY)) {

                String macAddress = args.getString(0);
                UUID serviceUUID = uuidFromString(args.getString(1));
                UUID characteristicUUID = uuidFromString(args.getString(2));
                registerNotifyCallback(callbackContext, macAddress, serviceUUID, characteristicUUID);

            } else if (action.equals(IS_ENABLED)) {

                if (bluetoothAdapter.isEnabled()) {
                    callbackContext.success();
                } else {
                    callbackContext.error("Bluetooth is disabled.");
                }

            } else if (action.equals(IS_CAPABLE)) {

                if (this.cordova.getActivity().getApplicationContext().getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) {
                    callbackContext.success();
                } else {
                    callbackContext.error("Bluetooth not supported on this device.");
                }

            } else if (action.equals(IS_CONNECTED)) {

                String macAddress = args.getString(0);

                if (peripherals.containsKey(macAddress) && peripherals.get(macAddress).isConnected()) {
                    callbackContext.success();
                } else {
                    callbackContext.error("Not connected.");
                }

            } else if (action.equals(SETTINGS)) {

                Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                cordova.getActivity().startActivity(intent);
                callbackContext.success();

            } else if (action.equals(ENABLE)) {

                enableBluetoothCallback = callbackContext;
                Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                cordova.startActivityForResult(this, intent, REQUEST_ENABLE_BLUETOOTH);

            } else {

                validAction = false;

            }
        }

        return validAction;
    }

    private UUID[] parseServiceUUIDList(JSONArray jsonArray) throws JSONException {
        List<UUID> serviceUUIDs = new ArrayList<UUID>();

        for(int i = 0; i < jsonArray.length(); i++){
            String uuidString = jsonArray.getString(i);
            serviceUUIDs.add(uuidFromString(uuidString));
        }

        return serviceUUIDs.toArray(new UUID[jsonArray.length()]);
    }

    private void connect(CallbackContext callbackContext, String macAddress) {

        Peripheral peripheral = peripherals.get(macAddress);
        if (peripheral != null) {
            peripheral.connect(callbackContext, cordova.getActivity());
        } else {
            callbackContext.error("Peripheral " + macAddress + " not found.");
        }

    }

    private void disconnect(CallbackContext callbackContext, String macAddress) {

        Peripheral peripheral = peripherals.get(macAddress);
        if (peripheral != null) {
            peripheral.disconnect();
        }
        callbackContext.success();

    }

    private void read(CallbackContext callbackContext, String macAddress, UUID serviceUUID, UUID characteristicUUID) {

        Peripheral peripheral = peripherals.get(macAddress);

        if (peripheral == null) {
            callbackContext.error("Peripheral " + macAddress + " not found.");
            return;
        }

        if (!peripheral.isConnected()) {
            callbackContext.error("Peripheral " + macAddress + " is not connected.");
            return;
        }

        //peripheral.readCharacteristic(callbackContext, serviceUUID, characteristicUUID);
        peripheral.queueRead(callbackContext, serviceUUID, characteristicUUID);

    }

    private void write(CallbackContext callbackContext, String macAddress, UUID serviceUUID, UUID characteristicUUID,
                       byte[] data, int writeType) {

        Peripheral peripheral = peripherals.get(macAddress);

        if (peripheral == null) {
            callbackContext.error("Peripheral " + macAddress + " not found.");
            return;
        }

        if (!peripheral.isConnected()) {
            callbackContext.error("Peripheral " + macAddress + " is not connected.");
            return;
        }

        //peripheral.writeCharacteristic(callbackContext, serviceUUID, characteristicUUID, data, writeType);
        peripheral.queueWrite(callbackContext, serviceUUID, characteristicUUID, data, writeType);

    }

    private void registerNotifyCallback(CallbackContext callbackContext, String macAddress, UUID serviceUUID, UUID characteristicUUID) {

        Peripheral peripheral = peripherals.get(macAddress);
        if (peripheral != null) {

            //peripheral.setOnDataCallback(serviceUUID, characteristicUUID, callbackContext);
            peripheral.queueRegisterNotifyCallback(callbackContext, serviceUUID, characteristicUUID);

        } else {

            callbackContext.error("Peripheral " + macAddress + " not found");

        }

    }

    protected ScanCallback _scanCallback = null;

    @TargetApi(21)
    protected ScanCallback scanCallback() {
        if (_scanCallback == null) {
            _scanCallback = new ScanCallback() {
                @Override
                public void onScanResult ( int callbackType, ScanResult result){
                    BluetoothDevice device = result.getDevice();
                    if (device != null) {
                        int rssi = result.getRssi();
                        ScanRecord scanRecord = result.getScanRecord();
                        assert scanRecord != null;
                        byte[] rawScanRecord = scanRecord.getBytes();

                        Peripheral peripheral = new Peripheral(bluetoothManager, bluetoothAdapter, device, rssi, rawScanRecord);
                        peripherals.put(device.getAddress(), peripheral);

                        if (discoverCallback != null) {
                            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, peripheral.asJSONObject());
                            pluginResult.setKeepCallback(true);
                            discoverCallback.sendPluginResult(pluginResult);
                        }
                    }
                }
                @Override
                public void onBatchScanResults (List < ScanResult > results) {
                    for (ScanResult result : results) {
                        onScanResult(2, result);
                    }
                }

                @Override
                public void onScanFailed ( int errorCode){
                    PluginResult result = new PluginResult(PluginResult.Status.ERROR, errorCode);
                    result.setKeepCallback(false);
                    discoverCallback.sendPluginResult(result);
                }
            } ;
        }
        return _scanCallback;
    }

    @TargetApi(21)
    private List<ScanFilter> scanFilters() {
        List<ScanFilter> list = new ArrayList<ScanFilter>();
        for (UUID uuid : this.scanFilterUuids) {
            ParcelUuid parcelUuid = new ParcelUuid(uuid);
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(parcelUuid).build();
            list.add(filter);    
        }
        
        return list;
    } 

    private ScanSettings _scanSettings = null;

    @TargetApi(23)
    private ScanSettings scanSettings() {
        if (_scanSettings == null) {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                _scanSettings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                        .setReportDelay(0)
                        .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                        .build();
            }
            else {
                _scanSettings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setReportDelay(0)
                        .build();
            }
        }
        return _scanSettings;
    }

    private BluetoothLeScanner _bluetoothLeScanner = null;

    @TargetApi(21)
    private BluetoothLeScanner bluetoothLeScanner() {
        if (_bluetoothLeScanner == null) {
            _bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }       
        return _bluetoothLeScanner;
    }
    
    private void findLowEnergyDevices(CallbackContext callbackContext, final UUID[] serviceUUIDs, int scanSeconds) {

        // TODO skip if currently scanning

        discoverCallback = callbackContext;
        discoverSeconds = scanSeconds;
        scanFilterUuids = serviceUUIDs;

        if(hasPermission()) {
            LOG.d(TAG, "has permission");
            findLowEnergyDevices();
        }
        else {
            getPermission(0);
        }
    }

    private void findLowEnergyDevices() {

        // clear non-connected cached peripherals
        for(Iterator<Map.Entry<String, Peripheral>> iterator = peripherals.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<String, Peripheral> entry = iterator.next();
            if(!entry.getValue().isConnected()) {
                iterator.remove();
            }
        }
        
        // the Android 4.3 code has a bug parsing UUIDs.  This code gets around that by
        // asking android for everything, and then filtering the results after the fact
        // instead of by providing Android with the UUID to filter
        if (android.os.Build.VERSION.SDK_INT < 21) {
            bluetoothAdapter.startLeScan(this);

            if (discoverSeconds > 0) {
                Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        isScanning = false;
                        LOG.d(TAG, "Stopping Scan");
                        BLECentralPlugin.this.bluetoothAdapter.stopLeScan(BLECentralPlugin.this);
                    }
                }, discoverSeconds * 1000);
            }

            isScanning = true;

            final Handler rescanHandler = new Handler();
            rescanHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    LOG.d(TAG, "Rescanning");
                    if (isScanning) {
                        BLECentralPlugin.this.bluetoothAdapter.stopLeScan(BLECentralPlugin.this);
                        BLECentralPlugin.this.bluetoothAdapter.startLeScan(BLECentralPlugin.this);
                        rescanHandler.postDelayed(this, RESCAN_INTERVAL);
                    }
                }
            }, RESCAN_INTERVAL);    
        }        
        else {
            bluetoothLeScanner().startScan(this.scanFilters(), this.scanSettings(), scanCallback());
        }


        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        discoverCallback.sendPluginResult(result);
    }

    private void listKnownDevices(CallbackContext callbackContext) {

        JSONArray json = new JSONArray();

        // do we care about consistent order? will peripherals.values() be in order?
        for (Map.Entry<String, Peripheral> entry : peripherals.entrySet()) {
            Peripheral peripheral = entry.getValue();
            json.put(peripheral.asJSONObject());
        }

        PluginResult result = new PluginResult(PluginResult.Status.OK, json);
        callbackContext.sendPluginResult(result);
    }


    static List<UUID> parseUuids(byte[] adv_data) {
        List<UUID> uuids = new ArrayList<UUID>();

        int offset = 0;
        while (offset < (adv_data.length - 2)) {
            int len = adv_data[offset++];
            if (len == 0) break;
            if (offset + len > adv_data.length) break;

            int type = adv_data[offset++];
            len--; //subtract one byte for the type
            switch (type) {
                case 0x02: // Partial list of 16-bit UUIDs
                case 0x03: // Complete list of 16-bit UUIDs
                    while (len > 1) {
                        int uuid16 = (adv_data[offset++] & 0xff) | ((adv_data[offset++] & 0xff) << 8);
                        len -= 2;
                        uuids.add(UUID.fromString(String.format(
                                "%08x-0000-1000-8000-00805f9b34fb", uuid16)));
                    }
                    offset += len;
                    break;

                default:
                    offset += len;
                    break;
            }
        }

        return uuids;
    }

    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {

        String address = device.getAddress();

        if (!peripherals.containsKey(address)) {
            
            List<UUID> uuids = parseUuids(scanRecord);
            boolean found = false;
            for(UUID uuid: uuids) {
                for (UUID uuid2: scanFilterUuids) {
                    if (uuid.equals(uuid2)) {
                        found = true;
                        break;
                    }                    
                }
            }

            if (found) {
                Peripheral peripheral = new Peripheral(bluetoothManager, bluetoothAdapter, device, rssi, scanRecord);
                peripherals.put(device.getAddress(), peripheral);
    
    
                if (discoverCallback != null) {
                    PluginResult result = new PluginResult(PluginResult.Status.OK, peripheral.asJSONObject());
                    result.setKeepCallback(true);
                    discoverCallback.sendPluginResult(result);
                }
            }

        } else {
            // this isn't necessary
            Peripheral peripheral = peripherals.get(address);
            peripheral.updateRssi(rssi);

            if (discoverCallback != null) {
                PluginResult result = new PluginResult(PluginResult.Status.OK, peripheral.asJSONObject());
                result.setKeepCallback(true);
                discoverCallback.sendPluginResult(result);
            }
        }

        // TODO offer option to return duplicates

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {

            if (resultCode == Activity.RESULT_OK) {
                LOG.d(TAG, "User enabled Bluetooth");
                if (enableBluetoothCallback != null) {
                    enableBluetoothCallback.success();
                }
            } else {
                LOG.d(TAG, "User did *NOT* enable Bluetooth");
                if (enableBluetoothCallback != null) {
                    enableBluetoothCallback.error("User did not enable Bluetooth");
                }
            }

            enableBluetoothCallback = null;
        }
    }

    private UUID uuidFromString(String uuid) {
        return UUIDHelper.uuidFromString(uuid);
    }

}
