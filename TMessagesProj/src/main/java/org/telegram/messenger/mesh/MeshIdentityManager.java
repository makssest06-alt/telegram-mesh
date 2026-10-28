package org.telegram.messenger.mesh;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Phase 1: binds a mesh nodeId to a Telegram userId.
 *
 * Two-step handshake performed right after a link goes up (before any
 * application traffic is trusted):
 *
 *  1. CHALLENGE  A -> B : TYPE_IDENTITY_CHALLENGE { nonceA }
 *  2. PROOF      B -> A : TYPE_IDENTITY { userIdB, nonceA, mac }
 *     where mac = HMAC-SHA256(sessionKey, "mesh-id-v1|" + userIdB + "|" + nonceA)
 *
 * A verifies the mac against its own sessionKey copy for B. If valid,
 * A records binding nodeId(B) <-> userIdB and marks the peer as a
 * verified mesh user. Both sides run the same exchange symmetrically.
 *
 * Bindings persist in SharedPreferences so a restart does not require
 * a new handshake with known peers (the session key itself is derived
 * deterministically from the X25519 exchange, see MeshCryptManager).
 *
 * Server-assisted verification (one-time code through Telegram servers,
 * protects against a malicious device impersonating a userId it does not
 * own) is layered on top via verifyThroughServer() — phase 1.2.
 */
public class MeshIdentityManager {

    private static final String TAG = "MeshIdentity";
    private static final String PREF_NAME = "mesh_identity";
    private static final String MAC_CONTEXT = "mesh-id-v1";

    private static volatile MeshIdentityManager instance;

    private final MeshConfig config;
    private final MeshManager meshManager;
    private final SharedPreferences prefs;
    private final Random random = new Random();

    /** nodeId -> pending challenge nonce we sent and await proof for. */
    private final Map<String, String> pendingChallenges = new ConcurrentHashMap<>();
    /** nodeId -> bound telegram userId (verified). */
    private final Map<String, Long> bindings = new ConcurrentHashMap<>();
    /** nonces received from peers, awaiting OUR proof reply (dedup). */
    private final Map<String, Long> receivedChallenges = new ConcurrentHashMap<>();

    public interface IdentityListener {
        void onPeerVerified(String nodeId, long userId, String displayName);
    }

