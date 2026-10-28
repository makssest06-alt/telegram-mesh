package org.telegram.messenger.mesh.transport;

/**
 * Callbacks a transport fires up to the TransportManager.
 * All methods may be called from arbitrary threads; the manager marshals to its own queue.
 */
public interface TransportCallback {

    /** A peer was discovered (not necessarily connected yet). */
    void onPeerDiscovered(String nodeId, String displayName, String transportAddress);

    /** A live bidirectional link to the peer is established. */
    void onLinkUp(String nodeId, String transportAddress);

    /** The link to the peer went down. */
    void onLinkDown(String nodeId, String transportAddress);

    /** The peer disappeared from discovery entirely. */
    void onPeerLost(String nodeId, String transportAddress);

    /** A complete MSH1 frame was received from a peer. */
    void onFrameReceived(String nodeId, String transportAddress, byte[] frame);

    /** The transport itself changed state (started/stopped/error). */
    void onTransportStateChanged(String transportName, boolean running);
}
