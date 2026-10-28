package org.telegram.messenger.mesh.transport;

import org.telegram.messenger.mesh.MeshMessage;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * MSH1 binary frame codec for mesh messages.
 *
 * Frame layout (big-endian):
 *   MAGIC   4 bytes  "MSH1"
 *   VER     1 byte   protocol version (currently 1)
 *   LEN     4 bytes  body length
 *   BODY    LEN bytes
 *   CRC32   4 bytes  over VER..BODY
 *
 * Body is a fixed-order sequence of primitives:
 *   string messageId
 *   string senderNodeId
 *   string senderDisplayName
 *   long   senderUserId
 *   string recipientNodeId
 *   long   recipientUserId
 *   long   dialogId
 *   int    type
 *   long   timestamp
 *   int    hopCount
 *   string routePath
 *   byte   flags  (bit0 e2ee, bit1 deliveryReceipt, bit2 readReceipt, bit3 relayed)
 *   bytes  payload
 *   bytes  encryptedPayload
 *   bytes  iv
 *   bytes  hmac
 *
 * Strings/bytes are varint-length prefixed. NO Java serialization is used:
 * deserializing untrusted data with ObjectInputStream is an RCE vector.
 */
public class FrameCodec {

    public static final byte[] MAGIC = {'M', 'S', 'H', '1'};
    public static final byte VERSION = 1;
    /** Hard cap: 16 MB frames (text/control); media chunks stay well below. */
    public static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;
    private static final int HEADER_SIZE = 4 + 1 + 4;
    private static final int TRAILER_SIZE = 4;

    private static final byte CTL_KEEPALIVE = 1;
    private static final byte CTL_KEEPALIVE_ACK = 2;

    public static class FrameException extends Exception {
        public FrameException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Encode
    // ------------------------------------------------------------------

    public static byte[] encode(MeshMessage msg) throws FrameException {
        byte[] body = encodeBody(msg);
        byte[] frame = new byte[HEADER_SIZE + body.length + TRAILER_SIZE];
        int p = 0;
        System.arraycopy(MAGIC, 0, frame, p, 4); p += 4;
        frame[p++] = VERSION;
        p = writeInt32(frame, p, body.length);
        System.arraycopy(body, 0, frame, p, body.length); p += body.length;

        CRC32 crc = new CRC32();
        crc.update(frame, 4, HEADER_SIZE - 4 + body.length); // VER + LEN + BODY
        p = writeInt32(frame, p, (int) crc.getValue());
        return frame;
    }

    private static byte[] encodeBody(MeshMessage m) throws FrameException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        writeStr(out, m.getMessageId());
        writeStr(out, m.getSenderNodeId());
        writeStr(out, m.getSenderDisplayName());
        writeLong(out, m.getSenderUserId());
        writeStr(out, m.getRecipientNodeId());
        writeLong(out, m.getRecipientUserId());
        writeLong(out, m.getDialogId());
        writeInt(out, m.getType());
        writeLong(out, m.getTimestamp());
        writeInt(out, m.getHopCount());
        writeStr(out, m.getRoutePath());

        int flags = 0;
        if (m.isE2ee()) flags |= 1;
        if (m.isDeliveryReceipt()) flags |= 2;
        if (m.isReadReceipt()) flags |= 4;
        if (m.isRelayed()) flags |= 8;
        out.write(flags);

        writeBytes(out, m.getPayload());
        writeBytes(out, m.getEncryptedPayload());
        writeBytes(out, m.getIv());
        writeBytes(out, m.getHmac());
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // Control frames (keepalive etc.) — they carry no MeshMessage
    // ------------------------------------------------------------------

    public static final byte[] FRAME_KEEPALIVE = frame(CTL_KEEPALIVE);
    public static final byte[] FRAME_KEEPALIVE_ACK = frame(CTL_KEEPALIVE_ACK);

