package org.telegram.messenger.mesh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.telegram.messenger.R;

public class MeshNetworkService extends Service {

    private static final String CHANNEL_ID = "mesh_network_channel";
    private static final int NOTIFICATION_ID = 9999;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mesh Network Active")
            .setContentText("Discovery and P2P connections running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();

        startForeground(NOTIFICATION_ID, notification);

        MeshConfig config = MeshConfig.getInstance();
        if (config.isMeshEnabled()) {
            MeshManager.getInstance().start();
            MeshPresenceHandler.getInstance().startPresenceBroadcast();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        MeshManager.getInstance().stop();
        MeshPresenceHandler.getInstance().stopPresenceBroadcast();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notification for Mesh Network foreground service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
