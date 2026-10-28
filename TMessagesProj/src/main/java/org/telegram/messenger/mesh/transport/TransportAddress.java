package org.telegram.messenger.mesh.transport;

/**
 * Address of a peer on a specific transport, serialized as "transportName:rawAddress".
 *
 * Examples:
 *   nearby:EPIV4XKQ7HQM          (Nearby endpoint id)
 *   tcp:192.168.1.42:47821        (LAN socket)
 *   tcp:10.0.2.2:45679#emu5556    (emulator bridge slot)
 */
public class TransportAddress {

    private final String transport;
    private final String address;

    public TransportAddress(String transport, String address) {
        this.transport = transport;
        this.address = address;
    }

    public static TransportAddress parse(String full) {
        if (full == null) return null;
        int idx = full.indexOf(':');
        if (idx <= 0 || idx == full.length() - 1) return null;
        return new TransportAddress(full.substring(0, idx), full.substring(idx + 1));
    }

    public String getTransport() { return transport; }
    public String getAddress() { return address; }

    public String serialize() {
        return transport + ":" + address;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransportAddress)) return false;
        TransportAddress other = (TransportAddress) o;
        return transport.equals(other.transport) && address.equals(other.address);
    }

    @Override
    public int hashCode() {
        return transport.hashCode() * 31 + address.hashCode();
    }

    @Override
    public String toString() {
        return serialize();
    }
}
