package org.telegram.messenger.mesh;

import android.content.SharedPreferences;
import android.util.Base64;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;

public class MeshConfig {

    private static final String PREF_NAME = "mesh_config";
    private static final String KEY_ENABLED = "mesh_enabled";
    private static final String KEY_VISIBLE = "mesh_visible";
    private static final String KEY_E2EE = "mesh_e2ee";
    private static final String KEY_PUBLIC_KEY = "mesh_public_key";
    private static final String KEY_PRIVATE_KEY = "mesh_private_key";
    private static final String KEY_NODE_ID = "mesh_node_id";
    private static final String KEY_AUTO_CONNECT = "mesh_auto_connect";
    private static final String KEY_AUTO_FALLBACK = "mesh_auto_fallback";
    private static final String KEY_ONLY_MESH = "mesh_only_mode";
    private static final String KEY_NODE_DISPLAY_NAME = "mesh_node_display_name";
    private static final String KEY_BRIDGE_HOST = "mesh_bridge_host";
    private static final String KEY_BRIDGE_PORT = "mesh_bridge_port";
    private static final String KEY_TRANSPORT_TCP = "mesh_transport_tcp";
    private static final String KEY_TRANSPORT_NEARBY = "mesh_transport_nearby";

    private static MeshConfig instance;
    private final SharedPreferences prefs;
    private KeyPair keyPair;
    private String nodeId;
    private boolean supported = true;

    private MeshConfig() {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0);
        nodeId = loadOrCreateNodeId();
        try {
            loadOrCreateKeyPair();
        } catch (Exception e) {
            FileLog.e("MeshConfig: X25519 not supported on this device/version");
            supported = false;
        }
    }

    public static synchronized MeshConfig getInstance() {
        if (instance == null) {
            instance = new MeshConfig();
        }
        return instance;
    }

    private String loadOrCreateNodeId() {
        String id = prefs.getString(KEY_NODE_ID, null);
        if (id == null) {
            id = "mesh_" + java.util.UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString(KEY_NODE_ID, id).apply();
        }
        return id;
    }

    private void loadOrCreateKeyPair() throws Exception {
        String pubB64 = prefs.getString(KEY_PUBLIC_KEY, null);
        String privB64 = prefs.getString(KEY_PRIVATE_KEY, null);
        if (pubB64 != null && privB64 != null) {
            try {
                java.security.KeyFactory kf = java.security.KeyFactory.getInstance("X25519");
                java.security.spec.X509EncodedKeySpec pubSpec = new java.security.spec.X509EncodedKeySpec(Base64.decode(pubB64, Base64.DEFAULT));
                java.security.spec.PKCS8EncodedKeySpec privSpec = new java.security.spec.PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.DEFAULT));
                PublicKey pub = kf.generatePublic(pubSpec);
                PrivateKey priv = kf.generatePrivate(privSpec);
                keyPair = new KeyPair(pub, priv);
                return;
            } catch (Exception e) {
                // fall through
            }
        }
        generateNewKeyPair();
    }

    private void generateNewKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator kg = KeyPairGenerator.getInstance("X25519");
        keyPair = kg.generateKeyPair();
        prefs.edit()
            .putString(KEY_PUBLIC_KEY, Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.DEFAULT))
            .putString(KEY_PRIVATE_KEY, Base64.encodeToString(keyPair.getPrivate().getEncoded(), Base64.DEFAULT))
            .apply();
    }

    public boolean isSupported() { return supported; }
    public boolean isMeshEnabled() { return supported && prefs.getBoolean(KEY_ENABLED, false); }
    public void setMeshEnabled(boolean v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply(); }
    public boolean isVisible() { return prefs.getBoolean(KEY_VISIBLE, true); }
    public void setVisible(boolean v) { prefs.edit().putBoolean(KEY_VISIBLE, v).apply(); }
    public boolean isE2eeEnabled() { return supported && prefs.getBoolean(KEY_E2EE, true); }
    public void setE2eeEnabled(boolean v) { prefs.edit().putBoolean(KEY_E2EE, v).apply(); }
    public boolean isAutoConnect() { return prefs.getBoolean(KEY_AUTO_CONNECT, true); }
    public void setAutoConnect(boolean v) { prefs.edit().putBoolean(KEY_AUTO_CONNECT, v).apply(); }
    public boolean isAutoFallback() { return prefs.getBoolean(KEY_AUTO_FALLBACK, true); }
    public void setAutoFallback(boolean v) { prefs.edit().putBoolean(KEY_AUTO_FALLBACK, v).apply(); }
    public boolean isMeshOnlyMode() { return prefs.getBoolean(KEY_ONLY_MESH, false); }
    public void setMeshOnlyMode(boolean v) { prefs.edit().putBoolean(KEY_ONLY_MESH, v).apply(); }
    public String getNodeId() { return nodeId; }
    public PublicKey getPublicKey() { return keyPair != null ? keyPair.getPublic() : null; }
    public PrivateKey getPrivateKey() { return keyPair != null ? keyPair.getPrivate() : null; }

    public String getNodeDisplayName() {
        return prefs.getString(KEY_NODE_DISPLAY_NAME, "MeshUser");
    }
    public void setNodeDisplayName(String v) { prefs.edit().putString(KEY_NODE_DISPLAY_NAME, v).apply(); }

    public String getBridgeHost() { return prefs.getString(KEY_BRIDGE_HOST, "10.0.2.2"); }
    public void setBridgeHost(String v) { prefs.edit().putString(KEY_BRIDGE_HOST, v).apply(); }

    public int getBridgePort() { return prefs.getInt(KEY_BRIDGE_PORT, 45678); }
    public void setBridgePort(int v) { prefs.edit().putInt(KEY_BRIDGE_PORT, v).apply(); }

    public boolean isTcpTransportEnabled() { return prefs.getBoolean(KEY_TRANSPORT_TCP, true); }
    public void setTcpTransportEnabled(boolean v) { prefs.edit().putBoolean(KEY_TRANSPORT_TCP, v).apply(); }

    public boolean isNearbyTransportEnabled() { return prefs.getBoolean(KEY_TRANSPORT_NEARBY, true); }
    public void setNearbyTransportEnabled(boolean v) { prefs.edit().putBoolean(KEY_TRANSPORT_NEARBY, v).apply(); }
}
