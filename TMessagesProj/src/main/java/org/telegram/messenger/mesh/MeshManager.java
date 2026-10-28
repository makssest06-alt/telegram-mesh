package org.telegram.messenger.mesh;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.mesh.transport.FrameCodec;

import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central mesh controller. Transport-agnostic: all physical channels
 * (Nearby, TCP-LAN, emulator bridge) are handled by TransportManager.
 *
 * Responsibilities here:
 *  - session key management (X25519 -> AES-GCM per peer)
 *  - E2EE encrypt/decrypt of application messages
 *  - handling of control messages: PRESENCE, KEY_EXCHANGE, ROUTE_ANNOUNCE
 *  - exposing MeshListener events to the rest of the app
 */
public class MeshManager {

    private static final String TAG = "MeshManager";

    private static volatile MeshManager instance;
    private final Context context;
    private final MeshConfig config;
    private final MeshCryptManager crypt;
    private final TransportManager transportManager;
    private final Map<String, byte[]> sessionKeys = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<MeshMessage> pendingMessages = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshListener> listeners = new CopyOnWriteArrayList<>();

    private boolean isRunning;

    public interface MeshListener {
        void onPeerFound(MeshPeer peer);
        void onPeerUpdated(MeshPeer peer);
        void onPeerLost(MeshPeer peer);
        void onMessageReceived(MeshMessage msg);
        void onMessageSent(MeshMessage msg);
        void onMessageFailed(MeshMessage msg, String error);
        void onStatusChanged(boolean running, int peerCount);
    }

    private MeshManager() {
        this.context = ApplicationLoader.applicationContext;
        this.config = MeshConfig.getInstance();
        this.crypt = new MeshCryptManager(config);
        this.transportManager = new TransportManager(context);
        this.transportManager.setListener(transportListener);
    }

    public static synchronized MeshManager getInstance() {
        if (instance == null) {
            instance = new MeshManager();
        }
        return instance;
    }

    public void start() {
        if (isRunning) return;
        if (!config.isMeshEnabled()) return;
        if (!config.isSupported()) {
            FileLog.e(TAG + ": crypto not supported on this device, mesh disabled");
            return;
        }
        try {
            transportManager.start();
            isRunning = true;
            notifyStatusChanged();
            FileLog.d("MeshManager started");
        } catch (Exception e) {
            FileLog.e("MeshManager start failed: " + e.getMessage());
        }
    }

    public void stop() {
        if (!isRunning) return;
        try {
            transportManager.stop();
            sessionKeys.clear();
            pendingMessages.clear();
            isRunning = false;
            notifyStatusChanged();
            FileLog.d("MeshManager stopped");
        } catch (Exception e) {
            FileLog.e("MeshManager stop failed: " + e.getMessage());
        }
    }

