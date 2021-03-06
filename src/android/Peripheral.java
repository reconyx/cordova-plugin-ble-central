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

import android.app.Activity;

import android.bluetooth.*;
import android.util.Base64;
import android.os.Handler;
import android.os.Looper;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Peripheral wraps the BluetoothDevice and provides methods to convert to JSON.
 */
public class Peripheral extends BluetoothGattCallback {

    // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
    //public final static UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    public final static UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUIDHelper.uuidFromString("2902");
    private static final String TAG = "Peripheral";

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice device;
    private byte[] advertisingData;
    private int advertisingRSSI;
    private boolean connected = false;
    private ConcurrentLinkedQueue<BLECommand> commandQueue = new ConcurrentLinkedQueue<BLECommand>();
    private boolean bleProcessing;
    private Timer rssiTimer;

    BluetoothGatt gatt;

    private CallbackContext connectCallback;
    private CallbackContext readCallback;
    private CallbackContext writeCallback;

    private Map<String, CallbackContext> notificationCallbacks = new HashMap<String, CallbackContext>();

    public Peripheral(BluetoothManager bluetoothManager, BluetoothAdapter bluetoothAdapter, BluetoothDevice device, int advertisingRSSI, byte[] scanRecord) {
        this.bluetoothManager = bluetoothManager;
        this.bluetoothAdapter = bluetoothAdapter;
        this.device = device;
        this.advertisingRSSI = advertisingRSSI;
        this.advertisingData = scanRecord;

    }
    
