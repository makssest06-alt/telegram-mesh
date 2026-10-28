package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.mesh.MeshConfig;
import org.telegram.messenger.mesh.MeshManager;
import org.telegram.messenger.mesh.MeshPeer;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MeshSettingsActivity extends BaseFragment {

    private ListAdapter listAdapter;
    private RecyclerListView listView;
    private MeshConfig meshConfig;
    private MeshManager meshManager;

    private int rowCount;
    private int generalHeaderRow;
    private int enableRow;
    private int visibleRow;
    private int autoConnectRow;
    private int autoFallbackRow;
    private int meshOnlyRow;
    private int e2eeRow;
    private int transportHeaderRow;
    private int tcpTransportRow;
    private int nearbyTransportRow;
    private int bridgeHostRow;
    private int statusHeaderRow;
    private int statusRow;
    private int peerCountRow;
    private int showMapRow;
    private int peersHeaderRow;
    private int peerStartRow;
    private int peerEndRow;
    private int errorInfoRow;

    private List<MeshPeer> peerList = new ArrayList<>();
    private boolean isGoogleServicesAvailable;

    @Override
    public View createView(Context context) {
        meshConfig = MeshConfig.getInstance();
        meshManager = MeshManager.getInstance();
        
        isGoogleServicesAvailable = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS;

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString("MeshNetwork", R.string.MeshNetwork));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);
        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter);
        listView.setVerticalScrollBarEnabled(false);

        listView.setOnItemClickListener((view, position) -> {
            if (position == enableRow) {
                boolean enabled = !meshConfig.isMeshEnabled();
                meshConfig.setMeshEnabled(enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                if (enabled) {
                    meshManager.start();
                } else {
                    meshManager.stop();
                }
                updateRows();
            } else if (position == visibleRow) {
                boolean visible = !meshConfig.isVisible();
                meshConfig.setVisible(visible);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(visible);
                }
                meshManager.restart();
            } else if (position == autoConnectRow) {
                boolean auto = !meshConfig.isAutoConnect();
                meshConfig.setAutoConnect(auto);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(auto);
                }
            } else if (position == autoFallbackRow) {
                boolean fallback = !meshConfig.isAutoFallback();
                meshConfig.setAutoFallback(fallback);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(fallback);
                }
            } else if (position == meshOnlyRow) {
                boolean only = !meshConfig.isMeshOnlyMode();
                meshConfig.setMeshOnlyMode(only);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(only);
                }
            } else if (position == e2eeRow) {
                boolean e2ee = !meshConfig.isE2eeEnabled();
                meshConfig.setE2eeEnabled(e2ee);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(e2ee);
                }
            } else if (position == tcpTransportRow) {
                boolean tcp = !meshConfig.isTcpTransportEnabled();
                meshConfig.setTcpTransportEnabled(tcp);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(tcp);
                }
                meshManager.restart();
            } else if (position == nearbyTransportRow) {
                boolean nearby = !meshConfig.isNearbyTransportEnabled();
                meshConfig.setNearbyTransportEnabled(nearby);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(nearby);
                }
                meshManager.restart();
            } else if (position == bridgeHostRow) {
                showBridgeHostDialog();
            } else if (position == showMapRow) {
                presentFragment(new MeshMapActivity());
            }
        });

        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateRows();
        return fragmentView;
    }

    private void showBridgeHostDialog() {
        if (getParentActivity() == null) return;
        final android.widget.EditText input = new android.widget.EditText(getContext());
        input.setText(meshConfig.getBridgeHost() + ":" + meshConfig.getBridgePort());
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setHint("host:port (empty = disable bridge)");
        new AlertDialog.Builder(getContext())
            .setTitle("Bridge server (emulator testing)")
            .setView(input)
            .setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString("Save", R.string.Save), (d, w) -> {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) {
                    meshConfig.setBridgeHost("");
                } else {
                    String[] parts = text.split(":");
                    meshConfig.setBridgeHost(parts[0]);
                    if (parts.length > 1) {
                        try {
                            meshConfig.setBridgePort(Integer.parseInt(parts[1].trim()));
                        } catch (NumberFormatException ignore) { }
                    }
                }
                meshManager.restart();
                updateRows();
            })
            .show();
    }

    private void updateRows() {
        rowCount = 0;
        generalHeaderRow = rowCount++;
        enableRow = rowCount++;
        visibleRow = rowCount++;
        autoConnectRow = rowCount++;
        autoFallbackRow = rowCount++;
        meshOnlyRow = rowCount++;
        e2eeRow = rowCount++;

        transportHeaderRow = rowCount++;
        tcpTransportRow = rowCount++;
        bridgeHostRow = rowCount++;
        if (isGoogleServicesAvailable) {
            errorInfoRow = -1;
            nearbyTransportRow = rowCount++;
        } else {
            errorInfoRow = rowCount++;
            nearbyTransportRow = -1;
        }

        statusHeaderRow = rowCount++;
        statusRow = rowCount++;
        peerCountRow = rowCount++;
        showMapRow = rowCount++;

        peerList.clear();
        if (meshConfig.isMeshEnabled()) {
            peersHeaderRow = rowCount++;
            peerStartRow = rowCount;
            Collection<MeshPeer> peers = meshManager.getPeers();
            if (peers != null) {
                peerList.addAll(peers);
            }
            rowCount += peerList.size();
            peerEndRow = rowCount;
        } else {
            peersHeaderRow = -1;
            peerStartRow = -1;
            peerEndRow = -1;
        }

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRows();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        public ListAdapter(Context context) { this.context = context; }

        @Override
        public int getItemCount() { return rowCount; }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == enableRow || position == visibleRow || position == autoConnectRow
                || position == autoFallbackRow || position == e2eeRow || position == showMapRow || position == meshOnlyRow
                || position == tcpTransportRow || position == nearbyTransportRow || position == bridgeHostRow
                || (position >= peerStartRow && position < peerEndRow);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == generalHeaderRow || position == statusHeaderRow || position == peersHeaderRow || position == transportHeaderRow) return 0;
            if (position == enableRow || position == visibleRow || position == autoConnectRow || position == autoFallbackRow
                || position == e2eeRow || position == meshOnlyRow || position == tcpTransportRow || position == nearbyTransportRow) return 1;
            if (position == statusRow || position == peerCountRow || position == showMapRow || position == bridgeHostRow) return 2;
            if (position == errorInfoRow) return 4;
            if (position >= peerStartRow && position < peerEndRow) return 3;
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0: view = new HeaderCell(context); break;
                case 1: view = new TextCheckCell(context); break;
                case 2: view = new TextSettingsCell(context); break;
                case 3: view = new PeerCell(context); break;
                case 4: view = new TextInfoPrivacyCell(context); break;
                default: view = new View(context); break;
            }
            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = getItemViewType(position);
            switch (type) {
                case 0:
                    HeaderCell header = (HeaderCell) holder.itemView;
                    if (position == generalHeaderRow) header.setText(LocaleController.getString("MeshGeneral", R.string.MeshGeneral));
                    else if (position == transportHeaderRow) header.setText("Transports");
                    else if (position == statusHeaderRow) header.setText(LocaleController.getString("MeshStatus", R.string.MeshStatus));
                    else if (position == peersHeaderRow) header.setText(LocaleController.getString("MeshPeers", R.string.MeshPeers));
                    break;
                case 1:
                    TextCheckCell check = (TextCheckCell) holder.itemView;
                    if (position == enableRow) check.setTextAndCheck(LocaleController.getString("MeshEnable", R.string.MeshEnable), meshConfig.isMeshEnabled(), true);
                    else if (position == visibleRow) check.setTextAndCheck(LocaleController.getString("MeshVisible", R.string.MeshVisible), meshConfig.isVisible(), true);
                    else if (position == autoConnectRow) check.setTextAndCheck(LocaleController.getString("MeshAutoConnect", R.string.MeshAutoConnect), meshConfig.isAutoConnect(), true);
                    else if (position == autoFallbackRow) check.setTextAndCheck(LocaleController.getString("MeshAutoFallback", R.string.MeshAutoFallback), meshConfig.isAutoFallback(), true);
                    else if (position == meshOnlyRow) check.setTextAndCheck(LocaleController.getString("MeshOnlyMode", R.string.MeshOnlyMode), meshConfig.isMeshOnlyMode(), true);
                    else if (position == e2eeRow) check.setTextAndCheck(LocaleController.getString("MeshE2EE", R.string.MeshE2EE), meshConfig.isE2eeEnabled(), false);
                    else if (position == tcpTransportRow) check.setTextAndCheck("TCP LAN transport", meshConfig.isTcpTransportEnabled(), true);
                    else if (position == nearbyTransportRow) check.setTextAndCheck("Nearby Connections (GMS)", meshConfig.isNearbyTransportEnabled(), true);
                    break;
                case 2:
                    TextSettingsCell settings = (TextSettingsCell) holder.itemView;
                    if (position == statusRow) {
                        String status = meshManager.isRunning() ? LocaleController.getString("MeshActive", R.string.MeshActive) : LocaleController.getString("MeshInactive", R.string.MeshInactive);
                        settings.setTextAndValue(LocaleController.getString("MeshConnectionStatus", R.string.MeshConnectionStatus), status, true);
                    } else if (position == peerCountRow) settings.setTextAndValue(LocaleController.getString("MeshPeers", R.string.MeshPeers), String.valueOf(peerList.size()), true);
                    else if (position == showMapRow) settings.setText(LocaleController.getString("MeshShowMap", R.string.MeshShowMap), false);
                    else if (position == bridgeHostRow) {
                        String bridge = meshConfig.getBridgeHost();
                        String value = (bridge == null || bridge.isEmpty()) ? "disabled" : bridge + ":" + meshConfig.getBridgePort();
                        settings.setTextAndValue("Bridge server (emulators)", value, false);
                    }
                    break;
                case 3:
                    ((PeerCell) holder.itemView).setPeer(peerList.get(position - peerStartRow));
                    break;
                case 4:
                    TextInfoPrivacyCell info = (TextInfoPrivacyCell) holder.itemView;
                    info.setText("Google Play Services are not available. Nearby transport is disabled, but TCP LAN transport works without GMS.");
                    break;
            }
        }
    }

    private static class PeerCell extends FrameLayout {
        private final TextView nameText, statusText, signalText;
        private final View statusDot;
        public PeerCell(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
            nameText = new TextView(context); nameText.setTextSize(16); nameText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(nameText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            statusText = new TextView(context); statusText.setTextSize(13); statusText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(statusText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 24, 0, 0));
            signalText = new TextView(context); signalText.setTextSize(12); signalText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            addView(signalText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 44, 0, 0));
            statusDot = new View(context); statusDot.setBackgroundColor(0xFF00D26A);
            addView(statusDot, LayoutHelper.createFrame(12, 12));
        }
        public void setPeer(MeshPeer peer) {
            nameText.setText(peer.getDisplayName() != null ? peer.getDisplayName() : peer.getNodeId());
            statusText.setText(peer.getStatus().name());
            signalText.setText("Hops: " + peer.getHopCount() + " | " + (peer.isAlive() ? "ONLINE" : "OFFLINE"));
            statusDot.setVisibility(peer.isAlive() ? VISIBLE : GONE);
        }
    }
}
