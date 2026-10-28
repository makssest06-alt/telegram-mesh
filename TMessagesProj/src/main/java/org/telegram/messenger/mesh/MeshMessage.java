package org.telegram.messenger.mesh;

import org.telegram.tgnet.TLRPC;

import java.util.Arrays;

public class MeshMessage {

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_MEDIA = 2;
    public static final int TYPE_FILE = 3;
    public static final int TYPE_PRESENCE = 4;
    public static final int TYPE_KEY_EXCHANGE = 5;
    public static final int TYPE_ROUTE_ANNOUNCE = 6;
    public static final int TYPE_IDENTITY = 7;
    public static final int TYPE_IDENTITY_CHALLENGE = 8;
    public static final int TYPE_ACK = 9;

    private String messageId;
    private String senderNodeId;
    private String senderDisplayName;
    private long senderUserId;
    private String recipientNodeId;
    private long recipientUserId;
    private long dialogId;
    private int type;
    private byte[] payload;
    private byte[] encryptedPayload;
    private byte[] iv;
    private byte[] hmac;
    private long timestamp;
    private int hopCount;
    private String routePath;
    private boolean isDeliveryReceipt;
    private boolean isReadReceipt;
    private boolean isRelayed;
    private boolean isE2ee;

    public MeshMessage() {
        this.timestamp = System.currentTimeMillis();
        this.hopCount = 0;
        this.routePath = "";
    }

    public static MeshMessage fromText(String senderNodeId, long senderUserId, long dialogId,
                                       long recipientUserId, String text) {
        MeshMessage msg = new MeshMessage();
        msg.messageId = java.util.UUID.randomUUID().toString();
        msg.senderNodeId = senderNodeId;
        msg.senderUserId = senderUserId;
        msg.recipientUserId = recipientUserId;
        msg.dialogId = dialogId;
        msg.type = TYPE_TEXT;
        msg.payload = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return msg;
    }

    public static MeshMessage fromPresence(String nodeId, boolean alive) {
        MeshMessage msg = new MeshMessage();
        msg.messageId = java.util.UUID.randomUUID().toString();
        msg.senderNodeId = nodeId;
        msg.type = TYPE_PRESENCE;
        msg.payload = alive ? new byte[]{1} : new byte[]{0};
        return msg;
    }

    public static MeshMessage fromKeyExchange(String nodeId, byte[] publicKeyBytes) {
        MeshMessage msg = new MeshMessage();
        msg.messageId = java.util.UUID.randomUUID().toString();
        msg.senderNodeId = nodeId;
        msg.type = TYPE_KEY_EXCHANGE;
        msg.payload = publicKeyBytes;
        return msg;
    }

    public static MeshMessage fromRouteAnnounce(String nodeId, String route) {
        MeshMessage msg = new MeshMessage();
        msg.messageId = java.util.UUID.randomUUID().toString();
        msg.senderNodeId = nodeId;
        msg.type = TYPE_ROUTE_ANNOUNCE;
        msg.payload = route.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return msg;
    }

    /**
     * Identity handshake: binds a mesh nodeId to a Telegram userId.
     * payload = utf8 string "userId|nonce|code" where code is a one-time
     * proof that will be verified through the Telegram server (phase 1).
     */
    public static MeshMessage fromIdentity(String nodeId, long userId, String nonce, String code) {
        MeshMessage msg = new MeshMessage();
        msg.messageId = java.util.UUID.randomUUID().toString();
        msg.senderNodeId = nodeId;
        msg.senderUserId = userId;
        msg.type = TYPE_IDENTITY;
        msg.payload = (userId + "|" + nonce + "|" + code).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return msg;
    }

    /** Challenge carrying a nonce the peer must sign/echo back. */
    public static MeshMessage fromIdentityChallenge(String nodeId, String nonce) {
        MeshMessage msg = new MeshMessage();
        msg.messageId = java.util.UUID.randomUUID().toString();
        msg.senderNodeId = nodeId;
        msg.type = TYPE_IDENTITY_CHALLENGE;
        msg.payload = nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return msg;
    }

    /** Delivery acknowledgement referencing the acked messageId. */
    public static MeshMessage fromAck(String nodeId, String ackedMessageId) {
        MeshMessage msg = new MeshMessage();
        msg.messageId = "ack_" + ackedMessageId;
        msg.senderNodeId = nodeId;
        msg.type = TYPE_ACK;
        msg.payload = ackedMessageId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return msg;
    }

    // Getters and setters
    public String getMessageId() { return messageId; }
    public void setMessageId(String id) { this.messageId = id; }
    public String getSenderNodeId() { return senderNodeId; }
    public void setSenderNodeId(String n) { this.senderNodeId = n; }
    public String getSenderDisplayName() { return senderDisplayName; }
    public void setSenderDisplayName(String n) { this.senderDisplayName = n; }
    public long getSenderUserId() { return senderUserId; }
    public void setSenderUserId(long u) { this.senderUserId = u; }
    public String getRecipientNodeId() { return recipientNodeId; }
    public void setRecipientNodeId(String n) { this.recipientNodeId = n; }
    public long getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(long u) { this.recipientUserId = u; }
    public long getDialogId() { return dialogId; }
    public void setDialogId(long d) { this.dialogId = d; }
    public int getType() { return type; }
    public void setType(int t) { this.type = t; }
    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] p) { this.payload = p; }
    public byte[] getEncryptedPayload() { return encryptedPayload; }
    public void setEncryptedPayload(byte[] e) { this.encryptedPayload = e; }
    public byte[] getIv() { return iv; }
    public void setIv(byte[] i) { this.iv = i; }
    public byte[] getHmac() { return hmac; }
    public void setHmac(byte[] h) { this.hmac = h; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long t) { this.timestamp = t; }
    public int getHopCount() { return hopCount; }
    public void setHopCount(int h) { this.hopCount = h; }
    public void incrementHop() { this.hopCount++; }
    public String getRoutePath() { return routePath; }
    public void setRoutePath(String r) { this.routePath = r; }
    public void addRouteHop(String nodeId) {
        this.routePath = routePath.isEmpty() ? nodeId : routePath + "," + nodeId;
    }
    public boolean isDeliveryReceipt() { return isDeliveryReceipt; }
    public void setDeliveryReceipt(boolean d) { isDeliveryReceipt = d; }
    public boolean isReadReceipt() { return isReadReceipt; }
    public void setReadReceipt(boolean r) { isReadReceipt = r; }
    public boolean isRelayed() { return isRelayed; }
    public void setRelayed(boolean r) { isRelayed = r; }
    public boolean isE2ee() { return isE2ee; }
    public void setE2ee(boolean e) { isE2ee = e; }

    @Override
    public String toString() {
        return "MeshMessage{" +
            "id='" + messageId + '\'' +
            ", from=" + senderNodeId +
            ", type=" + type +
            ", hop=" + hopCount +
            ", e2ee=" + isE2ee +
            '}';
    }
}
