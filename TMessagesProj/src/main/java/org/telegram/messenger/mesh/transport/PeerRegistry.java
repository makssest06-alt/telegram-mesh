package org.telegram.messenger.mesh.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks logical peers (by nodeId) across multiple transports.
 * The same physical device may be discovered via Nearby AND TCP-LAN simultaneously;
 * the registry keeps a single logical entry with a prioritized list of addresses.
 *
 * Thread-safe: all mutable state lives in ConcurrentHashMap.
 */
public class PeerRegistry {

    public static class PeerEntry {
        public final String nodeId;
        public volatile String displayName;
        /** addresses ordered by transport priority (best first), managed under lock */
        private final List<TransportAddress> addresses = new ArrayList<>();
        /** nodeId -> alive link */
        private final Map<String, Boolean> linkUp = new ConcurrentHashMap<>();

        PeerEntry(String nodeId, String displayName) {
            this.nodeId = nodeId;
            this.displayName = displayName;
        }

        public synchronized void addAddress(TransportAddress addr, int priority) {
            addresses.remove(addr);
            int pos = addresses.size();
            for (int i = 0; i < addresses.size(); i++) {
                if (getPriorityOf(addresses.get(i)) > priority) {
                    pos = i;
                    break;
                }
            }
            addresses.add(pos, addr);
        }

        public synchronized void removeAddress(TransportAddress addr) {
            addresses.remove(addr);
            linkUp.remove(addr.serialize());
        }

        public synchronized List<TransportAddress> getAddresses() {
            return new ArrayList<>(addresses);
        }

        /** Best address that currently has a live link, else best known address. */
        public synchronized TransportAddress getBestAddress() {
            for (TransportAddress a : addresses) {
                if (Boolean.TRUE.equals(linkUp.get(a.serialize()))) {
                    return a;
                }
            }
            return addresses.isEmpty() ? null : addresses.get(0);
        }

        public synchronized boolean hasLiveLink() {
            for (TransportAddress a : addresses) {
                if (Boolean.TRUE.equals(linkUp.get(a.serialize()))) return true;
            }
            return false;
        }

        public void setLinkUp(String serializedAddr, boolean up) {
            linkUp.put(serializedAddr, up);
        }

        public synchronized int addressCount() {
            return addresses.size();
        }

        private int getPriorityOf(TransportAddress a) {
            // priorities are looked up statically; lower = better
            switch (a.getTransport()) {
                case "tcp": return 10;
                case "nearby": return 20;
                default: return 100;
            }
        }
    }

    private final Map<String, PeerEntry> entries = new ConcurrentHashMap<>();

    public PeerEntry getOrCreate(String nodeId, String displayName) {
        PeerEntry e = entries.get(nodeId);
        if (e == null) {
            e = new PeerEntry(nodeId, displayName);
            PeerEntry prev = entries.putIfAbsent(nodeId, e);
            if (prev != null) e = prev;
        } else if (displayName != null) {
            e.displayName = displayName;
        }
        return e;
    }

    public PeerEntry get(String nodeId) {
        return entries.get(nodeId);
    }

    public void remove(String nodeId) {
        entries.remove(nodeId);
    }

    public List<PeerEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public int size() {
        return entries.size();
    }

    /** Find nodeId by one of its transport addresses (used when a frame arrives). */
    public String findNodeIdByAddress(TransportAddress addr) {
        for (Map.Entry<String, PeerEntry> en : entries.entrySet()) {
            for (TransportAddress a : en.getValue().getAddresses()) {
                if (a.equals(addr)) return en.getKey();
            }
        }
        return null;
    }
}