    private static byte[] frame(byte ctl) {
        byte[] body = new byte[]{ctl};
        byte[] out = new byte[HEADER_SIZE + 1 + TRAILER_SIZE];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        writeInt32(out, 5, 1);
        out[HEADER_SIZE] = ctl;
        CRC32 crc = new CRC32();
        crc.update(out, 4, HEADER_SIZE - 4 + 1);
        writeInt32(out, HEADER_SIZE + 1, (int) crc.getValue());
        return out;
    }

    /** @return >0 control code if this is a control frame, else 0 (data frame). */
    public static int controlCodeOf(byte[] frame) {
        if (frame == null || frame.length != HEADER_SIZE + 1 + TRAILER_SIZE) return 0;
        if (frame[4] != VERSION) return 0;
        if (readInt32(frame, 5) != 1) return 0;
        byte b = frame[HEADER_SIZE];
        return (b == CTL_KEEPALIVE || b == CTL_KEEPALIVE_ACK) ? b : 0;
    }

    // ------------------------------------------------------------------
    // Streaming read (TCP sockets): read one full frame from an InputStream
    // ------------------------------------------------------------------

    /**
     * Reads exactly one frame from the stream. Blocks until complete frame, EOF or corruption.
     * @return raw frame bytes, or null on clean EOF at a frame boundary
     */
    public static byte[] readFrame(InputStream in) throws IOException, FrameException {
        byte[] header = readFully(in, HEADER_SIZE, true);
        if (header == null) return null; // EOF at boundary
        for (int i = 0; i < 4; i++) {
            if (header[i] != MAGIC[i]) throw new FrameException("bad magic in stream");
        }
        if (header[4] != VERSION) throw new FrameException("unsupported version " + header[4]);
        int bodyLen = readInt32(header, 5);
        if (bodyLen < 0 || bodyLen > MAX_FRAME_SIZE) throw new FrameException("bad body length " + bodyLen);
        byte[] rest = readFully(in, bodyLen + TRAILER_SIZE, false);
        if (rest == null) throw new FrameException("EOF in the middle of a frame");
        CRC32 crc = new CRC32();
        crc.update(header, 4, HEADER_SIZE - 4);
        crc.update(rest, 0, bodyLen);
        int expected = readInt32(rest, bodyLen);
        if ((int) crc.getValue() != expected) throw new FrameException("CRC mismatch in stream");
        byte[] frame = new byte[HEADER_SIZE + bodyLen + TRAILER_SIZE];
        System.arraycopy(header, 0, frame, 0, HEADER_SIZE);
        System.arraycopy(rest, 0, frame, HEADER_SIZE, rest.length);
        return frame;
    }

