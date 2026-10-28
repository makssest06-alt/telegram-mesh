package org.telegram.messenger.mesh;

import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.DialogCell;

import java.util.Timer;
import java.util.TimerTask;

public class MeshPresenceHandler {

    private static MeshPresenceHandler instance;
    private final MeshManager meshManager;
    private final MeshConfig config;
    private Timer presenceTimer;

    private MeshPresenceHandler() {
        this.meshManager = MeshManager.getInstance();
        this.config = MeshConfig.getInstance();
    }

    public static synchronized MeshPresenceHandler getInstance() {
        if (instance == null) {
            instance = new MeshPresenceHandler();
        }
        return instance;
    }

    public void startPresenceBroadcast() {
        stopPresenceBroadcast();
        presenceTimer = new Timer("MeshPresence");
        presenceTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (config.isMeshEnabled() && meshManager.isRunning()) {
                    meshManager.broadcastPresence(true);
                }
            }
        }, 0, 30_000);
    }

    public void stopPresenceBroadcast() {
        if (presenceTimer != null) {
            presenceTimer.cancel();
            presenceTimer = null;
        }
    }

    public boolean isMeshUser(long userId) {
        MeshPeer peer = meshManager.findPeerByUserId(userId);
        return peer != null && peer.isMeshUser() && peer.isAlive();
    }

    public boolean shouldShowMeshIndicator(long userId) {
        return config.isMeshEnabled() && isMeshUser(userId);
    }
}
