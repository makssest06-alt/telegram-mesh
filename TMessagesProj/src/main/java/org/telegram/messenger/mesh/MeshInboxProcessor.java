package org.telegram.messenger.mesh;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 2: injects inbound mesh messages into Telegram dialogs so they
 * appear in the chat UI exactly like server-delivered messages.
 *
 * Flow (mirrors MessagesController.processUpdateArray for TL_updateNewMessage):
 *   1. verify the sender binding (MeshIdentityManager) and dedup by messageId
 *   2. build a TLRPC.TL_message with a NEGATIVE local id (Telegram convention
 *      for client-side ids; replaced by a real id after server-sync)
 *   3. wrap into MessageObject, persist via MessagesStorage.putMessages
 *   4. updateInterfaceWithMessages -> posts didReceiveNewMessages -> chat renders
 *   5. NotificationsController.processNewMessages -> notification if chat closed
 *   6. queue the message for server-sync (phase 3, MeshServerSync) so the
 *      conversation converges in the cloud when connectivity returns
 */
public class MeshInboxProcessor {

    private static final String TAG = "MeshInbox";
    private static final int DEDUP_CACHE_LIMIT = 2048;

    private static volatile MeshInboxProcessor instance;

    private final MeshIdentityManager identityManager;
    private final MeshManager meshManager;
    /** messageId dedup ring: a message can arrive via several transports. */
    private final Set<String> seenMessageIds = new HashSet<>();
    private final ArrayList<String> seenOrder = new ArrayList<>();

    private MeshInboxProcessor() {
        this.identityManager = MeshIdentityManager.getInstance();
        this.meshManager = MeshManager.getInstance();
    }

    public static synchronized MeshInboxProcessor getInstance() {
        if (instance == null) {
            instance = new MeshInboxProcessor();
        }
        return instance;
    }

    /**
     * Entry point: called from MeshManager listener when a decrypted
     * TYPE_TEXT/TYPE_MEDIA message arrives.
     */
    public void processIncoming(final MeshMessage msg, final int currentAccount) {
        if (msg == null || msg.getMessageId() == null) return;
        if (msg.isDeliveryReceipt() || msg.isReadReceipt()) {
            processReceipt(msg, currentAccount);
            return;
        }
        if (msg.getType() != MeshMessage.TYPE_TEXT) {
            // media payload delivery lands in phase 5
            FileLog.d(TAG + ": ignoring non-text mesh message type=" + msg.getType());
            return;
        }
        if (!markSeen(msg.getMessageId())) {
            FileLog.d(TAG + ": duplicate mesh message " + msg.getMessageId() + " dropped");
            return;
        }

        final String senderNodeId = msg.getSenderNodeId();
        final Long senderUserId = identityManager.getUserIdForNode(senderNodeId);
        if (senderUserId == null || senderUserId == 0) {
            FileLog.w(TAG + ": mesh message from UNVERIFIED node " + senderNodeId + ", holding");
            // Hold until identity handshake completes; MeshManager keeps a
            // pending queue, we simply drop here for MVP and log loudly.
            return;
        }

        final String text = msg.getPayload() != null
            ? new String(msg.getPayload(), StandardCharsets.UTF_8) : "";
        final long dialogId = senderUserId; // 1:1 dialog with the sender

        AndroidUtilities.runOnUIThread(() -> {
            try {
                TLRPC.TL_message tlMessage = new TLRPC.TL_message();
                tlMessage.id = localIdFor(msg.getMessageId());
                tlMessage.dialog_id = dialogId;
                tlMessage.peer_id = new TLRPC.TL_peerUser();
                tlMessage.peer_id.user_id = senderUserId;
                tlMessage.from_id = new TLRPC.TL_peerUser();
                tlMessage.from_id.user_id = senderUserId;
                tlMessage.message = text;
                tlMessage.date = (int) (msg.getTimestamp() / 1000);
                tlMessage.out = false;
                tlMessage.unread = true;
                tlMessage.mentioned = false;
                tlMessage.media_unread = false;
                tlMessage.random_id = 0;
                tlMessage.flags = 0;
                if (tlMessage.params == null) {
                    tlMessage.params = new HashMap<>();
                }
                tlMessage.params.put("mesh", "1");
                // normalize dialog id (fills messageOwner.dialog_id etc.)
                org.telegram.messenger.MessageObject.getDialogId(tlMessage);

                final MessagesController controller = MessagesController.getInstance(currentAccount);
                TLRPC.User senderUser = controller.getUser(senderUserId);
                HashMap<Long, TLRPC.User> usersDict = new HashMap<>();
                if (senderUser != null) usersDict.put(senderUserId, senderUser);

                final org.telegram.messenger.MessageObject obj =
                    new org.telegram.messenger.MessageObject(currentAccount, tlMessage, usersDict, null, true, true);

                final ArrayList<TLRPC.Message> toStore = new ArrayList<>();
                toStore.add(tlMessage);
                final ArrayList<org.telegram.messenger.MessageObject> objs = new ArrayList<>();
                objs.add(obj);

                MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
                    MessagesStorage.getInstance(currentAccount).putMessages(toStore, true, false, false, 0, 0, 0);
                    AndroidUtilities.runOnUIThread(() -> {
                        controller.updateInterfaceWithMessages(dialogId, objs, 0);
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload);
                        // notification when the chat is not open
                        ArrayList<org.telegram.messenger.MessageObject> push = new ArrayList<>();
                        push.add(obj);
                        org.telegram.messenger.NotificationsController.getInstance(currentAccount)
                            .processNewMessages(push, true, false, null);
                        FileLog.d(TAG + ": injected mesh message into dialog " + dialogId + " id=" + tlMessage.id);
                        // phase 3: schedule cloud sync so other devices converge
                        MeshServerSync.getInstance().enqueueForUpload(currentAccount, msg);
                    });
                });
            } catch (Exception e) {
                FileLog.e(TAG + ": inject failed: " + e);
            }
        });
    }

    /** Delivery ACK from a peer: mark our local message as delivered (✓✓). */
    private void processReceipt(MeshMessage msg, int currentAccount) {
        if (msg.getPayload() == null) return;
        String ackedId = new String(msg.getPayload(), StandardCharsets.UTF_8);
        if (ackedId.startsWith("delivered:")) ackedId = ackedId.substring(10);
        FileLog.d(TAG + ": delivery receipt for " + ackedId);
        // Read-state for mesh-sent messages is tracked by MeshServerSync/outbox (phase 3)
    }

    /**
     * Deterministic negative local id in Telegram's client-side range.
     * Negative ids are how Telegram marks "not yet on server" messages;
     * server-sync later swaps it for a real one via updateMessageStateAndId.
     */
    public static int localIdFor(String meshMessageId) {
        int h = meshMessageId.hashCode();
        // keep within the range MessagesController treats as local
        return -(Math.abs(h) % 900_000_000) - 1;
    }

    private synchronized boolean markSeen(String messageId) {
        if (seenMessageIds.contains(messageId)) return false;
        seenMessageIds.add(messageId);
        seenOrder.add(messageId);
        if (seenOrder.size() > DEDUP_CACHE_LIMIT) {
            String old = seenOrder.remove(0);
            seenMessageIds.remove(old);
        }
        return true;
    }
}
