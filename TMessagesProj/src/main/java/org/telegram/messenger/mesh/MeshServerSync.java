package org.telegram.messenger.mesh;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Phase 3: convergence between the mesh world and Telegram cloud.
 *
 * Two queues:
 *
 *  A. UPLOAD queue — messages RECEIVED via mesh. When internet is back we
 *     cannot simply re-send them (they are incoming, from the peer).
 *     Instead we rely on the sender's own sync (below). For the receiver,
 *     the local negative-id copy stays until the sender's server copy
 *     arrives through normal updates; MeshInboxProcessor's dedup (by text +
 *     dialog + time window) prevents visible duplicates. MVP: record the
 *     fact of receipt so the sender can later reconcile.
 *
 *  B. OUTBOX flush — messages SENT via mesh while offline. When the network
 *     returns, they are re-sent through the normal MTProto path
 *     (SendMessagesHelper) so the cloud, and all other devices of both
 *     users, converge. The re-send uses the original text and dialog.
 *
 * Fallback policy (see MESH_ROADMAP 3.2):
 *   HYBRID  : mesh-first for bound peers; on transport failure -> server
 *   OFFLINE : mesh + queue into outbox for later server upload
 *   MESH_ONLY: never touch the server
 */
public class MeshServerSync {

    private static final String TAG = "MeshServerSync";
    private static final String PREF_NAME = "mesh_outbox";
    private static final long FLUSH_INTERVAL_MS = 30_000;

    private static volatile MeshServerSync instance;

    private final SharedPreferences prefs;
    private final NetworkSwitcher networkSwitcher;
    private final MeshConfig config;
    private Timer flushTimer;

    /** Pending uploads: "dialogId|timestamp|text" entries. */
    private final Set<String> outbox = new HashSet<>();

    private MeshServerSync() {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0);
        networkSwitcher = NetworkSwitcher.getInstance();
        config = MeshConfig.getInstance();
        loadOutbox();
    }

    public static synchronized MeshServerSync getInstance() {
        if (instance == null) {
            instance = new MeshServerSync();
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // Outbox (mesh-sent messages awaiting cloud convergence)
    // ------------------------------------------------------------------

    /** Called by the SENDER right after a successful mesh send. */
    public void enqueueOutgoingForSync(int currentAccount, long dialogId, String text, long timestamp) {
        if (config.isMeshOnlyMode()) return; // never sync in mesh-only mode
        String key = dialogId + "|" + timestamp + "|" + text;
        synchronized (outbox) {
            outbox.add(key);
        }
        prefs.edit().putStringSet("pending", snapshot()).apply();
    }

    /** Receiver-side bookkeeping; MVP just logs (see class doc, queue A). */
    public void enqueueForUpload(int currentAccount, MeshMessage received) {
        FileLog.d(TAG + ": received mesh msg noted for reconciliation: " + received.getMessageId());
    }

    public void startFlusher() {
        stopFlusher();
        flushTimer = new Timer("MeshSync");
        flushTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                tryFlush(UserConfig.selectedAccount);
            }
        }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS);
    }

    public void stopFlusher() {
        if (flushTimer != null) {
            flushTimer.cancel();
            flushTimer = null;
        }
    }

    /** Flush outbox through the normal server path when internet is back. */
    public void tryFlush(final int currentAccount) {
        if (config.isMeshOnlyMode()) return;
        if (!networkSwitcher.hasInternetConnection()) return;

        final ArrayList<String> batch;
        synchronized (outbox) {
            if (outbox.isEmpty()) return;
            batch = new ArrayList<>(outbox);
        }
        AndroidUtilities.runOnUIThread(() -> {
            SendMessagesHelper helper = SendMessagesHelper.getInstance(currentAccount);
            int sent = 0;
            for (String key : batch) {
                String[] parts = key.split("\\|", 3);
                if (parts.length < 3) continue;
                long dialogId;
                try {
                    dialogId = Long.parseLong(parts[0]);
                } catch (NumberFormatException e) {
                    continue;
                }
                String text = parts[2];
                // Normal Telegram send; this creates the cloud copy.
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId));
                synchronized (outbox) {
                    outbox.remove(key);
                }
                sent++;
            }
            if (sent > 0) {
                prefs.edit().putStringSet("pending", snapshot()).apply();
                FileLog.d(TAG + ": flushed " + sent + " mesh-sent messages to server");
            }
        });
    }

    // ------------------------------------------------------------------
    // Fallback hook used by MeshMessageInterceptor
    // ------------------------------------------------------------------

    /**
     * Mesh send failed => decide what to do.
     * @return true if the caller should continue with the normal MTProto path.
     */
    public boolean shouldFallbackToServer() {
        if (config.isMeshOnlyMode()) return false;
        return networkSwitcher.hasInternetConnection();
    }

    private Set<String> snapshot() {
        synchronized (outbox) {
            return new HashSet<>(outbox);
        }
    }

    private void loadOutbox() {
        Set<String> saved = prefs.getStringSet("pending", null);
        if (saved != null) {
            synchronized (outbox) {
                outbox.addAll(saved);
            }
        }
        FileLog.d(TAG + ": outbox restored with " + outbox.size() + " entries");
    }
}
