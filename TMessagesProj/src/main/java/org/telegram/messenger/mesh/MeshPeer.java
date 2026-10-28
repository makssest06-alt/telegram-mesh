package org.telegram.messenger.mesh;

import java.security.PublicKey;
import java.util.UUID;

public class MeshPeer {

    public enum PeerStatus {
        DISCOVERED,
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    public enum ConnectionMethod {
        NEARBY_WIFI,
        NEARBY_BLE,
        WIFI_DIRECT,
        BLUETOOTH
    }

    private final String peerId;
    private final String nodeId;
    private volatile String displayName;
    private volatile long userId;
    private PublicKey publicKey;
    private PeerStatus status;
    private ConnectionMethod method;
    private String endpointId;
    private int signalStrength;
    private long lastSeen;
    private int hopCount;
    private boolean isMeshUser;
    private boolean isAlive;

    public MeshPeer(String nodeId, String displayName, long userId) {
        this.peerId = UUID.randomUUID().toString();
        this.nodeId = nodeId;
        this.displayName = displayName;
        this.userId = userId;
        this.status = PeerStatus.DISCOVERED;
        this.method = ConnectionMethod.NEARBY_WIFI;
        this.isMeshUser = true;
        this.isAlive = false;
        this.lastSeen = System.currentTimeMillis();
        this.hopCount = 0;
    }

    public String getPeerId() { return peerId; }
    public String getNodeId() { return nodeId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String d) { this.displayName = d; }
    public long getUserId() { return userId; }
    public void setUserId(long u) { this.userId = u; }
    public PublicKey getPublicKey() { return publicKey; }
    public void setPublicKey(PublicKey k) { this.publicKey = k; }
    public PeerStatus getStatus() { return status; }
    public void setStatus(PeerStatus s) { this.status = s; }
    public ConnectionMethod getMethod() { return method; }
    public void setMethod(ConnectionMethod m) { this.method = m; }
    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String e) { this.endpointId = e; }
    public int getSignalStrength() { return signalStrength; }
    public void setSignalStrength(int s) { this.signalStrength = s; }
    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long t) { this.lastSeen = t; }
    public int getHopCount() { return hopCount; }
    public void setHopCount(int h) { this.hopCount = h; }
    public boolean isMeshUser() { return isMeshUser; }
    public void setMeshUser(boolean m) { isMeshUser = m; }
    public boolean isAlive() { return isAlive; }
    public void setAlive(boolean a) { isAlive = a; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MeshPeer peer = (MeshPeer) o;
        return nodeId.equals(peer.nodeId);
    }

    @Override
    public int hashCode() {
        return nodeId.hashCode();
    }
}
