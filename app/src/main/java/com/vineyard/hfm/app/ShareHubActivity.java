package com.vineyard.hfm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// --- FIX 1: Change Activity to FragmentActivity to support modern permission APIs ---
public class ShareHubActivity extends FragmentActivity implements WifiP2pManager.ChannelListener, WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener {

    private static final String TAG = "ShareHubActivity";
    private static final int CATEGORY_PICKER_REQUEST_CODE = 200;
    public static final String ACTION_DISCONNECT_WIFI_P2P = "com.vineyard.hfm.app.DISCONNECT_WIFI_P2P";

    // --- FIX 2: Modern permission handling launchers ---
    private ActivityResultLauncher<Intent> storagePermissionLauncher;
    private ActivityResultLauncher<String[]> runtimePermissionsLauncher;
    private ArrayList<String> requiredPermissions = new ArrayList<>();


    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver = null;
    private final IntentFilter intentFilter = new IntentFilter();
    private boolean isWifiP2pEnabled = false;

    private WifiP2pInfo connectionInfo;
    private List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    private DeviceListAdapter deviceListAdapter;

    private TextView statusTextView;
    private Button sendButton, receiveButton;
    private RecyclerView devicesRecyclerView;

    private boolean isTransferInProgress = false;
    private BroadcastReceiver disconnectReceiver;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_hub);

        initializeViews();
        setupWifiDirect();
        // --- FIX 3: Initialize the new permission launchers ---
        initializePermissionLaunchers();
        setupListeners();
        setupDisconnectReceiver();

        // Start the permission check flow
        checkAndRequestPermissions();
    }

    private void initializeViews() {
        statusTextView = findViewById(R.id.status_text);
        sendButton = findViewById(R.id.button_send);
        receiveButton = findViewById(R.id.button_receive);
        devicesRecyclerView = findViewById(R.id.devices_recycler_view);

        deviceListAdapter = new DeviceListAdapter(this, peers);
        devicesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        devicesRecyclerView.setAdapter(deviceListAdapter);
    }

    private void setupWifiDirect() {
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
    }
    
    // --- FIX 4: The new, correct way to handle permissions sequentially ---
    private void initializePermissionLaunchers() {
        // Launcher for standard runtime permissions (Location, Wi-Fi, etc.)
        runtimePermissionsLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            new ActivityResultCallback<Map<String, Boolean>>() {
                @Override
                public void onActivityResult(Map<String, Boolean> result) {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }

                    if (allGranted) {
                        onAllPermissionsGranted();
                    } else {
                        Toast.makeText(ShareHubActivity.this, "Some permissions were denied. The feature may not work correctly.", Toast.LENGTH_LONG).show();
                    }
                }
            });

        // Launcher for the special "All Files Access" setting screen
        storagePermissionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    // After returning from settings, check the permission again.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (Environment.isExternalStorageManager()) {
                            // Storage permission is now granted, now request the runtime permissions.
                            runtimePermissionsLauncher.launch(requiredPermissions.toArray(new String[0]));
                        } else {
                            Toast.makeText(ShareHubActivity.this, "All Files Access is required to save received files.", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            });
    }
    
    private void checkAndRequestPermissions() {
        // Step 1: Check for Storage Permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            if (!Environment.isExternalStorageManager()) {
                // If storage permission is missing, show a dialog explaining why it's needed.
                new AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("HFM Share needs 'All Files Access' permission to save received files. Please grant this permission in the next screen.")
                    .setPositiveButton("Grant", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // Launch the settings screen using the launcher.
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
                            storagePermissionLauncher.launch(intent);
                        }
                    })
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                             finish();
                        }
                    })
                    .setCancelable(false)
                    .show();
                return; // Stop here and wait for the user to return from settings.
            }
        }

        // Step 2: Build the list of required runtime permissions
        requiredPermissions.clear();
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { // Android 10 and below (e.g., Huawei Nova 7 SE / EMUI 10.1 API 29)
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        // Step 3: Check which runtime permissions are still needed
        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        
        // Step 4: Request missing runtime permissions or finalize if all are granted
        if (!permissionsToRequest.isEmpty()) {
            runtimePermissionsLauncher.launch(permissionsToRequest.toArray(new String[0]));
        } else {
            // All permissions are already granted.
            onAllPermissionsGranted();
        }
    }
    
    private void onAllPermissionsGranted() {
        // This function is called only when all necessary permissions are confirmed.
        // It's now safe to register the receiver and use Wi-Fi features.
        Toast.makeText(this, "Permissions granted. Ready to share.", Toast.LENGTH_SHORT).show();
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
        registerReceiver(receiver, intentFilter);
    }


    private void setupListeners() {
        sendButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					discoverPeers();
				}
			});

        receiveButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					createGroup();
				}
			});
    }

    private void setupDisconnectReceiver() {
        disconnectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_DISCONNECT_WIFI_P2P.equals(intent.getAction())) {
                    disconnect();
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(disconnectReceiver, new IntentFilter(ACTION_DISCONNECT_WIFI_P2P));
    }


    public void setIsWifiP2pEnabled(boolean isWifiP2pEnabled) {
        this.isWifiP2pEnabled = isWifiP2pEnabled;
    }

    public void setTransferStatus(boolean status) {
        this.isTransferInProgress = status;
    }

    private void discoverPeers() {
        if (!isWifiP2pEnabled) {
            Toast.makeText(this, "Enable P2P from settings", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission is required for discovery.", Toast.LENGTH_SHORT).show();
            return;
        }
        statusTextView.setText("Discovering devices...");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
				@Override
				public void onSuccess() {
					Toast.makeText(ShareHubActivity.this, "Discovery Initiated", Toast.LENGTH_SHORT).show();
				}

				@Override
				public void onFailure(int reasonCode) {
					Toast.makeText(ShareHubActivity.this, "Discovery Failed: " + reasonCode, Toast.LENGTH_SHORT).show();
				}
			});
    }

    private void createGroup() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
             Toast.makeText(this, "Location permission is required to create a group.", Toast.LENGTH_SHORT).show();
             return;
        }
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
				@Override
				public void onSuccess() {
					statusTextView.setText("Hosting. Ready to receive files.");
					Toast.makeText(ShareHubActivity.this, "Device is now discoverable.", Toast.LENGTH_SHORT).show();
				}

				@Override
				public void onFailure(int reason) {
					Toast.makeText(ShareHubActivity.this, "Could not create group: " + reason, Toast.LENGTH_SHORT).show();
				}
			});
    }

    @Override
    public void onPeersAvailable(WifiP2pDeviceList peerList) {
        List<WifiP2pDevice> refreshedPeers = new ArrayList<WifiP2pDevice>(peerList.getDeviceList());
        if (!refreshedPeers.equals(peers)) {
            peers.clear();
            peers.addAll(refreshedPeers);
            deviceListAdapter.notifyDataSetChanged();
            if (peers.size() == 0) {
                Log.d(TAG, "No devices found");
                statusTextView.setText("No devices found.");
                return;
            }
        }
    }

    public void connect(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission is required to connect.", Toast.LENGTH_SHORT).show();
            return;
        }
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
				@Override
				public void onSuccess() {
					// WiFiDirectBroadcastReceiver will notify us.
				}

				@Override
				public void onFailure(int reason) {
					Toast.makeText(ShareHubActivity.this, "Connect failed. Retry.", Toast.LENGTH_SHORT).show();
				}
			});
    }

    @Override
    public void onConnectionInfoAvailable(final WifiP2pInfo info) {
        if (isTransferInProgress) {
            return;
        }

        this.connectionInfo = info;

        if (info.groupFormed && !info.isGroupOwner) {
            statusTextView.setText("Connected. Select files to send.");
            Intent intent = new Intent(this, CategoryPickerActivity.class);
            startActivityForResult(intent, CATEGORY_PICKER_REQUEST_CODE);
        } else if (info.groupFormed) {
            isTransferInProgress = true;
            statusTextView.setText("Connected. Waiting for files...");
            Intent serviceIntent = new Intent(this, FileTransferService.class);
            serviceIntent.setAction(FileTransferService.ACTION_RECEIVE_FILES);
            serviceIntent.putExtra(FileTransferService.EXTRA_GROUP_OWNER_ADDRESS, info.groupOwnerAddress.getHostAddress());
            startService(serviceIntent);

            Intent progressIntent = new Intent(this, TransferProgressActivity.class);
            progressIntent.setAction(FileTransferService.ACTION_RECEIVE_FILES);
            startActivity(progressIntent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CATEGORY_PICKER_REQUEST_CODE && resultCode == RESULT_OK) {
            ArrayList<String> filePaths = data.getStringArrayListExtra("picked_files");
            if (filePaths != null && !filePaths.isEmpty()) {
                if (this.connectionInfo != null && this.connectionInfo.groupOwnerAddress != null) {
                    isTransferInProgress = true;
                    Intent serviceIntent = new Intent(this, FileTransferService.class);
                    serviceIntent.setAction(FileTransferService.ACTION_SEND_FILES);
                    serviceIntent.putStringArrayListExtra(FileTransferService.EXTRA_FILE_PATHS, filePaths);
                    serviceIntent.putExtra(FileTransferService.EXTRA_GROUP_OWNER_ADDRESS, this.connectionInfo.groupOwnerAddress.getHostAddress());
                    startService(serviceIntent);

                    Intent progressIntent = new Intent(ShareHubActivity.this, TransferProgressActivity.class);
                    progressIntent.putStringArrayListExtra(FileTransferService.EXTRA_FILE_PATHS, filePaths);
                    startActivity(progressIntent);
                } else {
                    Toast.makeText(this, "Connection lost. Please reconnect and try again.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    public void disconnect() {
        if (manager != null && channel != null) {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
					@Override
					public void onSuccess() {
						Log.d(TAG, "P2P group removed.");
					}

					@Override
					public void onFailure(int reason) {
						Log.d(TAG, "P2P group removal failed. Reason: " + reason);
					}
				});
        }
    }


    public void resetDeviceList() {
        peers.clear();
        deviceListAdapter.notifyDataSetChanged();
        statusTextView.setText("Ready to connect");
        this.connectionInfo = null;
    }

    public void updateThisDevice(WifiP2pDevice device) {
        // You can update UI with this device's info if needed
    }

    @Override
    public void onChannelDisconnected() {
        Toast.makeText(this, "Channel disconnected. Try again.", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The receiver is now registered only after all permissions are granted.
        if (receiver == null) {
            checkAndRequestPermissions();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiver != null) {
            unregisterReceiver(receiver);
            // Set receiver to null so onResume knows to re-check permissions
            receiver = null; 
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (disconnectReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(disconnectReceiver);
        }
    }
    
    public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {
        private Context context;
        private List<WifiP2pDevice> devices;

        public DeviceListAdapter(Context context, List<WifiP2pDevice> devices) {
            this.context = context;
            this.devices = devices;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_device, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final WifiP2pDevice device = devices.get(position);
            holder.deviceName.setText(device.deviceName);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						((ShareHubActivity) context).connect(device);
					}
				});
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            ImageView deviceIcon;
            TextView deviceName;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                deviceIcon = itemView.findViewById(R.id.device_icon);
                deviceName = itemView.findViewById(R.id.device_name);
            }
        }
    }
}