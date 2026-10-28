package org.telegram.messenger.mesh;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public class NetworkSwitcher {

    public enum NetworkStatus {
        ONLINE,
        OFFLINE,
        MESH_ONLY,
        HYBRID
    }

    private static NetworkSwitcher instance;
    private final Context context;
    private final MeshConfig config;
    private NetworkStatus currentStatus;
    private boolean hasInternet;

    private NetworkSwitcher() {
        this.context = ApplicationLoader.applicationContext;
        this.config = MeshConfig.getInstance();
        this.hasInternet = checkInternet();
        this.currentStatus = resolveStatus();
    }

    public static synchronized NetworkSwitcher getInstance() {
        if (instance == null) {
            instance = new NetworkSwitcher();
        }
        return instance;
    }

    public boolean checkInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    private NetworkStatus resolveStatus() {
        hasInternet = checkInternet();
        if (config.isMeshOnlyMode()) {
            return NetworkStatus.MESH_ONLY;
        }
        if (!hasInternet && config.isMeshEnabled()) {
            return NetworkStatus.OFFLINE;
        }
        if (hasInternet && config.isMeshEnabled()) {
            return NetworkStatus.HYBRID;
        }
        return NetworkStatus.ONLINE;
    }

    public void refresh() {
        NetworkStatus old = currentStatus;
        currentStatus = resolveStatus();
        if (old != currentStatus) {
            FileLog.d("Network status changed: " + old + " -> " + currentStatus);
            if (currentStatus == NetworkStatus.OFFLINE || currentStatus == NetworkStatus.MESH_ONLY) {
                if (config.isMeshEnabled()) {
                    MeshManager.getInstance().start();
                }
            }
        }
    }

    public boolean shouldRouteThroughMesh() {
        return currentStatus == NetworkStatus.OFFLINE
            || currentStatus == NetworkStatus.MESH_ONLY
            || (currentStatus == NetworkStatus.HYBRID && config.isAutoFallback());
    }

    public boolean hasInternetConnection() {
        refresh();
        return hasInternet;
    }

    public NetworkStatus getCurrentStatus() { return currentStatus; }
}
