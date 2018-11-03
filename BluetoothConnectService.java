package gmf.zju.cn.sewingBLE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.io.IOException;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 */
@SuppressLint("NewApi")

public class BluetoothConnectService extends Activity implements Constant {
    // Debugging
    private static Context mContext;
    private static final String TAG = "BluetoothConnectService";
    private static final boolean D = true;
    public int BluetoothType;

    // Unique UUID for this application
    public static String ServiceUUID = "0000ffe0-0000-1000-8000-00805f9b34fb";
    public static String CharacterUUID = "0000ffe1-0000-1000-8000-00805f9b34fb";
    private static final UUID MY_UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");


    // Member fields
    private final BluetoothAdapter mAdapter;
    private BluetoothManager mBluetoothManager;
    public static BluetoothGatt Gatt;
    public static BluetoothSocket Socket;
    private Handler mHandler;
    private Handler mHandler2;
    private BluetoothDevice mBluetoothDevice;
    private ConnectThread mConnectThread;
    public static int mState = STATE_DISCONNECTED;
    private String mBluetoothDeviceAddress;
    private ConnectedThread mConnectedThread;
    private boolean isConnected = false;

    public BluetoothGattService service ;
    public BluetoothGattCharacteristic Characteristic;
    private boolean isrecing=false;
    public Queue<Byte> recbuff;

    private int readcount=0;
    private byte[] recData=new byte[20];
    private int reclen;
    public boolean isDownloading=false;
    public boolean isDataGet=false;

    public Queue<Byte> sendbuff;

    public class LocalBinder extends Binder {
        BluetoothConnectService getService() {
            return BluetoothConnectService.this;
        }
    }

    public IBinder onBind(Intent intent) {
        return mBinder;
    }


    private final IBinder mBinder = new LocalBinder();

