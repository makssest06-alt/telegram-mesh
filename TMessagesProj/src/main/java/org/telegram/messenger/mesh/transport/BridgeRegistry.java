package org.telegram.messenger.mesh.transport;

import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for the emulator bridge rendezvous server (Tools/mesh_bridge.py).
 *
 * Emulators sit behind isolated NAT (10.0.2.x) and cannot see each other's
 * mDNS. The bridge runs on the host machine; from inside an emulator the
 * host is reachable at 10.0.2.2. The app registers its node id + local TCP
 * port, the bridge sets up "adb forward" and returns a reachable
 * (host, forwardedPort). Polling LIST returns every other peer as
 * (nodeId, 10.0.2.2, fwdPort); the app then opens direct TCP sockets to
 * peers through those forwards — mesh data never flows through the bridge.
 *
 * Line protocol (text, \n terminated):
 *   -> REGISTER <nodeId> <localPort>\n   <- OK <fwdHost> <fwdPort>\n
 *   -> LIST\n                            <- PEER <nodeId> <host> <port>\n ... END\n
 *   -> REFRESH <nodeId>\n                <- OK\n
 *
 * All socket I/O is serialized on the single scheduler thread; one
 * persistent BufferedReader is used for the lifetime of the socket.
 */
public class BridgeRegistry {

    private static final String TAG = "BridgeRegistry";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final long POLL_INTERVAL_MS = 4_000;
    private static final long REFRESH_INTERVAL_MS = 10_000;

    private final String host;
    private final int port;
    private final String nodeId;
    private final int localTcpPort;
    private final TcpLanTransport transport;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final Set<String> knownPeers = new HashSet<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mesh-bridge");
        t.setDaemon(true);
        return t;
    });

    private Socket socket;
    private BufferedReader reader;
    private OutputStream out;
    private ScheduledFuture<?> pollTask;
    private ScheduledFuture<?> refreshTask;

    public BridgeRegistry(String host, int port, String nodeId, int localTcpPort, TcpLanTransport transport) {
        this.host = host;
        this.port = port;
        this.nodeId = nodeId;
        this.localTcpPort = localTcpPort;
        this.transport = transport;
    }

    public void registerAndPoll() {
        if (active.getAndSet(true)) return;
        FileLog.d(TAG + ": registering with bridge " + host + ":" + port);
        pollTask = scheduler.scheduleWithFixedDelay(this::pollOnce, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        refreshTask = scheduler.scheduleWithFixedDelay(this::refreshOnce, REFRESH_INTERVAL_MS, REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void unregister() {
        if (!active.getAndSet(false)) return;
        if (pollTask != null) pollTask.cancel(true);
        if (refreshTask != null) refreshTask.cancel(true);
        scheduler.execute(this::closeSocket);
        FileLog.d(TAG + ": unregistered");
    }

    /** Connect (or reuse), send REGISTER on fresh connections. Runs on scheduler thread. */
    private void ensureSocket() throws IOException {
        if (socket != null && !socket.isClosed() && socket.isConnected()) return;
        closeSocket();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        socket = s;
        out = s.getOutputStream();
        reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        writeLine("REGISTER " + nodeId + " " + localTcpPort);
        String resp = reader.readLine();
        FileLog.d(TAG + ": register response: " + resp);
        if (resp == null || !resp.startsWith("OK")) {
            closeSocket();
            throw new IOException("bridge registration rejected: " + resp);
        }
    }

    private void writeLine(String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void pollOnce() {
        if (!active.get()) return;
        try {
            ensureSocket();
            writeLine("LIST");
            String line;
            while ((line = reader.readLine()) != null) {
                if ("END".equals(line)) break;
                if (line.startsWith("PEER ")) {
                    String[] p = line.split(" ");
                    if (p.length < 4) continue;
                    String peerNode = p[1];
                    String peerHost = p[2];
                    int peerPort;
                    try {
                        peerPort = Integer.parseInt(p[3]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (peerNode.equals(nodeId)) continue;
                    if (knownPeers.add(peerNode)) {
                        FileLog.d(TAG + ": bridge peer discovered: " + peerNode + " @" + peerHost + ":" + peerPort);
                    }
                    // connectByHostPort is idempotent (no-op if link exists)
                    transport.connectByHostPort(peerNode, peerNode, peerHost, peerPort);
                }
            }
        } catch (IOException e) {
            FileLog.e(TAG + ": poll failed: " + e.getMessage());
            closeSocket();
        }
    }

    private void refreshOnce() {
        if (!active.get()) return;
        try {
            ensureSocket();
            writeLine("REFRESH " + nodeId);
            reader.readLine(); // OK
        } catch (IOException e) {
            closeSocket();
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        reader = null;
        out = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignore) { }
        }
    }
}
