package org.telegram.messenger.mesh.transport;

/**
 * Transport abstraction for the mesh network.
 * Every physical channel (Nearby Connections, TCP LAN, emulator bridge)
 * implements this interface so that MeshManager stays transport-agnostic.
 */
public interface IMeshTransport {

    /** Human-readable transport name, also used as address prefix, e.g. "nearby", "tcp". */
    String getName();

    /** Lower value = higher priority when TransportManager picks a channel. */
    int getPriority();

    /** Start advertising (if visible) and discovering peers. */
    void start(TransportCallback callback);

    /** Stop everything and release all resources. */
    void stop();

    /** True if the transport is currently operational. */
    boolean isRunning();

    /**
     * Send a raw MSH1 frame to a peer.
     * @param transportAddress address in "name:addr" form (see TransportAddress)
     * @param frame bytes produced by FrameCodec.encode
     * @param cb invoked once with success or error
     */
    void send(String transportAddress, byte[] frame, SendCallback cb);

    /** True if we currently have a live link to this address. */
    boolean isLinkUp(String transportAddress);

    interface SendCallback {
        void onSent();
        void onError(String error);
    }
}