    private final java.util.List<IdentityListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private MeshIdentityManager() {
        this.config = MeshConfig.getInstance();
        this.meshManager = MeshManager.getInstance();
        this.prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0);
        loadBindings();
    }

    public static synchronized MeshIdentityManager getInstance() {
        if (instance == null) {
            instance = new MeshIdentityManager();
        }
        return instance;
    }

    public void addListener(IdentityListener l) { listeners.add(l); }
    public void removeListener(IdentityListener l) { listeners.remove(l); }

    // ------------------------------------------------------------------
    // Step 1: send challenge when a link comes up
    // ------------------------------------------------------------------

    public void onLinkUp(String nodeId) {
        if (bindings.containsKey(nodeId)) {
            // already verified before; re-announce binding to MeshManager
            Long uid = bindings.get(nodeId);
            meshManager.bindUserId(nodeId, uid, null);
            return;
        }
        String nonce = randomNonce();
        pendingChallenges.put(nodeId, nonce);
        MeshMessage challenge = MeshMessage.fromIdentityChallenge(config.getNodeId(), nonce);
        meshManager.sendMeshMessage(nodeId, challenge);
        FileLog.d(TAG + ": challenge sent to " + nodeId);
    }

    // ------------------------------------------------------------------
    // Step 2a: peer challenged us — reply with proof
    // ------------------------------------------------------------------

    public void handleChallenge(String nodeId, MeshMessage msg) {
        if (msg.getPayload() == null) return;
        String nonce = new String(msg.getPayload(), StandardCharsets.UTF_8);
        receivedChallenges.put(nonce, System.currentTimeMillis());

        long myUserId = currentUserId();
        byte[] sessionKey = meshManager.getSessionKey(nodeId);
        if (sessionKey == null) {
            // no session yet: trigger key exchange; the challenge will be
            // retried after handleKeyExchange completes (see onSessionReady)
            FileLog.w(TAG + ": no session key for " + nodeId + " yet, deferring proof");
            pendingChallenges.put(nodeId, nonce); // remember to answer later
            meshManager.initiateKeyExchange(nodeId);
            return;
        }
        sendProof(nodeId, nonce, myUserId, sessionKey);
    }

    private void sendProof(String nodeId, String nonce, long userId, byte[] sessionKey) {
        String mac = computeMac(sessionKey, userId, nonce);
        MeshMessage proof = MeshMessage.fromIdentity(config.getNodeId(), userId, nonce, mac);
        proof.setSenderDisplayName(displayName());
        meshManager.sendMeshMessage(nodeId, proof);
        FileLog.d(TAG + ": proof sent to " + nodeId + " for uid=" + userId);
    }

    /** Called by MeshManager once a session key becomes available. */
    public void onSessionReady(String nodeId) {
        String nonce = pendingChallenges.remove(nodeId);
        byte[] sessionKey = meshManager.getSessionKey(nodeId);
        if (nonce != null && sessionKey != null && receivedChallenges.containsKey(nonce)) {
            // we still owe a proof for their challenge
            sendProof(nodeId, nonce, currentUserId(), sessionKey);
        } else if (sessionKey != null && !bindings.containsKey(nodeId) && pendingChallenges.get(nodeId) == null) {
            // we haven't challenged them yet — do it now that crypto is ready
            onLinkUp(nodeId);
        }
    }

    // ------------------------------------------------------------------
    // Step 2b: verify peer's proof
    // ------------------------------------------------------------------

    public void handleIdentityProof(String nodeId, MeshMessage msg) {
        if (msg.getPayload() == null) return;
        String payload = new String(msg.getPayload(), StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", 3);
        if (parts.length < 3) {
            FileLog.e(TAG + ": malformed identity from " + nodeId);
            return;
        }
        long claimedUserId;
        try {
            claimedUserId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return;
        }
        String nonce = parts[1];
        String mac = parts[2];

        String expectedNonce = pendingChallenges.get(nodeId);
        if (expectedNonce == null || !expectedNonce.equals(nonce)) {
            FileLog.e(TAG + ": nonce mismatch from " + nodeId + " (replay?)");
            return;
        }
        byte[] sessionKey = meshManager.getSessionKey(nodeId);
        if (sessionKey == null) {
            FileLog.e(TAG + ": no session key to verify " + nodeId);
            return;
        }
        String expectedMac = computeMac(sessionKey, claimedUserId, nonce);
        if (!MessageDigest.isEqual(expectedMac.getBytes(StandardCharsets.UTF_8), mac.getBytes(StandardCharsets.UTF_8))) {
            FileLog.e(TAG + ": MAC verification FAILED for " + nodeId + " claiming uid=" + claimedUserId);
            return;
        }

        pendingChallenges.remove(nodeId);
        bind(nodeId, claimedUserId, msg.getSenderDisplayName());
    }

    private void bind(String nodeId, long userId, String displayName) {
        bindings.put(nodeId, userId);
        prefs.edit().putLong("bind_" + nodeId, userId).apply();
        if (displayName != null) {
            prefs.edit().putString("name_" + nodeId, displayName).apply();
        }
        meshManager.bindUserId(nodeId, userId, displayName);
        FileLog.d(TAG + ": VERIFIED binding " + nodeId + " <-> uid " + userId);
        for (IdentityListener l : listeners) {
            l.onPeerVerified(nodeId, userId, displayName);
        }
    }

    private void loadBindings() {
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getKey().startsWith("bind_") && e.getValue() instanceof Long) {
                bindings.put(e.getKey().substring(5), (Long) e.getValue());
            }
        }
        FileLog.d(TAG + ": loaded " + bindings.size() + " persisted bindings");
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public boolean isVerified(String nodeId) {
        return bindings.containsKey(nodeId);
    }

    public Long getUserIdForNode(String nodeId) {
        return bindings.get(nodeId);
    }

    public String getNodeIdForUser(long userId) {
        for (Map.Entry<String, Long> e : bindings.entrySet()) {
            if (e.getValue() == userId) return e.getKey();
        }
        return null;
    }

    /**
     * Phase 1.2 (server-assisted): sends a one-time marker message to our
     * own Saved Messages through Telegram servers containing
     * nodeId+userId+timestamp. A verifier who can read that account's Saved
     * Messages (i.e., really owns it) can confirm the binding. For now this
     * is a no-op hook; the mesh-local MAC handshake above is the MVP trust
     * anchor (it proves session-key possession, which itself requires the
     * X25519 private key of the advertising device).
     */
    public void verifyThroughServer(String nodeId, long userId) {
        // TODO phase 1.2
    }

    // ------------------------------------------------------------------
    // Crypto helpers
    // ------------------------------------------------------------------

    private String computeMac(byte[] sessionKey, long userId, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sessionKey, "HmacSHA256"));
            mac.update((MAC_CONTEXT + "|" + userId + "|" + nonce).getBytes(StandardCharsets.UTF_8));
            byte[] out = mac.doFinal();
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("identity MAC failed", e);
        }
    }

    private String randomNonce() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private long currentUserId() {
        return UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
    }

    private String displayName() {
        TLRPC.User u = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
        if (u == null) return config.getNodeDisplayName();
        String first = u.first_name != null ? u.first_name : "";
        String last = u.last_name != null ? u.last_name : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? config.getNodeDisplayName() : full;
    }
}