    private static byte[] readFully(InputStream in, int n, boolean allowEofAtStart) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                if (off == 0 && allowEofAtStart) return null;
                return null;
            }
            off += r;
        }
        return buf;
    }

    // ------------------------------------------------------------------
    // Decode
    // ------------------------------------------------------------------

    public static MeshMessage decode(byte[] frame) throws FrameException {
        if (frame == null || frame.length < HEADER_SIZE + TRAILER_SIZE) {
            throw new FrameException("frame too short");
        }
        for (int i = 0; i < 4; i++) {
            if (frame[i] != MAGIC[i]) {
                throw new FrameException("bad magic");
            }
        }
        byte ver = frame[4];
        if (ver != VERSION) {
            throw new FrameException("unsupported version " + ver);
        }
        int bodyLen = readInt32(frame, 5);
        if (bodyLen < 0 || bodyLen > MAX_FRAME_SIZE) {
            throw new FrameException("bad body length " + bodyLen);
        }
        if (frame.length != HEADER_SIZE + bodyLen + TRAILER_SIZE) {
            throw new FrameException("length mismatch: have " + frame.length
                + ", expect " + (HEADER_SIZE + bodyLen + TRAILER_SIZE));
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 4, HEADER_SIZE - 4 + bodyLen);
        int expected = readInt32(frame, HEADER_SIZE + bodyLen);
        if ((int) crc.getValue() != expected) {
            throw new FrameException("CRC mismatch");
        }
        return decodeBody(frame, HEADER_SIZE, bodyLen);
    }

    private static MeshMessage decodeBody(byte[] buf, int off, int len) throws FrameException {
        int end = off + len;
        Reader r = new Reader(buf, off, end);
        MeshMessage m = new MeshMessage();
        m.setMessageId(r.readStr());
        m.setSenderNodeId(r.readStr());
        m.setSenderDisplayName(r.readStr());
        m.setSenderUserId(r.readLong());
        m.setRecipientNodeId(r.readStr());
        m.setRecipientUserId(r.readLong());
        m.setDialogId(r.readLong());
        m.setType(r.readInt());
        m.setTimestamp(r.readLong());
        m.setHopCount(r.readInt());
        m.setRoutePath(r.readStr());
        int flags = r.readByte() & 0xFF;
        m.setE2ee((flags & 1) != 0);
        m.setDeliveryReceipt((flags & 2) != 0);
        m.setReadReceipt((flags & 4) != 0);
        m.setRelayed((flags & 8) != 0);
        m.setPayload(r.readBytes());
        m.setEncryptedPayload(r.readBytes());
        m.setIv(r.readBytes());
        m.setHmac(r.readBytes());
        return m;
    }

    // ------------------------------------------------------------------
    // Primitive helpers (varint length prefixes, big-endian ints)
    // ------------------------------------------------------------------

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static void writeStr(ByteArrayOutputStream out, String s) {
        if (s == null) {
            writeVarint(out, 0);
        } else {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            writeVarint(out, b.length + 1); // +1 => non-null marker
            out.write(b, 0, b.length);
        }
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) {
        if (b == null) {
            writeVarint(out, 0);
        } else {
            writeVarint(out, b.length + 1);
            out.write(b, 0, b.length);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 56; i >= 0; i -= 8) {
            out.write((int) ((v >>> i) & 0xFF));
        }
    }

    private static int writeInt32(byte[] dst, int pos, int v) {
        dst[pos] = (byte) ((v >>> 24) & 0xFF);
        dst[pos + 1] = (byte) ((v >>> 16) & 0xFF);
        dst[pos + 2] = (byte) ((v >>> 8) & 0xFF);
        dst[pos + 3] = (byte) (v & 0xFF);
        return pos + 4;
    }

    private static int readInt32(byte[] src, int pos) {
        return ((src[pos] & 0xFF) << 24) | ((src[pos + 1] & 0xFF) << 16)
            | ((src[pos + 2] & 0xFF) << 8) | (src[pos + 3] & 0xFF);
    }

    /** Bounds-checked sequential reader over a byte range. */
    private static class Reader {
        private final byte[] buf;
        private final int end;
        private int pos;

        Reader(byte[] buf, int pos, int end) {
            this.buf = buf;
            this.pos = pos;
            this.end = end;
        }

        byte readByte() throws FrameException {
            if (pos + 1 > end) throw new FrameException("truncated: byte");
            return buf[pos++];
        }

        int readVarint() throws FrameException {
            int result = 0;
            int shift = 0;
            while (true) {
                if (pos + 1 > end) throw new FrameException("truncated: varint");
                if (shift > 28) throw new FrameException("varint too long");
                byte b = buf[pos++];
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
        }

        int readInt() throws FrameException {
            if (pos + 4 > end) throw new FrameException("truncated: int");
            int v = readInt32(buf, pos);
            pos += 4;
            return v;
        }

        long readLong() throws FrameException {
            if (pos + 8 > end) throw new FrameException("truncated: long");
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (buf[pos++] & 0xFFL);
            }
            return v;
        }

        String readStr() throws FrameException {
            byte[] b = readBytes();
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        byte[] readBytes() throws FrameException {
            int marker = readVarint();
            if (marker == 0) return null;
            int n = marker - 1;
            if (n < 0 || n > MAX_FRAME_SIZE) throw new FrameException("bad blob length " + n);
            if (pos + n > end) throw new FrameException("truncated: blob");
            byte[] out = new byte[n];
            System.arraycopy(buf, pos, out, 0, n);
            pos += n;
            return out;
        }
    }
}
