package com.example.bluetooth_print;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.gprinter.command.FactoryCommand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/** BluetoothPrintPlugin (Embedding V2) */
public class BluetoothPrintPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
  private static final String TAG = "BluetoothPrintPlugin";
  private static final String NAMESPACE = "bluetooth_print";
  private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;

  private Context context;
  private Activity activity;
  private MethodChannel channel;
  private EventChannel stateChannel;

  private BluetoothManager mBluetoothManager;
  private BluetoothAdapter mBluetoothAdapter;
  private ThreadPool threadPool;
  private int id = 0;

  private MethodCall pendingCall;
  private Result pendingResult;

  // ---------------- Flutter Plugin Lifecycle ----------------

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    context = binding.getApplicationContext();
    channel = new MethodChannel(binding.getBinaryMessenger(), NAMESPACE + "/methods");
    stateChannel = new EventChannel(binding.getBinaryMessenger(), NAMESPACE + "/state");
    channel.setMethodCallHandler(this);
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
    channel = null;
    stateChannel = null;
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activity = binding.getActivity();
    mBluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
    mBluetoothAdapter = mBluetoothManager.getAdapter();
    stateChannel.setStreamHandler(stateStreamHandler);
    binding.addRequestPermissionsResultListener((requestCode, permissions, grantResults) -> {
      if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          startScan(pendingCall, pendingResult);
        } else if (pendingResult != null) {
          pendingResult.error("no_permissions", "location permission denied", null);
        }
        return true;
      }
      return false;
    });
  }

  @Override
  public void onDetachedFromActivity() {
    activity = null;
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity();
  }

  // ---------------- MethodChannel handler ----------------

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    if (mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
      result.error("bluetooth_unavailable", "Bluetooth not available", null);
      return;
    }

    final Map<String, Object> args = call.arguments();

    switch (call.method) {
      case "state":
        state(result);
        break;
      case "isAvailable":
        result.success(mBluetoothAdapter != null);
        break;
      case "isOn":
        result.success(mBluetoothAdapter.isEnabled());
        break;
      case "isConnected":
        result.success(threadPool != null);
        break;
      case "startScan":
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
          ActivityCompat.requestPermissions(
              activity,
              new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
              REQUEST_COARSE_LOCATION_PERMISSIONS);
          pendingCall = call;
          pendingResult = result;
        } else {
          startScan(call, result);
        }
        break;
      case "stopScan":
        stopScan();
        result.success(null);
        break;
      case "connect":
        connect(result, args);
        break;
      case "disconnect":
        result.success(disconnect());
        break;
      case "destroy":
        result.success(destroy());
        break;
      case "print":
      case "printReceipt":
      case "printLabel":
        print(result, args);
        break;
      case "printTest":
        printTest(result);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  // ---------------- Core methods ----------------

  private void state(Result result) {
    try {
      result.success(mBluetoothAdapter.getState());
    } catch (Exception e) {
      result.error("bluetooth_state_error", e.getMessage(), null);
    }
  }

  private void startScan(MethodCall call, Result result) {
    try {
      BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
      if (scanner == null)
        throw new IllegalStateException("BluetoothLeScanner is null (is Bluetooth on?)");
      ScanSettings settings =
          new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
      scanner.startScan(null, settings, mScanCallback);
      result.success(null);
    } catch (Exception e) {
      result.error("startScan", e.getMessage(), null);
    }
  }

  private void stopScan() {
    BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
    if (scanner != null) scanner.stopScan(mScanCallback);
  }

  private final ScanCallback mScanCallback =
      new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
          BluetoothDevice device = result.getDevice();
          if (device != null && device.getName() != null) {
            Map<String, Object> map = new HashMap<>();
            map.put("address", device.getAddress());
            map.put("name", device.getName());
            map.put("type", device.getType());
            if (activity != null)
              activity.runOnUiThread(() -> channel.invokeMethod("ScanResult", map));
          }
        }
      };

  private void connect(Result result, Map<String, Object> args) {
    if (!args.containsKey("address")) {
      result.error("invalid_argument", "address missing", null);
      return;
    }
    String address = (String) args.get("address");
    disconnect();
    new DeviceConnFactoryManager.Build()
        .setId(id)
        .setConnMethod(DeviceConnFactoryManager.CONN_METHOD.BLUETOOTH)
        .setMacAddress(address)
        .build();
    threadPool = ThreadPool.getInstantiation();
    threadPool.addSerialTask(() -> DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].openPort());
    result.success(true);
  }

  private boolean disconnect() {
    if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id] != null
        && DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort != null) {
      DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].reader.cancel();
      DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort.closePort();
      DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort = null;
    }
    return true;
  }

  private boolean destroy() {
    DeviceConnFactoryManager.closeAllPort();
    if (threadPool != null) threadPool.stopThreadPool();
    return true;
  }

  private void printTest(Result result) {
    if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id] == null
        || !DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].getConnState()) {
      result.error("not connect", "printer not connected", null);
      return;
    }
    threadPool = ThreadPool.getInstantiation();
    threadPool.addSerialTask(() -> {
      switch (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].getCurrentPrinterCommand()) {
        case ESC:
          DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id]
              .sendByteDataImmediately(FactoryCommand.printSelfTest(FactoryCommand.printerMode.ESC));
          break;
        case TSC:
          DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id]
              .sendByteDataImmediately(FactoryCommand.printSelfTest(FactoryCommand.printerMode.TSC));
          break;
        case CPCL:
          DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id]
              .sendByteDataImmediately(FactoryCommand.printSelfTest(FactoryCommand.printerMode.CPCL));
          break;
      }
    });
  }

  @SuppressWarnings("unchecked")
  private void print(Result result, Map<String, Object> args) {
    if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id] == null
        || !DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].getConnState()) {
      result.error("not connect", "printer not connected", null);
      return;
    }
    if (!args.containsKey("config") || !args.containsKey("data")) {
      result.error("invalid_args", "config or data missing", null);
      return;
    }
    final Map<String, Object> config = (Map<String, Object>) args.get("config");
    final List<Map<String, Object>> list = (List<Map<String, Object>>) args.get("data");
    threadPool = ThreadPool.getInstantiation();
    threadPool.addSerialTask(() -> {
      switch (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].getCurrentPrinterCommand()) {
        case ESC:
          DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id]
              .sendDataImmediately(PrintContent.mapToReceipt(config, list));
          break;
        case TSC:
          DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id]
              .sendDataImmediately(PrintContent.mapToLabel(config, list));
          break;
        case CPCL:
          DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id]
              .sendDataImmediately(PrintContent.mapToCPCL(config, list));
          break;
      }
    });
  }

  // ---------------- State Stream ----------------

  private final EventChannel.StreamHandler stateStreamHandler =
      new EventChannel.StreamHandler() {
        private EventChannel.EventSink sink;
        private final BroadcastReceiver receiver =
            new BroadcastReceiver() {
              @Override
              public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (sink == null) return;
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                  threadPool = null;
                  sink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                  sink.success(1);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                  threadPool = null;
                  sink.success(0);
                }
              }
            };

        @Override
        public void onListen(Object args, EventChannel.EventSink events) {
          sink = events;
          if (activity == null) return;
          IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
          filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
          filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
          activity.registerReceiver(receiver, filter);
        }

        @Override
        public void onCancel(Object args) {
          sink = null;
          if (activity != null) activity.unregisterReceiver(receiver);
        }
      };
}
