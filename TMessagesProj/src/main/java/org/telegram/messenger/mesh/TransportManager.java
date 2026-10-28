package org.telegram.messenger.mesh;

import android.content.Context;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.mesh.transport.IMeshTransport;
import org.telegram.messenger.mesh.transport.NearbyTransport;
import org.telegram.messenger.mesh.transport.PeerRegistry;
import org.telegram.messenger.mesh.transport.TcpLanTransport;
import org.telegram.messenger.mesh.transport.TransportAddress;
import org.telegram.messenger.mesh.transport.TransportCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Multiplexes several IMeshTransport implementations into one logical mesh.
 *
 * Responsibilities:
 *  - starts/stops enabled transports (TCP-LAN and/or Nearby, per MeshConfig)
 *  - deduplicates peers: the same nodeId discovered via two transports
 *    becomes ONE PeerRegistry entry with a prioritized address list
 *  - picks the best channel for sending (live link with lowest priority value)
 *  - retries a failed send on the next-best address of the same peer
 *  - hands decoded frames to MeshManager via FrameListener
 *
 * All transport callbacks are marshalled onto a single-thread executor so
 * MeshManager sees a consistent, serialized event stream.
 */
public class TransportManager {

    private static final String TAG = "TransportManager";

    public interface FrameListener {
        void onFrame(String nodeId, String transportAddress, MeshMessage msg);
        void onPeerEvent(PeerEvent event, MeshPeer peer);
        void onTransportState(String transportName, boolean running);
    }

    public enum PeerEvent { DISCOVERED, LINK_UP, LINK_DOWN, LOST }

