package org.telegram.messenger.mesh.transport;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.mesh.MeshConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP transport for the mesh network. Two discovery modes:
 *
 *  1. NSD mode (physical devices on one Wi-Fi): registers "_tgmesh._tcp."
 *     via NsdManager, discovers peers the same way. Zero configuration.
 *
 *  2. Bridge mode (emulators): emulators cannot see each other's mDNS
 *     (isolated NAT 10.0.2.x networks). Instead both register with a small
 *     rendezvous server running on the host machine (Tools/mesh_bridge.py),
 *     reachable from inside the emulator as 10.0.2.2. The bridge sets up
 *     "adb reverse/forward" port tunnels so emulator A can open a TCP
 *     socket to emulator B through the host.
 *
 * Wire protocol: MSH1 frames (see FrameCodec), length-delimited.
 * Connection handshake (plaintext, before any E2EE traffic):
 *   each side sends HELLO:<nodeId>:<displayName>\n once after connect;
 *   the side with lexicographically smaller nodeId is the "initiator"
 *   and only the initiator keeps its outbound socket (the other closes
 *   the duplicate) to avoid double connections.
 *
 * Keepalive: control frame every 15 s; 3 missed => link down.
 */
public class TcpLanTransport implements IMeshTransport {

    private static final String TAG = "TcpLanTransport";
    public static final String NSD_SERVICE_TYPE = "_tgmesh._tcp.";
    private static final int KEEPALIVE_INTERVAL_MS = 15_000;
    private static final int KEEPALIVE_MISSES_LIMIT = 3;
    private static final int CONNECT_TIMEOUT_MS = 7_000;

    private final Context context;
    private final MeshConfig config;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mesh-tcp");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, TcpLink> linksByNode = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private int localPort = -1;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private TransportCallback callback;
    private volatile BridgeRegistry bridge; // non-null => bridge mode active

    /** One live TCP connection to a peer node. */
    private static class TcpLink {
        final String nodeId;
        final Socket socket;
        final OutputStream out;
        final String displayName;
        final Thread readerThread;
        volatile long lastKeepaliveReceived = System.currentTimeMillis();
        volatile boolean closed = false;

        TcpLink(String nodeId, String displayName, Socket socket, Thread readerThread) throws IOException {
            this.nodeId = nodeId;
            this.displayName = displayName;
            this.socket = socket;
            this.out = socket.getOutputStream();
            this.readerThread = readerThread;
        }

        synchronized void write(byte[] data) throws IOException {
            if (closed) throw new IOException("link closed");
            out.write(data);
            out.flush();
        }

        void close() {
            closed = true;
            try { socket.close(); } catch (IOException ignore) { }
            readerThread.interrupt();
        }
    }

    public TcpLanTransport(Context context) {
        this.context = context;
        this.config = MeshConfig.getInstance();
    }

    @Override
    public String getName() { return "tcp"; }

    @Override
    public int getPriority() { return 10; } // better than nearby(20): usually lower latency

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public void start(TransportCallback cb) {
        if (running.getAndSet(true)) return;
        this.callback = cb;
        executor.execute(() -> {
            try {
                startServerSocket();
                startDiscovery();
                cb.onTransportStateChanged(getName(), true);
                FileLog.d(TAG + ": started on port " + localPort);
            } catch (Exception e) {
                running.set(false);
                FileLog.e(TAG + ": start failed: " + e.getMessage());
                cb.onTransportStateChanged(getName(), false);
            }
        });
        startKeepaliveTicker();
    }

