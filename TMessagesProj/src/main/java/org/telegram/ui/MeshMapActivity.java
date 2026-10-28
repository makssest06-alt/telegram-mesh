package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.mesh.MeshManager;
import org.telegram.messenger.mesh.MeshPeer;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

public class MeshMapActivity extends BaseFragment {

    private MeshManager meshManager;
    private MeshMapView mapView;

    @Override
    public View createView(Context context) {
        meshManager = MeshManager.getInstance();

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString("MeshNetworkMap", R.string.MeshNetworkMap));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        mapView = new MeshMapView(context);
        mapView.setPeers(meshManager.getPeers());
        fragmentView = mapView;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.setPeers(meshManager.getPeers());
        }
    }

    private static class MeshMapView extends View {

        private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path edgePath = new Path();
        private final Random random = new Random();

        private List<MeshPeer> peers = new ArrayList<>();
        private final float[] nodePositions;
        private int nodeCount;

        public MeshMapView(Context context) {
            super(context);
            nodePaint.setColor(0xFF6C63FF);
            nodePaint.setStyle(Paint.Style.FILL);
            nodePaint.setStrokeWidth(4);

            edgePaint.setColor(0x886C63FF);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(2);

            centerPaint.setColor(0xFF00D26A);
            centerPaint.setStyle(Paint.Style.FILL);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(AndroidUtilities.dp(10));
            textPaint.setTextAlign(Paint.Align.CENTER);

            nodePositions = new float[200];
        }

        public void setPeers(Collection<MeshPeer> peerCollection) {
            peers.clear();
            if (peerCollection != null) {
                peers.addAll(peerCollection);
            }
            nodeCount = Math.min(peers.size(), 100);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();
            int cx = width / 2;
            int cy = height / 2;

            canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundGray));

            centerPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(24), centerPaint);
            canvas.drawText("YOU", cx, cy + AndroidUtilities.dp(4), textPaint);

            if (peers.isEmpty()) {
                textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                textPaint.setTextSize(AndroidUtilities.dp(16));
                String noPeers = LocaleController.getString("MeshNoPeers", R.string.MeshNoPeers);
                canvas.drawText(noPeers, cx, cy - AndroidUtilities.dp(60), textPaint);
                textPaint.setTextSize(AndroidUtilities.dp(10));
                return;
            }

            int count = Math.min(peers.size(), 20);
            int R = Math.min(width, height) / 2 - AndroidUtilities.dp(40);

            for (int i = 0; i < count; i++) {
                double angle = 2 * Math.PI * i / count;
                float px = cx + (float) (R * Math.cos(angle));
                float py = cy + (float) (R * Math.sin(angle));
                nodePositions[i * 2] = px;
                nodePositions[i * 2 + 1] = py;

                edgePath.reset();
                edgePath.moveTo(cx, cy);
                edgePath.lineTo(px, py);
                canvas.drawPath(edgePath, edgePaint);

                MeshPeer peer = peers.get(i);
                int nodeColor = peer.isAlive() ? 0xFF6C63FF : 0xFF888888;
                nodePaint.setColor(nodeColor);
                canvas.drawCircle(px, py, AndroidUtilities.dp(16), nodePaint);

                String label = peer.getDisplayName();
                if (label != null && label.length() > 10) {
                    label = label.substring(0, 10) + "..";
                }
                canvas.drawText(label != null ? label : peer.getNodeId(), px, py + AndroidUtilities.dp(4), textPaint);

                String hops = "H:" + peer.getHopCount();
                textPaint.setTextSize(AndroidUtilities.dp(8));
                canvas.drawText(hops, px, py - AndroidUtilities.dp(18), textPaint);
                textPaint.setTextSize(AndroidUtilities.dp(10));
            }

            if (count > 1) {
                for (int i = 0; i < count; i++) {
                    for (int j = i + 1; j < count; j++) {
                        if (peers.get(i).getHopCount() <= 1 && peers.get(j).getHopCount() <= 1) {
                            edgePath.reset();
                            edgePath.moveTo(nodePositions[i * 2], nodePositions[i * 2 + 1]);
                            edgePath.lineTo(nodePositions[j * 2], nodePositions[j * 2 + 1]);
                            edgePaint.setAlpha(40);
                            canvas.drawPath(edgePath, edgePaint);
                            edgePaint.setAlpha(136);
                        }
                    }
                }
            }
        }
    }
}