    private final Context context;
    private final MeshConfig config;
    private final PeerRegistry registry = new PeerRegistry();
    private final Map<String, IMeshTransport> transports = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService queue =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mesh-tmgr");
            t.setDaemon(true);
            return t;
        });

    private TcpLanTransport tcpTransport;
    private NearbyTransport nearbyTransport;
    private FrameListener listener;
    private volatile boolean running;

    /** nodeId -> MeshPeer facade handed to MeshManager/UI. */
    private final Map<String, MeshPeer> peers = new ConcurrentHashMap<>();

    public TransportManager(Context context) {
        this.context = context;
        this.config = MeshConfig.getInstance();
    }

    public void setListener(FrameListener l) {
        this.listener = l;
    }

    public void start() {
        if (running) return;
        running = true;
        if (config.isTcpTransportEnabled()) {
            tcpTransport = new TcpLanTransport(context);
            transports.put("tcp", tcpTransport);
            tcpTransport.start(internalCallback);
            String bridgeHost = config.getBridgeHost();
            if (bridgeHost != null && !bridgeHost.isEmpty()) {
                tcpTransport.enableBridge(bridgeHost, config.getBridgePort());
                FileLog.d(TAG + ": TCP transport started with bridge " + bridgeHost + ":" + config.getBridgePort());
            } else {
                FileLog.d(TAG + ": TCP transport started (NSD mode)");
            }
        }
        if (config.isNearbyTransportEnabled() && config.isSupported()) {
            nearbyTransport = new NearbyTransport(context);
            transports.put("nearby", nearbyTransport);
            nearbyTransport.start(internalCallback);
            FileLog.d(TAG + ": Nearby transport started");
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        for (IMeshTransport t : transports.values()) {
            try { t.stop(); } catch (Exception e) { FileLog.e(TAG + ": stop " + t.getName() + " failed: " + e); }
        }
        transports.clear();
        registry.getAll().forEach(e -> registry.remove(e.nodeId));
        peers.clear();
        FileLog.d(TAG + ": stopped");
    }

    public boolean isRunning() { return running; }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Send an encoded frame to a node. NON-BLOCKING: picks the best address,
     * dispatches the write on the transport executor; on failure automatically
     * tries the next address of the same peer.
     * @return true if at least one candidate route exists (send is queued).
     */
    public boolean send(String nodeId, byte[] frame) {
        if (!running) return false;
        PeerRegistry.PeerEntry entry = registry.get(nodeId);
        if (entry == null) return false;
        List<TransportAddress> addrs = entry.getAddresses();
        if (addrs.isEmpty()) return false;

        // Prefer addresses with a live link, keep declared priority order otherwise.
        List<TransportAddress> ordered = new ArrayList<>();
        for (TransportAddress a : addrs) {
            IMeshTransport t = transports.get(a.getTransport());
            if (t != null && t.isLinkUp(a.serialize())) ordered.add(a);
        }
        for (TransportAddress a : addrs) {
            if (!ordered.contains(a)) ordered.add(a);
        }
        if (ordered.isEmpty()) return false;

        attemptSend(nodeId, frame, ordered, 0);
        return true;
    }

    private void attemptSend(String nodeId, byte[] frame, List<TransportAddress> ordered, int index) {
        if (index >= ordered.size()) {
            FileLog.e(TAG + ": all routes exhausted for " + nodeId);
            return;
        }
        TransportAddress addr = ordered.get(index);
        IMeshTransport t = transports.get(addr.getTransport());
        if (t == null) {
            attemptSend(nodeId, frame, ordered, index + 1);
            return;
        }
        t.send(addr.serialize(), frame, new IMeshTransport.SendCallback() {
            @Override public void onSent() { /* ok */ }
            @Override public void onError(String err) {
                FileLog.w(TAG + ": send via " + addr + " failed: " + err + ", trying next route");
                attemptSend(nodeId, frame, ordered, index + 1);
            }
        });
    }

    /** True if the node has at least one live link on any transport. */
    public boolean isPeerReachable(String nodeId) {
        PeerRegistry.PeerEntry entry = registry.get(nodeId);
        return entry != null && entry.hasLiveLink();
    }

    // ------------------------------------------------------------------
    // Peer view for UI / MeshManager
    // ------------------------------------------------------------------

    public MeshPeer getPeer(String nodeId) { return peers.get(nodeId); }

    public List<MeshPeer> getPeers() { return new ArrayList<>(peers.values()); }

    public int getPeerCount() { return peers.size(); }

    public MeshPeer findPeerByUserId(long userId) {
        if (userId == 0) return null;
        for (MeshPeer p : peers.values()) {
            if (p.getUserId() == userId) return p;
        }
        return null;
    }

    public String findNodeIdByUserId(long userId) {
        MeshPeer p = findPeerByUserId(userId);
        return p != null ? p.getNodeId() : null;
    }

    /** Bind a Telegram userId to a node after a successful identity handshake. */
    public void bindUserId(String nodeId, long userId, String displayName) {
        MeshPeer p = peers.get(nodeId);
        if (p != null) {
            p.setUserId(userId);
            p.setMeshUser(true);
            if (displayName != null) p.setDisplayName(displayName);
        }
    }

    private MeshPeer facadeFor(String nodeId, String displayName) {
        MeshPeer p = peers.get(nodeId);
        if (p == null) {
            p = new MeshPeer(nodeId, displayName != null ? displayName : nodeId, 0);
            MeshPeer prev = peers.putIfAbsent(nodeId, p);
            if (prev != null) p = prev;
        }
        return p;
    }

    // ------------------------------------------------------------------
    // Transport callbacks (serialized on `queue`)
    // ------------------------------------------------------------------

    private final TransportCallback internalCallback = new TransportCallback() {

        @Override
        public void onPeerDiscovered(String nodeId, String displayName, String transportAddress) {
            queue.execute(() -> {
                TransportAddress addr = TransportAddress.parse(transportAddress);
                if (addr == null) return;
                PeerRegistry.PeerEntry entry = registry.getOrCreate(nodeId, displayName);
                IMeshTransport t = transports.get(addr.getTransport());
                entry.addAddress(addr, t != null ? t.getPriority() : 50);
                MeshPeer peer = facadeFor(nodeId, displayName);
                peer.setStatus(MeshPeer.PeerStatus.DISCOVERED);
                if (t != null && t.isLinkUp(addr.serialize())) {
                    peer.setStatus(MeshPeer.PeerStatus.CONNECTED);
                }
                if (listener != null) listener.onPeerEvent(PeerEvent.DISCOVERED, peer);
            });
        }

        @Override
        public void onLinkUp(String nodeId, String transportAddress) {
            queue.execute(() -> {
                TransportAddress addr = TransportAddress.parse(transportAddress);
                PeerRegistry.PeerEntry entry = registry.get(nodeId);
                if (entry != null && addr != null) {
                    entry.setLinkUp(addr.serialize(), true);
                }
                MeshPeer peer = facadeFor(nodeId, null);
                peer.setStatus(MeshPeer.PeerStatus.CONNECTED);
                peer.setAlive(true);
                peer.setLastSeen(System.currentTimeMillis());
                FileLog.d(TAG + ": link up " + nodeId + " via " + transportAddress);
                if (listener != null) listener.onPeerEvent(PeerEvent.LINK_UP, peer);
            });
        }

        @Override
        public void onLinkDown(String nodeId, String transportAddress) {
            queue.execute(() -> {
                TransportAddress addr = TransportAddress.parse(transportAddress);
                PeerRegistry.PeerEntry entry = registry.get(nodeId);
                if (entry != null && addr != null) {
                    entry.setLinkUp(addr.serialize(), false);
                }
                MeshPeer peer = peers.get(nodeId);
                if (peer != null) {
                    boolean stillUp = entry != null && entry.hasLiveLink();
                    peer.setStatus(stillUp ? MeshPeer.PeerStatus.CONNECTED : MeshPeer.PeerStatus.DISCONNECTED);
                    peer.setAlive(stillUp);
                    if (listener != null) listener.onPeerEvent(PeerEvent.LINK_DOWN, peer);
                }
            });
        }

        @Override
        public void onPeerLost(String nodeId, String transportAddress) {
            queue.execute(() -> {
                TransportAddress addr = TransportAddress.parse(transportAddress);
                PeerRegistry.PeerEntry entry = registry.get(nodeId);
                if (entry != null && addr != null) {
                    entry.removeAddress(addr);
                    if (entry.addressCount() == 0) {
                        registry.remove(nodeId);
                    }
                }
                MeshPeer peer = peers.remove(nodeId);
                if (peer != null && listener != null) {
                    peer.setStatus(MeshPeer.PeerStatus.DISCONNECTED);
                    listener.onPeerEvent(PeerEvent.LOST, peer);
                }
            });
        }

        @Override
        public void onFrameReceived(String nodeId, String transportAddress, byte[] frame) {
            queue.execute(() -> {
                try {
                    int ctl = org.telegram.messenger.mesh.transport.FrameCodec.controlCodeOf(frame);
                    if (ctl != 0) return; // transport-level keepalive, not for us
                    MeshMessage msg = org.telegram.messenger.mesh.transport.FrameCodec.decode(frame);
                    // keep registry alive-ness fresh
                    PeerRegistry.PeerEntry entry = registry.get(nodeId);
                    if (entry != null) {
                        MeshPeer peer = facadeFor(nodeId, msg.getSenderDisplayName());
                        peer.setAlive(true);
                        peer.setLastSeen(System.currentTimeMillis());
                    }
                    if (listener != null) listener.onFrame(nodeId, transportAddress, msg);
                } catch (org.telegram.messenger.mesh.transport.FrameCodec.FrameException e) {
                    FileLog.e(TAG + ": bad frame from " + nodeId + ": " + e.getMessage());
                }
            });
        }

        @Override
        public void onTransportStateChanged(String transportName, boolean isRunning) {
            queue.execute(() -> {
                FileLog.d(TAG + ": transport " + transportName + (isRunning ? " up" : " down"));
                if (listener != null) listener.onTransportState(transportName, isRunning);
            });
        }
    };
}
