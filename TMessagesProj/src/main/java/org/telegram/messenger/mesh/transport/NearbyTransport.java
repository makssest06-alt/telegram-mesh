package org.telegram.messenger.mesh.transport;

import android.content.Context;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.mesh.MeshConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nearby Connections transport (BLE advertisement + BT/WiFi-Direct data plane).
 * Wraps the same GMS APIs the original MeshManager used, now behind IMeshTransport.
 *
 * Address form: "nearby:<endpointId>".
 * Discovery name: "MeshUser_<nodeId>" (compatible with the original protocol).
 */
public class NearbyTransport implements IMeshTransport {

    private static final String TAG = "NearbyTransport";
    private static final String SERVICE_ID = "org.telegram.messenger.mesh";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

    private final Context context;
    private final MeshConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** endpointId -> nodeId (filled when we learn the peer identity). */
    private final Map<String, String> nodeByEndpoint = new ConcurrentHashMap<>();
    /** nodeId -> endpointId (best known). */
    private final Map<String, String> endpointByNode = new ConcurrentHashMap<>();

    private ConnectionsClient client;
    private TransportCallback callback;
    private boolean isAdvertising;
    private boolean isDiscovering;

    public NearbyTransport(Context context) {
        this.context = context;
        this.config = MeshConfig.getInstance();
    }

    @Override
    public String getName() { return "nearby"; }

    @Override
    public int getPriority() { return 20; }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public void start(TransportCallback cb) {
        if (running.getAndSet(true)) return;
        this.callback = cb;
        try {
            client = Nearby.getConnectionsClient(context);
            if (config.isVisible()) {
                startAdvertising();
            }
            startDiscovery();
            cb.onTransportStateChanged(getName(), true);
        } catch (Exception e) {
            running.set(false);
            FileLog.e(TAG + ": start failed: " + e.getMessage());
            cb.onTransportStateChanged(getName(), false);
        }
    }

    @Override
    public void stop() {
        if (!running.getAndSet(false)) return;
        try {
            if (client != null) {
                if (isAdvertising) client.stopAdvertising();
                if (isDiscovering) client.stopDiscovery();
                client.stopAllEndpoints();
            }
        } catch (Exception e) {
            FileLog.e(TAG + ": stop failed: " + e.getMessage());
        }
        isAdvertising = false;
        isDiscovering = false;
        nodeByEndpoint.clear();
        endpointByNode.clear();
        if (callback != null) callback.onTransportStateChanged(getName(), false);
        FileLog.d(TAG + ": stopped");
    }

    private void startAdvertising() {
        String localName = "MeshUser_" + config.getNodeId();
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        client.startAdvertising(localName, SERVICE_ID, lifecycleCallback, options)
            .addOnSuccessListener(v -> {
                isAdvertising = true;
                FileLog.d(TAG + ": advertising started as " + localName);
            })
            .addOnFailureListener(e -> FileLog.e(TAG + ": advertising failed: " + e.getMessage()));
    }

    private void startDiscovery() {
        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener(v -> {
                isDiscovering = true;
                FileLog.d(TAG + ": discovery started");
            })
            .addOnFailureListener(e -> FileLog.e(TAG + ": discovery failed: " + e.getMessage()));
    }

    private final EndpointDiscoveryCallback discoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            String nodeId = extractNodeId(info.getEndpointName());
            if (nodeId == null || nodeId.equals(config.getNodeId())) return;
            String addr = "nearby:" + endpointId;
            nodeByEndpoint.put(endpointId, nodeId);
            endpointByNode.put(nodeId, endpointId);
            if (callback != null) {
                callback.onPeerDiscovered(nodeId, info.getEndpointName(), addr);
            }
            if (config.isAutoConnect()) {
                client.requestConnection("MeshUser_" + config.getNodeId(), endpointId, lifecycleCallback)
                    .addOnFailureListener(e -> FileLog.e(TAG + ": connection request failed: " + e.getMessage()));
            }
        }

        @Override
        public void onEndpointLost(String endpointId) {
            String nodeId = nodeByEndpoint.remove(endpointId);
            if (nodeId != null) {
                endpointByNode.remove(nodeId);
                if (callback != null) callback.onPeerLost(nodeId, "nearby:" + endpointId);
            }
        }
    };

    private final ConnectionLifecycleCallback lifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            // identity of the initiator comes via the endpoint name; we learn the
            // nodeId either from discovery or from the first IDENTITY/PRESENCE frame
            String nodeId = extractNodeId(info.getEndpointName());
            if (nodeId != null) {
                nodeByEndpoint.put(endpointId, nodeId);
                endpointByNode.put(nodeId, endpointId);
            }
            client.acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
            if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.SUCCESS) {
                String nodeId = nodeByEndpoint.get(endpointId);
                if (nodeId != null && callback != null) {
                    callback.onLinkUp(nodeId, "nearby:" + endpointId);
                }
                FileLog.d(TAG + ": link up, endpoint " + endpointId + " node " + nodeId);
            } else {
                FileLog.e(TAG + ": connection failed: " + result.getStatus().getStatusCode());
            }
        }

        @Override
        public void onDisconnected(String endpointId) {
            String nodeId = nodeByEndpoint.get(endpointId);
            if (nodeId != null) {
                endpointByNode.remove(nodeId);
                if (callback != null) callback.onLinkDown(nodeId, "nearby:" + endpointId);
            }
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES || callback == null) return;
            byte[] frame = payload.asBytes();
            if (frame == null) return;
            String nodeId = nodeByEndpoint.get(endpointId);
            if (nodeId == null) {
                // unknown sender: try to decode the frame to learn senderNodeId
                try {
                    org.telegram.messenger.mesh.MeshMessage m = FrameCodec.decode(frame);
                    nodeId = m.getSenderNodeId();
                    if (nodeId != null) {
                        nodeByEndpoint.put(endpointId, nodeId);
                        endpointByNode.put(nodeId, endpointId);
                    }
                } catch (FrameCodec.FrameException ignore) { }
            }
            if (nodeId != null) {
                callback.onFrameReceived(nodeId, "nearby:" + endpointId, frame);
            }
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
            // Nearby handles chunking internally; nothing to do
        }
    };

    @Override
    public void send(String transportAddress, byte[] frame, SendCallback cb) {
        TransportAddress ta = TransportAddress.parse(transportAddress);
        if (ta == null || !"nearby".equals(ta.getTransport()) || client == null || !running.get()) {
            cb.onError("nearby transport not available");
            return;
        }
        String endpointId = ta.getAddress();
        client.sendPayload(endpointId, Payload.fromBytes(frame))
            .addOnSuccessListener(v -> cb.onSent())
            .addOnFailureListener(e -> cb.onError("nearby send failed: " + e.getMessage()));
    }

    @Override
    public boolean isLinkUp(String transportAddress) {
        TransportAddress ta = TransportAddress.parse(transportAddress);
        if (ta == null || !"nearby".equals(ta.getTransport())) return false;
        String nodeId = nodeByEndpoint.get(ta.getAddress());
        return nodeId != null && ta.getAddress().equals(endpointByNode.get(nodeId));
    }

    private String extractNodeId(String endpointName) {
        if (endpointName != null && endpointName.startsWith("MeshUser_")) {
            return endpointName.substring("MeshUser_".length());
        }
        return null;
    }
}
