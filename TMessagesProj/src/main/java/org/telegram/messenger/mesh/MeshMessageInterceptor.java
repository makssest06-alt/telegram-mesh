package org.telegram.messenger.mesh;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Bridges Telegram's send pipeline and the mesh network.
 *
 * Routing decision (see MESH_ROADMAP):
 *   mesh enabled + running + peer bound & reachable + NetworkSwitcher says mesh
 *     => send via mesh, fabricate local TL_updates so the UI shows the message
 *   anything else
 *     => return false; caller falls through to the normal MTProto path
 *
 * If a mesh send fails, the caller is notified through onMeshFallback and
 * falls back to the server path — a message is never lost silently.
 */
public class MeshMessageInterceptor {

    private static final String TAG = "MeshInterceptor";
    private static MeshMessageInterceptor instance;

    private final MeshConfig config;
    private final MeshManager meshManager;
    private final NetworkSwitcher networkSwitcher;

    private MeshMessageInterceptor() {
        this.config = MeshConfig.getInstance();
        this.meshManager = MeshManager.getInstance();
        this.networkSwitcher = NetworkSwitcher.getInstance();
    }

    public static synchronized MeshMessageInterceptor getInstance() {
        if (instance == null) {
            instance = new MeshMessageInterceptor();
        }
        return instance;
    }

    public interface MeshSendCallback {
        void onMeshSuccess(TLRPC.Updates updates);
        void onMeshFallback(String error);
    }

    public boolean tryIntercept(String message, long dialogId, long peerId, int currentAccount,
                                SendMessagesHelper helper, MeshSendCallback callback) {
        if (!config.isMeshEnabled() || !meshManager.isRunning()) {
            return false;
        }
        if (!networkSwitcher.shouldRouteThroughMesh()) {
            return false;
        }

        MeshPeer recipient = meshManager.findPeerByUserId(peerId);
        if (recipient == null || !recipient.isAlive()) {
            FileLog.d(TAG + ": peer not found or offline for uid=" + peerId);
            return false;
        }
        String nodeId = recipient.getNodeId();

        MeshMessage meshMsg = MeshMessage.fromText(
            config.getNodeId(),
            UserConfig.getInstance(currentAccount).getClientUserId(),
            dialogId,
            peerId,
            message
        );
        meshMsg.setRecipientNodeId(nodeId);

        boolean sent = meshManager.sendMeshMessage(nodeId, meshMsg);
        if (!sent) {
            if (callback != null) callback.onMeshFallback("mesh send failed");
            // Returning false lets SendMessagesHelper continue with the normal
            // MTProto path — the message is never lost.
            return false;
        }
        FileLog.d(TAG + ": message intercepted and sent via mesh to " + recipient.getDisplayName());

        // Phase 3: queue for cloud convergence once internet returns
        MeshServerSync.getInstance().enqueueOutgoingForSync(currentAccount, dialogId, message, meshMsg.getTimestamp());

        if (callback != null) {
            callback.onMeshSuccess(fabricateUpdates(message, dialogId, currentAccount, meshMsg.getMessageId()));
        }
        return true;
    }

    public boolean tryInterceptMedia(TLRPC.MessageMedia media, long dialogId, long peerId,
                                     int currentAccount, MeshSendCallback callback) {
        if (!config.isMeshEnabled() || !meshManager.isRunning()) {
            return false;
        }
        if (!networkSwitcher.shouldRouteThroughMesh()) {
            return false;
        }

        MeshPeer recipient = meshManager.findPeerByUserId(peerId);
        if (recipient == null || !recipient.isAlive()) {
            return false;
        }

        // Phase 5 will chunk actual media bytes; for now only metadata travels
        // over mesh and the caller MUST fall back to the server for the file.
        if (callback != null) callback.onMeshFallback("media over mesh not implemented yet");
        return false;
    }

    /**
     * Builds a minimal TL_updates carrying the locally-sent message so that
     * MessagesController.processUpdates renders it exactly like a server ack.
     * Message id is negative-local (will be replaced on server sync).
     */
    public static TLRPC.Updates fabricateUpdates(String message, long dialogId, int currentAccount, String meshMsgId) {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates = new ArrayList<>();
        updates.users = new ArrayList<>();
        updates.chats = new ArrayList<>();

        TLRPC.TL_updateNewMessage updateNew = new TLRPC.TL_updateNewMessage();
        TLRPC.TL_message tlMessage = new TLRPC.TL_message();
        tlMessage.message = message;
        tlMessage.dialog_id = dialogId;
        tlMessage.from_id = new TLRPC.TL_peerUser();
        tlMessage.from_id.user_id = UserConfig.getInstance(currentAccount).getClientUserId();
        tlMessage.peer_id = new TLRPC.TL_peerUser();
        tlMessage.peer_id.user_id = dialogId;
        tlMessage.date = (int) (System.currentTimeMillis() / 1000);
        tlMessage.id = localMessageId(meshMsgId);
        tlMessage.out = true;
        if (tlMessage.params == null) {
            tlMessage.params = new java.util.HashMap<>();
        }
        tlMessage.params.put("mesh", "1");

        updateNew.message = tlMessage;
        updateNew.pts = 0;
        updateNew.pts_count = 1;
        updates.updates.add(updateNew);
        return updates;
    }

    /** Deterministic negative local id derived from the mesh message UUID. */
    public static int localMessageId(String meshMsgId) {
        int h = meshMsgId != null ? meshMsgId.hashCode() : (int) System.currentTimeMillis();
        int id = -(Math.abs(h) % 1_000_000_000) - 1;
        return id;
    }
}