    public void restart() {
        stop();
        start();
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Send a message to a node. Encrypts with the session key if available.
     * @return true if a transport accepted the frame.
     */
    public boolean sendMeshMessage(String nodeId, MeshMessage msg) {
        if (!isRunning) {
            pendingMessages.add(msg);
            return false;
        }
        msg.setSenderNodeId(config.getNodeId());
        if (msg.getSenderUserId() == 0) {
            msg.setSenderUserId(UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
        }
        msg.incrementHop();
        msg.addRouteHop(config.getNodeId());

        if (config.isE2eeEnabled() && msg.getType() != MeshMessage.TYPE_KEY_EXCHANGE) {
            byte[] sessionKey = sessionKeys.get(nodeId);
            if (sessionKey != null && !msg.isE2ee()) {
                crypt.encryptMeshMessage(msg, sessionKey);
            }
        }

        try {
            byte[] frame = FrameCodec.encode(msg);
            boolean sent = transportManager.send(nodeId, frame);
            if (sent) {
                notifyMessageSent(msg);
            } else {
                pendingMessages.add(msg);
                notifyMessageFailed(msg, "no route to " + nodeId);
            }
            return sent;
        } catch (FrameCodec.FrameException e) {
            FileLog.e(TAG + ": encode failed: " + e.getMessage());
            notifyMessageFailed(msg, e.getMessage());
            return false;
        }
    }

    public void broadcastPresence(boolean alive) {
        if (!isRunning) return;
        MeshMessage msg = MeshMessage.fromPresence(config.getNodeId(), alive);
        for (MeshPeer peer : transportManager.getPeers()) {
            sendMeshMessage(peer.getNodeId(), msg);
        }
    }

    public void broadcastToAll(MeshMessage msg) {
        for (MeshPeer peer : transportManager.getPeers()) {
            sendMeshMessage(peer.getNodeId(), msg);
        }
    }

    public void initiateKeyExchange(String nodeId) {
        if (config.getPublicKey() == null) return;
        MeshMessage msg = MeshMessage.fromKeyExchange(config.getNodeId(), config.getPublicKey().getEncoded());
        sendMeshMessage(nodeId, msg);
    }

    /** Session key for a peer, or null if key exchange has not completed. */
    public byte[] getSessionKey(String nodeId) {
        return sessionKeys.get(nodeId);
    }

    private void flushPending(String nodeId) {
        if (sessionKeys.get(nodeId) == null && config.isE2eeEnabled()) {
            initiateKeyExchange(nodeId);
        }
        for (MeshMessage msg : pendingMessages) {
            if (nodeId.equals(msg.getRecipientNodeId()) || msg.getRecipientNodeId() == null) {
                if (sendMeshMessage(nodeId, msg)) {
                    pendingMessages.remove(msg);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Transport events
    // ------------------------------------------------------------------

    private final TransportManager.FrameListener transportListener = new TransportManager.FrameListener() {

        @Override
        public void onFrame(String nodeId, String transportAddress, MeshMessage msg) {
            handleIncomingMessage(nodeId, msg);
        }

        @Override
        public void onPeerEvent(TransportManager.PeerEvent event, MeshPeer peer) {
            switch (event) {
                case DISCOVERED:
                    notifyPeerFound(peer);
                    break;
                case LINK_UP:
                    notifyPeerUpdated(peer);
                    flushPending(peer.getNodeId());
                    MeshIdentityManager.getInstance().onLinkUp(peer.getNodeId());
                    break;
                case LINK_DOWN:
                    notifyPeerUpdated(peer);
                    break;
                case LOST:
                    notifyPeerLost(peer);
                    break;
            }
        }

        @Override
        public void onTransportState(String transportName, boolean running) {
            notifyStatusChanged();
        }
    };

    private void handleIncomingMessage(String nodeId, MeshMessage msg) {
        MeshPeer sender = transportManager.getPeer(nodeId);
        if (sender != null && msg.getSenderDisplayName() == null) {
            msg.setSenderDisplayName(sender.getDisplayName());
        }

        switch (msg.getType()) {
            case MeshMessage.TYPE_PRESENCE:
                handlePresence(nodeId, msg);
                return;
            case MeshMessage.TYPE_KEY_EXCHANGE:
                handleKeyExchange(nodeId, msg);
                return;
            case MeshMessage.TYPE_ROUTE_ANNOUNCE:
                handleRouteAnnounce(nodeId, msg);
                return;
            case MeshMessage.TYPE_ACK:
                // delivery acknowledgement — nothing to decrypt
                notifyMessageReceived(msg);
                return;
            case MeshMessage.TYPE_IDENTITY_CHALLENGE:
                MeshIdentityManager.getInstance().handleChallenge(nodeId, msg);
                return;
            case MeshMessage.TYPE_IDENTITY:
                MeshIdentityManager.getInstance().handleIdentityProof(nodeId, msg);
                return;
        }

        if (msg.isE2ee() && msg.getEncryptedPayload() != null) {
            byte[] sessionKey = sessionKeys.get(nodeId);
            if (sessionKey == null) {
                sessionKey = sessionKeys.get(msg.getSenderNodeId());
            }
            if (sessionKey != null) {
                try {
                    crypt.decryptMeshMessage(msg, sessionKey);
                } catch (Exception e) {
                    FileLog.e(TAG + ": decrypt failed from " + nodeId + ": " + e.getMessage());
                    return;
                }
            } else {
                FileLog.w(TAG + ": no session key for " + nodeId + ", queuing");
                pendingMessages.add(msg);
                initiateKeyExchange(nodeId);
                return;
            }
        }

        if (msg.getType() == MeshMessage.TYPE_TEXT || msg.getType() == MeshMessage.TYPE_MEDIA) {
            notifyMessageReceived(msg);
            // Phase 2: inject into Telegram dialogs so the chat UI shows it
            MeshInboxProcessor.getInstance().processIncoming(msg, UserConfig.selectedAccount);
            sendDeliveryReceipt(nodeId, msg);
        } else {
            // IDENTITY and other future types bubble up too
            notifyMessageReceived(msg);
        }
    }

    private void handlePresence(String nodeId, MeshMessage msg) {
        boolean alive = msg.getPayload() != null && msg.getPayload().length > 0 && msg.getPayload()[0] == 1;
        MeshPeer peer = transportManager.getPeer(nodeId);
        if (peer != null) {
            peer.setAlive(alive);
            peer.setLastSeen(System.currentTimeMillis());
            notifyPeerUpdated(peer);
        }
    }

    private void handleKeyExchange(String nodeId, MeshMessage msg) {
        try {
            MeshPeer peer = transportManager.getPeer(nodeId);
            byte[] pubKeyBytes = msg.getPayload();
            if (pubKeyBytes == null) return;
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("X25519");
            X509EncodedKeySpec spec = new X509EncodedKeySpec(pubKeyBytes);
            PublicKey peerPublicKey = kf.generatePublic(spec);
            if (peer != null) peer.setPublicKey(peerPublicKey);
            byte[] sharedSecret = MeshCryptManager.generateSharedSecret(config.getPrivateKey(), peerPublicKey);
            byte[] sessionKey = crypt.computeSessionKey(sharedSecret, "mesh-salt".getBytes());
            sessionKeys.put(nodeId, sessionKey);
            FileLog.d(TAG + ": key exchange complete with " + nodeId);
            MeshIdentityManager.getInstance().onSessionReady(nodeId);
            // reply with our key if we haven't yet (so both sides converge)
            flushPending(nodeId);
        } catch (Exception e) {
            FileLog.e(TAG + ": key exchange failed: " + e.getMessage());
        }
    }

    private void handleRouteAnnounce(String nodeId, MeshMessage msg) {
        if (msg.getPayload() == null) return;
        String routeStr = new String(msg.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
        MeshPeer peer = transportManager.getPeer(nodeId);
        if (peer != null) {
            peer.setHopCount(routeStr.split(",").length);
            notifyPeerUpdated(peer);
        }
    }

    private void sendDeliveryReceipt(String nodeId, MeshMessage originalMsg) {
        MeshMessage receipt = MeshMessage.fromAck(config.getNodeId(), originalMsg.getMessageId());
        receipt.setDeliveryReceipt(true);
        sendMeshMessage(nodeId, receipt);
    }

    // ------------------------------------------------------------------
    // Peer queries (delegated to TransportManager)
    // ------------------------------------------------------------------

    public MeshPeer findPeerByUserId(long userId) {
        return transportManager.findPeerByUserId(userId);
    }

    public String findNodeIdByUserId(long userId) {
        return transportManager.findNodeIdByUserId(userId);
    }

    public boolean isPeerReachable(String nodeId) {
        return transportManager.isPeerReachable(nodeId);
    }

    public void bindUserId(String nodeId, long userId, String displayName) {
        transportManager.bindUserId(nodeId, userId, displayName);
    }

    public Collection<MeshPeer> getPeers() {
        List<MeshPeer> list = transportManager.getPeers();
        return list;
    }

    public int getPeerCount() {
        return transportManager.getPeerCount();
    }

    public boolean isRunning() { return isRunning; }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    public void addListener(MeshListener listener) {
        listeners.add(listener);
    }

    public void removeListener(MeshListener listener) {
        listeners.remove(listener);
    }

    private void notifyPeerFound(MeshPeer peer) {
        for (MeshListener l : listeners) l.onPeerFound(peer);
    }

    private void notifyPeerUpdated(MeshPeer peer) {
        for (MeshListener l : listeners) l.onPeerUpdated(peer);
    }

    private void notifyPeerLost(MeshPeer peer) {
        for (MeshListener l : listeners) l.onPeerLost(peer);
    }

    private void notifyMessageReceived(MeshMessage msg) {
        for (MeshListener l : listeners) l.onMessageReceived(msg);
    }

    private void notifyMessageSent(MeshMessage msg) {
        for (MeshListener l : listeners) l.onMessageSent(msg);
    }

    private void notifyMessageFailed(MeshMessage msg, String error) {
        for (MeshListener l : listeners) l.onMessageFailed(msg, error);
    }

    private void notifyStatusChanged() {
        for (MeshListener l : listeners) l.onStatusChanged(isRunning, transportManager.getPeerCount());
    }
}