    public void getService() {
        service = Gatt.getService(UUID.fromString(ServiceUUID));
        Log.i(TAG, "get service" + service.toString());
    }
    public void getCharacteristic() {
        Characteristic = service.getCharacteristic(UUID.fromString(CharacterUUID));
        /**Log.i(TAG, "get characteristic" + Characteristic.toString());*/
    }

    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (mAdapter == null || Gatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        Gatt.setCharacteristicNotification(characteristic, enabled);
        List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
        for (BluetoothGattDescriptor bgp : descriptors) {
            /**Log.i(TAG, "setCharacteristicNotification: " + bgp);*/
            bgp.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            Gatt.writeDescriptor(bgp);
        }
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setState(STATE_CONNECTED);
                isConnected = true;
                Gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setState(STATE_DISCONNECTED);
                isConnected = false;
                Log.i(TAG, "Disconnected from GATT server.");
                /**broadcastUpdate(ACTION_GATT_DISCONNECTED);*/
            }
        }
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.e("hh:", "成功发现服务");
                List<BluetoothGattService> supportedGattServices = gatt.getServices();
                for (BluetoothGattService gattService : supportedGattServices) {
                    //得到每个Service的Characteristics
                    List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
                    for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                        if(gattCharacteristic.getUuid().toString().equals("0000ffe1-0000-1000-8000-00805f9b34fb")){
                            Characteristic=gattCharacteristic;
                            setState(STATE_CONNECTED);
                        }
                    }
                }
                if(Characteristic==null)
                {
                    System.out.println("no mChatCharacteristic found");
                }
            }else{
                Log.e("hh:", "服务发现失败，错误码为:" + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                    setCharacteristicNotification(Characteristic, true);
                    Log.d(TAG, "read data success");
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            if(isDownloading){
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    //写入特征后回调到此处。
                    Log.e("write","写入成功");
                    int leftnum=sendbuff.size();
                    if(leftnum>0){
                        Log.e("状态","长数据帧继续");
                        if(leftnum<=20){
                            byte[] lastsd=new byte[leftnum];
                            for(int i=0;i<leftnum;i++){
                                lastsd[i]=sendbuff.poll();
                            }
                            Characteristic.setValue(lastsd);
                            Gatt.writeCharacteristic(Characteristic);//mBluetoothSer
                            Log.e("状态","长数据帧结束");
                            blogDataBuf(lastsd,leftnum,"BLE send:");
                        }else{
                            byte[] sd=new byte[20];
                            for(int sn=0;sn<20;sn++){
                                sd[sn]=sendbuff.poll();
                            }
                            Characteristic.setValue(sd);
                            Gatt.writeCharacteristic(Characteristic);//mBluetoothSer
                            blogDataBuf(sd,20,"BLE send:");
                        }
                        Gatt.setCharacteristicNotification(Characteristic,true);
                    }
                }else{
                    Log.e("write","写入错误");
                    MyLog.v(TAG, "写入失败");
                }
            }else {
                switch (status) {
                    case BluetoothGatt.GATT_SUCCESS:
                        /**Log.d(TAG, "write data success");*/
                        setCharacteristicNotification(Characteristic, true);
                        break;
                    case BluetoothGatt.GATT_FAILURE:
                        Log.d(TAG, "write data failed");
                        break;
                    case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
                        Log.d(TAG, "write not permitted");
                        break;
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            /**broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);*/
            if(isDownloading==true){
                //当特征（值）发生变法时回调到此处。
                byte[] rec=characteristic.getValue();
                byte sasa;
                recData=rec;
                reclen=rec.length;
                Log.e("onCharacteristicChanged","changdushi:"+reclen);
                for(int i=0;i<reclen;i++){
                    sasa=recData[i];
                    recbuff.offer(sasa);
                }
                isDataGet=true;
            }else{
                if (BluetoothConnectService.this.mHandler2 != null){
                    byte[] byteArray = characteristic.getValue();
                    mHandler2.obtainMessage(MESSAGE_READ, byteArray.length, -1, byteArray).sendToTarget();
                    Log.d(TAG, "读取数据成功 read data success==========================" + byteArray.length );
                    /**Log.i("收到String",ConvertUtils.getInstance().bytesToHexString(byteArray));*/
                }
                else Log.d(TAG, "数据 handler null");
            }
        }
    };

    public synchronized void read(){
        byte[] byteArray = Characteristic.getValue();
        mHandler2.obtainMessage(MESSAGE_READ, byteArray.length, -1, byteArray).sendToTarget();
        Log.d(TAG, "读取数据成功 read data success");
        Log.i("收到String",ConvertUtils.getInstance().bytesToHexString(byteArray));
    }

    public int dread(byte[] in) {
        int bytes;
        ConnectedThread r;
        synchronized (this) {
            if (mState != STATE_CONNECTED) {
                MyLog.e(TAG, "读蓝牙时不处于已连接状态");
                return -1;
            }
            r = mConnectedThread;
        }
        bytes = r.read(in);
        return bytes;
    }

    /**
     *
     * @param byteArray
     * @return
     */
    /**
    public boolean write(byte byteArray[]) {
        boolean SetSuccess,WriteSuccess;
        byte byteSend[] = new byte[20];
        int num = 0;
        int sendtimes = byteArray.length / 20;;
        int lastLen = byteArray.length % 20;
        byte byteLast[] = new byte[lastLen];

        if (Gatt != null && Characteristic != null) {
            while(sendtimes > 0) {
                System.arraycopy(byteArray, num, byteSend, 0, 20);
                SetSuccess = Characteristic.setValue(byteSend);
                Log.d(TAG,"开始写入特征值");
                WriteSuccess = Gatt.writeCharacteristic(Characteristic);
                Log.d(TAG,"写入特征值结束");
                while(!WriteSuccess) {
                    Log.d(TAG,"发送失败++++++++++++++++++++++++");
                    WriteSuccess = Gatt.writeCharacteristic(Characteristic);
                }
                num += 20;
                sendtimes -= 1;
                Log.d(TAG,"发送指令" + num + "/" + byteArray.length + "完成SetSucess=" + SetSuccess + ";WriteSucess=" + WriteSuccess +"-----------");
                try{
                    Thread.sleep(10);
                    Log.d(TAG,"间隔延时");
                }catch(Exception e){
                    Log.d(TAG,"延时失败");
                }
            }
            if (lastLen != 0){

                System.arraycopy(byteArray, num, byteLast, 0, lastLen);
                SetSuccess = Characteristic.setValue(byteLast);
                WriteSuccess = Gatt.writeCharacteristic(Characteristic);
                while(!WriteSuccess) {
                    Log.d(TAG,"发送失败++++++++++++++++++++++++");
                    WriteSuccess = Gatt.writeCharacteristic(Characteristic);
                }
                num += lastLen;
                Log.d(TAG,"发送指令" + num + "/" + byteArray.length + "完成SetSucess=" + SetSuccess + ";WriteSucess=" + WriteSuccess + "-----------------------------------");
            }
            //这里应该反馈给mainActivity中
            mHandler2.sendEmptyMessage(MESSAGE_WRITE);
            return true;
        }
        return false;
    }*/
    /**
     *
     * @param str
     * @return
     */
    /**
    public boolean write(String str) {
        if (Gatt != null && Characteristic != null) {
            boolean isSetSucess = Characteristic.setValue(str);
            boolean isWriteSucess = Gatt.writeCharacteristic(Characteristic);
            if (!isWriteSucess) {
                isWriteSucess = Gatt.writeCharacteristic(Characteristic);
            }
            // 发送指令完成
            Log.d(TAG,"发送指令完成isSetSucess=" + isSetSucess + ";isWriteSucess=" + isWriteSucess);
            if (isWriteSucess) {
                //这里应该反馈给mainActivity中
                mHandler2.sendEmptyMessage(MESSAGE_WRITE);
                return true;
            }
        }
        return false;
    }*/
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED)
                return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }
    public void dwrite(byte[] out, int offset, int count) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED)
                return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.dwrite(out, offset, count);
    }

    /**
     * Constructor. Prepares a new BluetoothChat session.
     * @param handler  A Handler to send messages back to the UI Activity
     */
    public BluetoothConnectService(Handler handler) {
        recbuff=new LinkedBlockingQueue<>();
        sendbuff=new LinkedBlockingQueue<>();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_DISCONNECTED;
        mHandler = handler;
    }

    public void ChangeHandler(Handler handler) {
        mHandler2 = handler;
    }
    /**
     * Set the current state of the chat connection
     * @param state  An integer defining the current connection state
     */
    public synchronized void setState(int state) {
        if (D) Log.i(TAG, "setState() " + mState + " -> " + state);
        mState = state;
        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(MESSAGE_STATE_CHANGE, mState, -1).sendToTarget();
    }

    /**
     * Return the current connection state. */
    public synchronized static int getState() {
        return mState;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    /**
    public boolean mGattconnect(final String address) {
        if (mAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }
        final BluetoothDevice device = mAdapter.getRemoteDevice(address);
        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && Gatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (Gatt.connect()) {
                connected(device);
                return true;
            } else {
                connectionFailed();
                return false;
            }
        }
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            connectionFailed();
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        else{
            Log.d(TAG, "Trying to create a new connection.");
            Gatt = device.connectGatt(this, false, mGattCallback);
            connected(device);
            mBluetoothDeviceAddress = address;
            return true;
        }
    }*/

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    /**
    public void disconnect() {
        if (mAdapter == null || Gatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        Gatt.disconnect();
    }*/

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */

    public synchronized void connect(BluetoothDevice device) {
        if (D)
            Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        mBluetoothDevice=device;
        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }
    /**
    public synchronized void connect(BluetoothDevice device) {
        if (D) {
            Log.i(TAG, "connect to: " + device);
        }
        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        }
        setState(STATE_CONNECTING);
        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
    }*/
    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     * @param device  The BluetoothDevice to connect
     */
    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param device  The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothDevice device) {
        if (D) Log.i(TAG, "connected");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        if (mConnectedThread != null) { mConnectedThread.cancel();mConnectedThread = null; }
        mConnectedThread = new ConnectedThread(device);
        mConnectedThread.start();
        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(STATE_CONNECTED);
    }

    /**
     * Stop all threads
     */
    /*
    public synchronized void stop() {
        if (D) Log.i(TAG, "stop");
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        if (mState == STATE_CONNECTED) Gatt.disconnect();
        setState(STATE_DISCONNECTED);
    }
*/
    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
        setState(STATE_DISCONNECTED);

        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    /**
    private class ConnectThread extends Thread {
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            if (Gatt != null) {
                Gatt.disconnect();
                Gatt = null;
            }
            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                Gatt = mmDevice.connectGatt(BluetoothConnectService.this, false, mGattCallback);
                connected(mmDevice);
            } catch (Exception e) {
                Log.e(TAG, "create() failed", e);
            }
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectThread");
            setName("ConnectThread");

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                if (Gatt != null && mState == STATE_CONNECTED)
                    Log.i(TAG,"ConnectThread中run方法mBluetoothGatt的连接状态" );
            } catch (Exception e) {
                connectionFailed();
                // Close the socket
                try {
                    Gatt.close();
                } catch (Exception e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                // Start the service over to restart listening mode
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothConnectService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
        }

        public void cancel() {
            try {
                Gatt.close();
                Log.e(TAG, "connect Gatt closed");
            } catch (Exception e) {
                Log.e(TAG, "close() of connect Gatt failed", e);
            }
        }
    }*/
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        if (D)
            Log.d(TAG, "connected Socket");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
        // mConnectedThread.start();

        // Send the name of the connected device back to the UI Activity

        Message msg = mHandler.obtainMessage(MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(STATE_CONNECTED);
    }
    private class ConnectThread extends Thread {
        private  BluetoothSocket mmSocket;
        private  BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device) {
            recbuff.clear();
            sendbuff.clear();
            mmDevice = device;
            if(mmDevice.getType()==BluetoothDevice.DEVICE_TYPE_CLASSIC){
                BluetoothType = 2;
                System.out.println("2.02.02.02.02.02.02.02.02.02.02.02.02.0");
                BluetoothSocket tmp = null;
                mSocketType = "SPP";
                // Get a BluetoothSocket for a connection with the
                // given BluetoothDevice
                try {
                    tmp = device.createRfcommSocketToServiceRecord(MY_UUID_SPP);
                } catch (IOException e) {
                    Log.e(TAG, "Socket Type: " + mSocketType + "create() failed", e);
                }
                mmSocket = tmp;
            }else if(mmDevice.getType()==BluetoothDevice.DEVICE_TYPE_LE){
                BluetoothType = 4;
                System.out.println("4.04.04.04.04.04.04.04.04.04.04.04.04.0");
            }else {
                //return 0;
            }
        }

        public void run() {
            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();
            if(mmDevice.getType()==BluetoothDevice.DEVICE_TYPE_CLASSIC){
                Log.i(TAG, "BEGIN mConnectThread SocketType:" + mSocketType);
                BluetoothType = 2;
                setName("ConnectThread" + mSocketType);
                // Always cancel discovery because it will slow down a connection
                // Make a connection to the BluetoothSocket
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    mmSocket.connect();
                    Socket = mmSocket;
                    Log.i(TAG, "connected SocketType:" + mSocketType);
                } catch (IOException e) {
                    // Close the socket
                    try {
                        mmSocket.close();
                    } catch (IOException e2) {
                        Log.e(TAG, "unable to close() " + mSocketType
                                + " socket during connection failure", e2);
                    }
                    connectionFailed();
                    return;
                }

                // Reset the ConnectThread because we're done
                synchronized (BluetoothConnectService.this) {
                    mConnectThread = null;
                }
                // Start the connected thread
                connected(mmSocket, mmDevice);
            }else if(mmDevice.getType()==BluetoothDevice.DEVICE_TYPE_LE){
                // Reset the ConnectThread because we're done
                Gatt = mmDevice.connectGatt(mContext, false, mGattCallback);
                try{
                    Thread.sleep(2000);
                    Log.d(TAG,"间隔延时");
                }catch(Exception e){
                    Log.d(TAG,"延时失败");
                }
                if(isConnected){
                    BluetoothType = 4;
                    synchronized (BluetoothConnectService.this) {
                        mConnectThread = null;
                    }
                    connected( mmDevice);
                }
                else {
                    connectionFailed();
                    return;
                }

                // Start the connected thread
            }

        }

        public void cancel() {
            recbuff.clear();
            sendbuff.clear();
        }
    }
    public synchronized void stop() {
        if (D)
            Log.d(TAG, "stop");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        setState(STATE_DISCONNECTED);
    }

    private void blogDataBuf(byte[] buf, int len, String prefixMsg) {
        StringBuffer out = new StringBuffer(prefixMsg);
        for (int i = 0; i < len; i++) {
            out.append(String.format(",0x%x", buf[i]));
        }
        Log.e("ble send",out.toString());
    }

    private class ConnectedThread extends Thread {
        private boolean runFlag;
        private BluetoothSocket mmSocket;
        private InputStream mmInStream;
        private OutputStream mmOutStream;
        private int type;

        public ConnectedThread(BluetoothDevice device) {
            type=4;
            Log.d(TAG, "create ConnectedThread: " + device);


            System.out.println(Gatt.connect());
            //mBluetoothGatt.connect();
        }

        public ConnectedThread(BluetoothSocket socket) {
            type=2;
            runFlag = true;
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[108];
            int bytes;
            // Keep listening to the InputStream while connected
            while (runFlag) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);
                    Log.e(TAG, "收到数据");
                    //Log.i("READ", "data received");
                    // Send the obtained bytes to the UI Activity
                    mHandler2.obtainMessage(MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();
                    Thread.sleep(100);
                } catch (Exception e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    e.printStackTrace();
                    break;
                }
            }
        }

        /**
         * Write to the connected OutStream.
         *
         * @param buffer
         *            The bytes to write
         */
        public void write(byte[] buffer) {
            if(type==4){
                boolean SetSuccess,WriteSuccess;
                byte byteSend[] = new byte[20];
                int num = 0;
                int sendtimes = buffer.length / 20;;
                int lastLen = buffer.length % 20;
                byte byteLast[] = new byte[lastLen];

                if (Gatt != null && Characteristic != null) {
                    while(sendtimes > 0) {
                        System.arraycopy(buffer, num, byteSend, 0, 20);
                        SetSuccess = Characteristic.setValue(byteSend);
                        Log.d(TAG,"开始写入特征值");
                        WriteSuccess = Gatt.writeCharacteristic(Characteristic);
                        while(!WriteSuccess) {
                            Log.d(TAG,"发送失败++++++++++++++++++++++++");
                            WriteSuccess = Gatt.writeCharacteristic(Characteristic);
                        }
                        Log.d(TAG,"写入特征值结束");
                        num += 20;
                        sendtimes -= 1;
                        Log.d(TAG,"发送指令" + num + "/" + buffer.length + "完成SetSucess=" + SetSuccess + ";WriteSucess=" + WriteSuccess +"-----------"+Util.byte2Str(byteSend,byteSend.length));
                        try{
                            Thread.sleep(10);
                            Log.d(TAG,"间隔延时");
                        }catch(Exception e){
                            Log.d(TAG,"延时失败");
                        }
                    }
                    if (lastLen != 0){

                        System.arraycopy(buffer, num, byteLast, 0, lastLen);
                        SetSuccess = Characteristic.setValue(byteLast);
                        WriteSuccess = Gatt.writeCharacteristic(Characteristic);
                        while(!WriteSuccess) {
                            Log.d(TAG,"发送失败++++++++++++++++++++++++");
                            WriteSuccess = Gatt.writeCharacteristic(Characteristic);
                        }
                        num += lastLen;
                        Log.d(TAG,"发送指令" + num + "/" + buffer.length + "完成SetSucess=" + SetSuccess + ";WriteSucess=" + WriteSuccess + "-----------------------------------"+Util.byte2Str(byteLast,byteLast.length));
                    }
                    //这里应该反馈给mainActivity中
                    mHandler2.sendEmptyMessage(MESSAGE_WRITE);
                    return;
                }
                return;
            }else if(type==2){
                try {
                    mmOutStream.write(buffer);
                } catch (IOException e) {
                    Log.e(TAG, "Exception during write", e);
                }
            }

        }


        public void dwrite(byte[] buffer, int offset, int count) {
            if(type==4){
                while(sendbuff.size()>0){
                }
                if(count<=20){
                    byte[] lastsd=new byte[count];
                    System.arraycopy(buffer, 0, lastsd, 0, count);
//					System.arraycopy(buffer, sendednum*20, sd, 0, 20);
//						mBluetoothGatt.setCharacteristicNotification(mChatCharacteristic,true);
                    Characteristic.setValue(lastsd);
                    Gatt.writeCharacteristic(Characteristic);//mBluetoothSer
                    Log.e("状态","短数据帧开始");
                    blogDataBuf(lastsd,count,"BLE send:");
                }else{
                    for(int sn=20;sn<count;sn++){
                        sendbuff.offer(buffer[sn]);
                    }
                    byte[] sd=new byte[20];
                    System.arraycopy(buffer, 0, sd, 0, 20);
                    Characteristic.setValue(sd);		//onCharacteristicWrite
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Gatt.writeCharacteristic(Characteristic);//mBluetoothSer
                        }
                    }).start();

                    Log.e("状态","长数据帧开始");
                    blogDataBuf(sd,20,"BLE send:");
                }
                Gatt.setCharacteristicNotification(Characteristic,true);
            }else if(type==2){
                try {
                    mmOutStream.write(buffer);
                } catch (IOException e) {
                    Log.e(TAG, "Exception during write", e);
                }
            }

        }

        //先测试用s'y'n'h
        public int read(byte[] buffer) {
            int bytes=0;
            Byte lsls;
            int nnnnn=0;
            if(type==4){
                int recsumlem=0;
                while (isDataGet==false&&isConnected==true){
                    if(isConnected==false){
                        Log.e(TAG,"连接断开，读取已被停止");
                        return 0;
                    }
                }
                isDataGet=false;
                Log.e("BLE","数据来了，现在来取数据"+readcount);
                nnnnn=0;
                while (recbuff.size()>0){
                    nnnnn++;
                    lsls=recbuff.poll();
                    if(lsls!=null){
                        buffer[recsumlem]=lsls;
                    }else{
                        MyLog.v(TAG,"read到一个空的");
                    }
                    recsumlem++;
                    if(recsumlem>=40){
                        break;
                    }
                }
                MyLog.v(TAG,"read结束了，一个"+nnnnn+"个");
//				isrecing=false;
                bytes=recsumlem;
//				StringBuilder buf = new StringBuilder(recsumlem * 2);
//				String hhhas=new String();
//				for(int i=0;i<recsumlem;i++) { // 使用String的format方法进行转换
//					byte b=(byte) (buffer[i]);
//					buf.append(String.format("%02x", new Integer(b & 0xff)));
//					buf.append(' ');
//				}
//				Log.e("BLE","length is:"+recsumlem);
//				Log.e("BLE","data is:"+buf.toString());
//                blogDataBuf(buffer,recsumlem,"BLE rec:");
            }else if(type==2){
                try {
                    bytes = mmInStream.read(buffer);
                } catch (IOException e) {
                    MyLog.e(TAG, " 蓝牙 disconnected");
                    connectionLost();
                    return -1;
                }
            }
            readcount--;
            return bytes;
        }

        public void cancel() {
        }
    }

    private void connectionLost() {
        // Send a failure message back to the Activity
        Message msg = mHandler
                .obtainMessage(MainActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(MainActivity.TOAST, "设备连接断开");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(STATE_DISCONNECTED);
    }
}