    protected void runOnUiThread(Runnable runnable) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(runnable);
    }

    protected boolean disconnected = false;

    public void connect(final CallbackContext callbackContext, final Activity activity) {
        final BluetoothDevice device = getDevice();
        connectCallback = callbackContext;

        final Peripheral peripheral = this;
        runOnUiThread(new Runnable() {   
            @Override
            public void run() {         
                peripheral.gatt = device.connectGatt(activity, false, peripheral);
                if (gatt == null) {
                        LOG.w(TAG, "connect gatt returned null");
                }
                else {
                    LOG.d(TAG, "connect gatt returned not null");
                }
        
                PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);
            }
        });
    }

    private boolean isConnected2() {
        List<BluetoothDevice> connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER); 
        if (connectedDevices != null) {
            for (BluetoothDevice connectedDevice : connectedDevices) {
                if (connectedDevice.getAddress() == device.getAddress()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void disconnect() {
        disconnected = true;
        connectCallback = null;
        
        // quit checking remote rssi
        if (rssiTimer != null) {
            rssiTimer.cancel();
            rssiTimer = null;
        }

        connected = false;        
        
        if (gatt != null) {
            
            // if android thinks we are connected
            if (isConnected2()) {
                // disconnect
                gatt.disconnect();
            }
            else {
                // otherwise free the resource
                gatt.close();                
                gatt = null;
            }
        }
        
        // NOTE: if a disconnect happens between writeCharacteristic() and onCharacteristicWrite(),
        // onCharacteristicWrite() will never be called.
        // This works around that by calling .error() on any existing callbacks
        if (writeCallback != null) {
            writeCallback.error("disconnected");
            writeCallback = null;
        }
        if (readCallback != null) {
            readCallback.error("disconnected");
            readCallback = null;
        }
        if (connectCallback != null) {
            connectCallback.error("disconnected");
            connectCallback = null;            
        }
        
        // remove the notification callback
        notificationCallbacks.clear();
        
        // when the above issue happens, make sure we aren't stuck in bleProcessing = true
        bleProcessing = false; 
        
        // consume any outstanding commands, since we were disconnected
        processCommands();
    }

    public JSONObject asJSONObject()  {

        JSONObject json = new JSONObject();

        try {
            json.put("name", device.getName());
            json.put("id", device.getAddress()); // mac address
            json.put("advertising", byteArrayToJSON(advertisingData));
            // TODO real RSSI if we have it, else
            json.put("rssi", advertisingRSSI);
        } catch (JSONException e) { // this shouldn't happen
            e.printStackTrace();
        }

        return json;
    }

    public JSONObject asJSONObject(BluetoothGatt gatt) {

        JSONObject json = asJSONObject();

        try {
            JSONArray servicesArray = new JSONArray();
            JSONArray characteristicsArray = new JSONArray();
            json.put("services", servicesArray);
            json.put("characteristics", characteristicsArray);

            if (connected && gatt != null) {
                for (BluetoothGattService service : gatt.getServices()) {
                    servicesArray.put(UUIDHelper.uuidToString(service.getUuid()));

                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        JSONObject characteristicsJSON = new JSONObject();
                        characteristicsArray.put(characteristicsJSON);

                        characteristicsJSON.put("service", UUIDHelper.uuidToString(service.getUuid()));
                        characteristicsJSON.put("characteristic", UUIDHelper.uuidToString(characteristic.getUuid()));
                        //characteristicsJSON.put("instanceId", characteristic.getInstanceId());

                        characteristicsJSON.put("properties", Helper.decodeProperties(characteristic));
                            // characteristicsJSON.put("propertiesValue", characteristic.getProperties());

                        if (characteristic.getPermissions() > 0) {
                            characteristicsJSON.put("permissions", Helper.decodePermissions(characteristic));
                            // characteristicsJSON.put("permissionsValue", characteristic.getPermissions());
                        }

                        JSONArray descriptorsArray = new JSONArray();

                        for (BluetoothGattDescriptor descriptor: characteristic.getDescriptors()) {
                            JSONObject descriptorJSON = new JSONObject();
                            descriptorJSON.put("uuid", UUIDHelper.uuidToString(descriptor.getUuid()));
                            descriptorJSON.put("value", descriptor.getValue()); // always blank

                            if (descriptor.getPermissions() > 0) {
                                descriptorJSON.put("permissions", Helper.decodePermissions(descriptor));
                                // descriptorJSON.put("permissionsValue", descriptor.getPermissions());
                            }
                            descriptorsArray.put(descriptorJSON);
                        }
                        if (descriptorsArray.length() > 0) {
                            characteristicsJSON.put("descriptors", descriptorsArray);
                        }
                    }
                }
            }
        } catch (JSONException e) { // TODO better error handling
            e.printStackTrace();
        }

        return json;
    }

    static JSONObject byteArrayToJSON(byte[] bytes) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("CDVType", "ArrayBuffer");
        object.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        return object;
    }

    public boolean isConnected() {
        return connected;
    }

    public BluetoothDevice getDevice() {
        if (disconnected) {
            BluetoothDevice newDevice = bluetoothAdapter.getRemoteDevice(device.getAddress());
            if (newDevice != null) {
                device = newDevice;
            }
            else {
                LOG.w(TAG, "getRemoteDevice() failed");
            }
        }
        return device;
    }

    @Override
    public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);

        if (status == BluetoothGatt.GATT_SUCCESS) {
            LOG.d(TAG, "Discovered Services");
            PluginResult result = new PluginResult(PluginResult.Status.OK, this.asJSONObject(gatt));
            result.setKeepCallback(true);
            connectCallback.sendPluginResult(result);
            
            try {
                TimerTask task = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        gatt.readRemoteRssi();
                    }
                };

                if (rssiTimer != null) {
                    rssiTimer.cancel();
                }              
                  
                rssiTimer = new Timer();
                rssiTimer.schedule(task, 0, 500);
            }
            catch(Exception e) {
                LOG.e(TAG, "Exception setting up RSSI task: " + e.toString());
            }
            
        } else {
            if (connectCallback != null) {
                LOG.e(TAG, "Service discovery failed. status = " + status);
                connectCallback.error("Service discovery failed. status = " + status);
                connectCallback = null;
            }
            disconnect();
        }
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {

        LOG.d(TAG, "onConnectionStateChange()");
        //this.gatt = gatt;

        if (newState == BluetoothGatt.STATE_CONNECTED) {
            LOG.d(TAG, "connected");
            connected = true;
            final Peripheral peripheral = this;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {         
                
                    boolean success = gatt.discoverServices();
                    if (!success) {
                        LOG.e(TAG, "discoverServices() failed");
                        if (peripheral.connectCallback != null) {
                            peripheral.connectCallback.error("Service discovery failed");
                            peripheral.connectCallback = null;
                        }
                        peripheral.disconnect();
                    }
                }
            });

        } else {
            LOG.d(TAG, "disconnected");
            if (connectCallback != null) {
                connectCallback.error("Disconnected");
                connectCallback = null;
            }
            
            // make sure disconnect() doesn't call gatt.disconnect()
            connected = false;
            
            // cleanup anything that needs cleaning up
            disconnect();
            
            // close the handle
            gatt.close();
            this.gatt = null;           
        }

    }

    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            LOG.d(TAG, String.format("BluetoothGatt ReadRssi[%d]", rssi));
            if (connectCallback != null) {
                PluginResult result = new PluginResult(PluginResult.Status.OK, rssi);
                result.setKeepCallback(true);
                connectCallback.sendPluginResult(result);                
            }
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        LOG.d(TAG, "onCharacteristicChanged " + characteristic);

        CallbackContext callback = notificationCallbacks.get(generateHashKey(characteristic));

        if (callback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, characteristic.getValue());
            result.setKeepCallback(true);
            callback.sendPluginResult(result);
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        try {
            super.onCharacteristicRead(gatt, characteristic, status);
            LOG.d(TAG, "onCharacteristicRead " + characteristic);
    
            if (readCallback != null) {
    
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    readCallback.success(characteristic.getValue());
                } else {
                    readCallback.error("Error reading " + characteristic.getUuid() + " status=" + status);
                }
    
                readCallback = null;
    
            }
        } finally {
            commandCompleted();
        } 
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        try {
            super.onCharacteristicWrite(gatt, characteristic, status);
            LOG.d(TAG, "onCharacteristicWrite " + characteristic);
    
            if (writeCallback != null) {
    
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeCallback.success();
                } else {
                    writeCallback.error(status);
                }
    
                writeCallback = null;
            }
        } finally {
            commandCompleted();
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        try {
            super.onDescriptorWrite(gatt, descriptor, status);
            LOG.d(TAG, "onDescriptorWrite " + descriptor);
            
            CallbackContext context = notificationCallbacks.get(generateHashKey(descriptor.getCharacteristic()));
            if (context != null) {
                PluginResult result = new PluginResult(PluginResult.Status.ERROR, 0); // falsy means success
                result.setKeepCallback(true);
                context.sendPluginResult(result);
            }
        } finally {
            commandCompleted();
        }
    }

    public void updateRssi(int rssi) {
        advertisingRSSI = rssi;
    }

    // This seems way too complicated
    private void registerNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        boolean success = false;
        try {
    
            if (gatt == null) {
                callbackContext.error("BluetoothGatt is null");
                return; // note: this will call commandCompleted()
            }
    
            BluetoothGattService service = gatt.getService(serviceUUID);
            if (service == null) {
                callbackContext.error("register notify failed, service not found " + serviceUUID);
                return; // note: commandCompleted() will still get called
            }

            BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
            String key = generateHashKey(serviceUUID, characteristic);
    
            if (characteristic != null) {
    
                notificationCallbacks.put(key, callbackContext);
    
                if (gatt.setCharacteristicNotification(characteristic, true)) {
    
                    // Why doesn't setCharacteristicNotification write the descriptor?
                    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
                    if (descriptor != null) {
    
                        // prefer notify over indicate
                        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        } else if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                        } else {
                            LOG.w(TAG, "Characteristic " + characteristicUUID + " does not have NOTIFY or INDICATE property set");
                        }
    
                        if (gatt.writeDescriptor(descriptor)) {
                            success = true;
                        } else {
                            callbackContext.error("Failed to set client characteristic notification for " + characteristicUUID);
                        }
    
                    } else {
                        callbackContext.error("Set notification failed for " + characteristicUUID);
                    }
    
                } else {
                    callbackContext.error("Failed to register notification for " + characteristicUUID);
                }
    
            } else {
                callbackContext.error("Characteristic " + characteristicUUID + " not found");
            }
        } finally {
            if (!success) {
                commandCompleted();
            }
        }
    }

    private void readCharacteristic(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {

        boolean success = false;
        try {
            if (gatt == null) {
                callbackContext.error("BluetoothGatt is null");
                return; // note: commandCompleted() will still get called
            }
    
            BluetoothGattService service = gatt.getService(serviceUUID);
            if (service == null) {
                callbackContext.error("read characteristic failed, service not found " + serviceUUID);
                return; // note: commandCompleted() will still get called
            }            
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
    
            if (characteristic == null) {
                callbackContext.error("Characteristic " + characteristicUUID + " not found.");
            } else {
                readCallback = callbackContext;
                if (gatt.readCharacteristic(characteristic)) {
                    success = true;
                } else {
                    readCallback = null;
                    callbackContext.error("Read failed");
                }
            }
        } finally {
            if (!success) {
                commandCompleted();
            }
        }

    }

    private void writeCharacteristic(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data, int writeType) {

        boolean success = false;
        try {
    
            if (gatt == null) {
                callbackContext.error("BluetoothGatt is null");
                return; // note: commandCompleted() will still get called
            }
    
            BluetoothGattService service = gatt.getService(serviceUUID);
            if (service == null) {
                callbackContext.error("Write failed: service " + serviceUUID + " not found.");
                return; // note: commandCompleted() will still get called
            }
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
    
            if (characteristic == null) {
                callbackContext.error("Characteristic " + characteristicUUID + " not found.");
            } else {
                if (characteristic.setValue(data)) {
                    characteristic.setWriteType(writeType);
                    writeCallback = callbackContext;
        
                    if (gatt.writeCharacteristic(characteristic)) {
                        success = true;
                    } else {
                        writeCallback = null;
                        callbackContext.error("Write failed");
                    }
                }
                else {
                    callbackContext.error("Write failed (couldn't set characteristic)");
                }
            }
            
        } finally {
            if (!success) {
                commandCompleted();
            }
        }

    }

    public void queueRead(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.READ);
        queueCommand(command);
    }

    public void queueWrite(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data, int writeType) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, data, writeType);
        queueCommand(command);
    }

    public void queueRegisterNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.REGISTER_NOTIFY);
        queueCommand(command);
    }

    // add a new command to the queue
    private void queueCommand(BLECommand command) {
        LOG.d(TAG,"Queuing Command " + command);
        commandQueue.add(command);

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        command.getCallbackContext().sendPluginResult(result);

        if (!bleProcessing) {
            processCommands();
        }
    }

    // command finished, queue the next command
    private void commandCompleted() {
        LOG.d(TAG,"Processing Complete");
        bleProcessing = false;
        processCommands();
    }

    // process the queue
    private void processCommands() {
        LOG.d(TAG,"Processing Commands");

        if (bleProcessing) { return; }

        BLECommand command = commandQueue.poll();
        if (command != null) {
            if (command.getType() == BLECommand.READ) {
                LOG.d(TAG,"Read " + command.getCharacteristicUUID());
                bleProcessing = true;
                readCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else if (command.getType() == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                LOG.d(TAG,"Write " + command.getCharacteristicUUID());
                bleProcessing = true;
                writeCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID(), command.getData(), command.getType());
            } else if (command.getType() == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                LOG.d(TAG,"Write No Response " + command.getCharacteristicUUID());
                bleProcessing = true;
                writeCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID(), command.getData(), command.getType());
            } else if (command.getType() == BLECommand.REGISTER_NOTIFY) {
                LOG.d(TAG,"Register Notify " + command.getCharacteristicUUID());
                bleProcessing = true;
                registerNotifyCallback(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else {
                // this shouldn't happen
                throw new RuntimeException("Unexpected BLE Command type " + command.getType());
            }
        } else {
            LOG.d(TAG, "Command Queue is empty.");
        }

    }

    private String generateHashKey(BluetoothGattCharacteristic characteristic) {
        return generateHashKey(characteristic.getService().getUuid(), characteristic);
    }

    private String generateHashKey(UUID serviceUUID, BluetoothGattCharacteristic characteristic) {
        return String.valueOf(serviceUUID) + "|" + characteristic.getUuid() + "|" + characteristic.getInstanceId();
    }

}