    @Override
    public void stop() {
        if (!running.getAndSet(false)) return;
        stopNsd();
        if (bridge != null) {
            bridge.unregister();
            bridge = null;
        }
        for (TcpLink link : linksByNode.values()) {
            link.close();
        }
        linksByNode.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignore) { }
        serverSocket = null;
        localPort = -1;
        if (callback != null) callback.onTransportStateChanged(getName(), false);
        FileLog.d(TAG + ": stopped");
    }

    // ------------------------------------------------------------------
    // Server side
    // ------------------------------------------------------------------

    private void startServerSocket() throws IOException {
        serverSocket = new ServerSocket(0); // ephemeral port
        localPort = serverSocket.getLocalPort();
        executor.execute(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket sock = serverSocket.accept();
                executor.execute(() -> handleIncoming(sock));
            } catch (IOException e) {
                if (running.get()) {
                    FileLog.e(TAG + ": accept failed: " + e.getMessage());
                }
                return;
            }
        }
    }

    private void handleIncoming(Socket sock) {
        Thread self = Thread.currentThread();
        try {
            sock.setSoTimeout(0);
            sock.setTcpNoDelay(true);
            InputStream in = sock.getInputStream();
            String hello = readHelloLine(in);
            if (hello == null) { sock.close(); return; }
            String[] parts = hello.split(":", 3);
            if (parts.length < 2 || !"HELLO".equals(parts[0])) { sock.close(); return; }
            String peerNode = parts[1];
            String peerName = parts.length > 2 ? parts[2] : peerNode;

            // duplicate-suppression: smaller nodeId keeps the OUTBOUND socket.
            // This socket is inbound => keep it only if OUR nodeId < peer's.
            if (config.getNodeId().compareTo(peerNode) > 0) {
                TcpLink existing = linksByNode.get(peerNode);
                if (existing != null && !existing.closed) {
                    sock.close();
                    return;
                }
                // we have no outbound link; adopt this inbound one
            }

            sendHello(sock.getOutputStream());
            TcpLink link = new TcpLink(peerNode, peerName, sock, self);
            TcpLink old = linksByNode.put(peerNode, link);
            if (old != null) old.close();

            if (callback != null) {
                callback.onPeerDiscovered(peerNode, peerName, addressOf(sock));
                callback.onLinkUp(peerNode, addressOf(sock));
            }
            FileLog.d(TAG + ": inbound link up with " + peerNode);
            readLoop(link, in);
        } catch (Exception e) {
            FileLog.e(TAG + ": incoming handling failed: " + e.getMessage());
            try { sock.close(); } catch (IOException ignore) { }
        }
    }

    // ------------------------------------------------------------------
    // Client side
    // ------------------------------------------------------------------

    public void connectByHostPort(String nodeId, String displayName, String host, int port) {
        if (!running.get()) return;
        executor.execute(() -> {
            TcpLink existing = linksByNode.get(nodeId);
            if (existing != null && !existing.closed) return;
            Thread self = Thread.currentThread();
            Socket sock = new Socket();
            try {
                sock.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                sock.setTcpNoDelay(true);
                sendHello(sock.getOutputStream());
                String theirHello = readHelloLine(sock.getInputStream());
                if (theirHello == null) { sock.close(); return; }
                TcpLink link = new TcpLink(nodeId, displayName, sock, self);
                TcpLink old = linksByNode.put(nodeId, link);
                if (old != null) old.close();
                if (callback != null) {
                    callback.onPeerDiscovered(nodeId, displayName, addressOf(sock));
                    callback.onLinkUp(nodeId, addressOf(sock));
                }
                FileLog.d(TAG + ": outbound link up with " + nodeId + " (" + host + ":" + port + ")");
                readLoop(link, sock.getInputStream());
            } catch (IOException e) {
                FileLog.e(TAG + ": connect to " + host + ":" + port + " failed: " + e.getMessage());
                try { sock.close(); } catch (IOException ignore) { }
            }
        });
    }

    private void readLoop(TcpLink link, InputStream in) {
        try {
            while (running.get() && !link.closed) {
                byte[] frame = FrameCodec.readFrame(in);
                if (frame == null) break; // EOF
                link.lastKeepaliveReceived = System.currentTimeMillis();
                int ctl = FrameCodec.controlCodeOf(frame);
                if (ctl != 0) {
                    if (ctl == 1 && !link.closed) {
                        // respond to keepalive
                        try { link.write(FrameCodec.FRAME_KEEPALIVE_ACK); } catch (IOException ignore) { }
                    }
                    continue;
                }
                if (callback != null) {
                    callback.onFrameReceived(link.nodeId, addressOf(link.socket), frame);
                }
            }
        } catch (IOException | FrameCodec.FrameException e) {
            if (!link.closed) {
                FileLog.e(TAG + ": read loop ended for " + link.nodeId + ": " + e.getMessage());
            }
        } finally {
            link.close();
            linksByNode.remove(link.nodeId, link);
            if (callback != null && running.get()) {
                callback.onLinkDown(link.nodeId, addressOf(link.socket));
            }
        }
    }

    // ------------------------------------------------------------------
    // Send
    // ------------------------------------------------------------------

    @Override
    public void send(String transportAddress, byte[] frame, SendCallback cb) {
        TcpLink link = findLinkForAddress(transportAddress);
        if (link == null) {
            cb.onError("no tcp link for " + transportAddress);
            return;
        }
        executor.execute(() -> {
            try {
                link.write(frame);
                cb.onSent();
            } catch (IOException e) {
                link.close();
                cb.onError("tcp write failed: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isLinkUp(String transportAddress) {
        TcpLink link = findLinkForAddress(transportAddress);
        return link != null && !link.closed;
    }

    private TcpLink findLinkForAddress(String transportAddress) {
        TransportAddress ta = TransportAddress.parse(transportAddress);
        if (ta == null || !"tcp".equals(ta.getTransport())) return null;
        for (Map.Entry<String, TcpLink> en : linksByNode.entrySet()) {
            TcpLink l = en.getValue();
            if (!l.closed && addressOf(l.socket).equals(transportAddress)) return l;
        }
        return null;
    }

    private String addressOf(Socket socket) {
        InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        if (remote == null) return "tcp:unknown";
        return "tcp:" + remote.getAddress().getHostAddress() + ":" + remote.getPort();
    }

    // ------------------------------------------------------------------
    // Handshake lines
    // ------------------------------------------------------------------

    private void sendHello(OutputStream out) throws IOException {
        String hello = "HELLO:" + config.getNodeId() + ":" + config.getNodeDisplayName() + "\n";
        out.write(hello.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads one \n-terminated line, max 512 chars (hard limit against abuse). */
    private String readHelloLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) return sb.length() > 0 ? sb.toString() : null;
            if (c == '\n') return sb.toString();
            if (sb.length() > 512) throw new IOException("hello line too long");
            sb.append((char) c);
        }
    }

    // ------------------------------------------------------------------
    // Keepalive
    // ------------------------------------------------------------------

    private void startKeepaliveTicker() {
        executor.execute(() -> {
            while (running.get()) {
                try { Thread.sleep(KEEPALIVE_INTERVAL_MS); } catch (InterruptedException e) { return; }
                if (!running.get()) return;
                long now = System.currentTimeMillis();
                for (TcpLink link : linksByNode.values()) {
                    try {
                        if (now - link.lastKeepaliveReceived > KEEPALIVE_INTERVAL_MS * KEEPALIVE_MISSES_LIMIT) {
                            FileLog.w(TAG + ": keepalive timeout for " + link.nodeId);
                            link.close();
                            continue;
                        }
                        link.write(FrameCodec.FRAME_KEEPALIVE);
                    } catch (IOException e) {
                        link.close();
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // NSD discovery (physical devices)
    // ------------------------------------------------------------------

    private void startDiscovery() {
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;

            if (config.isVisible()) {
                NsdServiceInfo info = new NsdServiceInfo();
                info.setServiceName("tgmesh-" + config.getNodeId());
                info.setServiceType(NSD_SERVICE_TYPE);
                info.setPort(localPort);
                info.setAttribute("node", config.getNodeId());
                info.setAttribute("name", config.getNodeDisplayName());
                registrationListener = new SimpleNsdListener() {
                    @Override public void onServiceRegistered(NsdServiceInfo i) {
                        FileLog.d(TAG + ": NSD registered " + i.getServiceName());
                    }
                    @Override public void onRegistrationFailed(NsdServiceInfo i, int err) {
                        FileLog.e(TAG + ": NSD registration failed: " + err);
                    }
                };
                nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
            }

            discoveryListener = new SimpleNsdListener() {
                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    nsdManager.resolveService(info, new SimpleNsdListener() {
                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            String peerNode = null;
                            String peerName = null;
                            try {
                                Map<String, byte[]> attrs = resolved.getAttributes();
                                if (attrs.get("node") != null) peerNode = new String(attrs.get("node"), StandardCharsets.UTF_8);
                                if (attrs.get("name") != null) peerName = new String(attrs.get("name"), StandardCharsets.UTF_8);
                            } catch (Exception ignore) { }
                            if (peerNode == null) {
                                String sn = resolved.getServiceName();
                                if (sn != null && sn.startsWith("tgmesh-")) peerNode = sn.substring(7);
                            }
                            if (peerNode == null || peerNode.equals(config.getNodeId())) return;
                            if (resolved.getHost() == null) return;
                            String host = resolved.getHost().getHostAddress();
                            int port = resolved.getPort();
                            if (callback != null) {
                                callback.onPeerDiscovered(peerNode, peerName != null ? peerName : peerNode, "tcp:" + host + ":" + port);
                            }
                            if (config.isAutoConnect()) {
                                connectByHostPort(peerNode, peerName != null ? peerName : peerNode, host, port);
                            }
                        }
                    });
                }
                @Override
                public void onServiceLost(NsdServiceInfo info) {
                    String sn = info.getServiceName();
                    if (sn != null && sn.startsWith("tgmesh-") && callback != null) {
                        callback.onPeerLost(sn.substring(7), "tcp:lost");
                    }
                }
            };
            nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            FileLog.e(TAG + ": NSD start failed: " + e.getMessage());
        }
    }

    private void stopNsd() {
        try {
            if (nsdManager != null) {
                if (discoveryListener != null) nsdManager.stopServiceDiscovery(discoveryListener);
                if (registrationListener != null) nsdManager.unregisterService(registrationListener);
            }
        } catch (Exception ignore) { }
        discoveryListener = null;
        registrationListener = null;
    }

    /** Empty-impl adapter so anonymous listeners only override what they need. */
    private abstract static class SimpleNsdListener implements NsdManager.ResolveListener, NsdManager.RegistrationListener, NsdManager.DiscoveryListener {
        @Override public void onResolveFailed(NsdServiceInfo info, int err) { }
        @Override public void onServiceResolved(NsdServiceInfo info) { }
        @Override public void onServiceRegistered(NsdServiceInfo info) { }
        @Override public void onRegistrationFailed(NsdServiceInfo info, int err) { }
        @Override public void onServiceUnregistered(NsdServiceInfo info) { }
        @Override public void onUnregistrationFailed(NsdServiceInfo info, int err) { }
        @Override public void onStartDiscoveryFailed(String type, int err) { }
        @Override public void onStopDiscoveryFailed(String type, int err) { }
        @Override public void onDiscoveryStarted(String type) { }
        @Override public void onDiscoveryStopped(String type) { }
        @Override public void onServiceFound(NsdServiceInfo info) { }
        @Override public void onServiceLost(NsdServiceInfo info) { }
    }

    // ------------------------------------------------------------------
    // Bridge mode (emulators)
    // ------------------------------------------------------------------

    /** Enables bridge mode: register with host rendezvous server instead of relying on NSD. */
    public void enableBridge(String bridgeHost, int bridgePort) {
        executor.execute(() -> {
            // Wait until the server socket is bound (start() is async).
            int waited = 0;
            while (running.get() && localPort < 0 && waited < 10_000) {
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                waited += 200;
            }
            if (!running.get() || localPort < 0) {
                FileLog.e(TAG + ": bridge aborted, server socket not ready");
                return;
            }
            try {
                bridge = new BridgeRegistry(bridgeHost, bridgePort, config.getNodeId(), localPort, this);
                bridge.registerAndPoll();
            } catch (Exception e) {
                FileLog.e(TAG + ": bridge failed: " + e.getMessage());
            }
        });
    }

    public int getLocalPort() { return localPort; }
}
