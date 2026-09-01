package me.cortex.voxy.client.lod;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Strict outer envelope for the terrain stream. */
public final class FrameCodec {
    public static final int MAGIC = 0x59584f56;
    public static final int HEADER_BYTES = 14;
    public static final int C_HELLO = WireMessage.C_HELLO;
    public static final int C_PING = WireMessage.C_PING;
    public static final int C_CREDIT = WireMessage.C_CREDIT;
    public static final int C_CAMERA_DOMAIN = WireMessage.C_CAMERA_DOMAIN;
    public static final int S_HELLO = WireMessage.S_HELLO;
    public static final int S_PONG = WireMessage.S_PONG;
    public static final int S_CAMERA_DOMAIN = WireMessage.S_CAMERA_DOMAIN;
    public static final int S_ERROR = WireMessage.S_ERROR;

    private FrameCodec() {}

    @FunctionalInterface
    public interface FrameAdmission {
        MemoryBudget.Reservation reserve(long bytes) throws IOException;
    }

    /** One owned and, in production, budgeted frame buffer. */
    public static final class Frame implements AutoCloseable {
        private final int type;
        private final byte[] payload;
        private MemoryBudget.Reservation memory;

        public Frame(int type, byte[] payload) {
            this(type, payload, null);
        }

        private Frame(int type, byte[] payload, MemoryBudget.Reservation memory) {
            if (type < 0 || type > 0xffff) throw new IllegalArgumentException("frame type is not u16");
            this.payload = Objects.requireNonNull(payload, "payload");
            if (payload.length > WireMessage.MAX_FRAME_PAYLOAD) {
                throw new IllegalArgumentException("frame is oversized");
            }
            this.type = type;
            this.memory = memory;
        }

        public int type() { return this.type; }
        public byte[] payload() { return this.payload; }

        @Override
        public void close() {
            MemoryBudget.Reservation retained = this.memory;
            this.memory = null;
            if (retained != null) retained.close();
        }
    }

    /** Rejects impossible server payload lengths directly from the outer header. */
    public static Frame readServer(DataInputStream input) throws IOException, FrameException {
        return readBounded(input, true, null);
    }

    /** Reserves the complete payload before allocating it. */
    public static Frame readServer(DataInputStream input, MemoryBudget memory)
            throws IOException, FrameException {
        Objects.requireNonNull(memory, "memory");
        return readBounded(input, true, bytes -> memory.tryReserve(
                MemoryBudget.Allocation.of(MemoryBudget.Pool.IN_FLIGHT, bytes))
                .orElseThrow(() -> new IOException(
                        "terrain memory budget cannot admit an inbound frame")));
    }

    /** Uses an interruptible session admission policy after validating the untrusted header. */
    public static Frame readServer(DataInputStream input, FrameAdmission admission)
            throws IOException, FrameException {
        return readBounded(input, true, Objects.requireNonNull(admission, "admission"));
    }

    private static Frame readBounded(DataInputStream input, boolean server, FrameAdmission admission)
            throws IOException, FrameException {
        Objects.requireNonNull(input, "input");
        byte[] rawHeader = new byte[HEADER_BYTES];
        try {
            input.readFully(rawHeader);
        } catch (EOFException exception) {
            throw new EOFException("terrain server closed the connection");
        }
        ByteBuffer header = little(rawHeader);
        if (header.getInt() != MAGIC) throw new FrameException("bad frame magic");
        int type = Short.toUnsignedInt(header.getShort());
        int length = header.getInt();
        int expectedChecksum = header.getInt();
        if (length < 0 || length > WireMessage.MAX_FRAME_PAYLOAD) {
            throw new FrameException("invalid frame length");
        }
        if (server) validateServerLength(type, length);
        MemoryBudget.Reservation reservation = null;
        if (admission != null) {
            reservation = admission.reserve(HEADER_BYTES + (long) length);
            if (reservation == null) throw new IOException(
                    "terrain frame admission returned no reservation");
        }
        try {
            byte[] payload = new byte[length];
            input.readFully(payload);
            if (WireMessage.checksum(payload) != expectedChecksum) {
                throw new FrameException("frame CRC32C mismatch");
            }
            Frame result = new Frame(type, payload, reservation);
            reservation = null;
            return result;
        } finally {
            if (reservation != null) reservation.close();
        }
    }

    private static void validateServerLength(int type, int length) throws FrameException {
        int minimumRootAnnounce = 2 + 1 + Long.BYTES
                + 5 * WireMessage.HASH_BYTES;
        int maximumRootAnnounce = minimumRootAnnounce - 1
                + WireMessage.MAX_DIMENSION_BYTES;
        int minimumBundle = Long.BYTES + 2 * WireMessage.HASH_BYTES
                + Short.BYTES + WireMessage.HASH_BYTES + 17;
        boolean valid = switch (type) {
            case S_HELLO -> length == Long.BYTES;
            case S_PONG -> length == Long.BYTES;
            case S_CAMERA_DOMAIN -> length == 113;
            case S_ERROR -> length >= 4 && length <= 4 + 4096;
            case WireMessage.S_ROOT_ANNOUNCE ->
                    length >= minimumRootAnnounce && length <= maximumRootAnnounce;
            case WireMessage.S_SUBTREE_DATA, WireMessage.S_OBJECT_BUNDLE ->
                    length >= minimumBundle && length <= WireMessage.MAX_FRAME_PAYLOAD;
            default -> false;
        };
        if (!valid) throw new FrameException("impossible server frame length or type");
    }

    public static void write(OutputStream output, int type, byte[] payload) throws IOException {
        Objects.requireNonNull(output, "output");
        Frame frame = new Frame(type, payload);
        validateClientLength(frame.type(), frame.payload().length);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC).putShort((short) frame.type()).putInt(frame.payload().length)
                .putInt(WireMessage.checksum(frame.payload()));
        output.write(header.array());
        output.write(frame.payload());
    }

    private static void validateClientLength(int type, int length) throws FrameException {
        int rootAndCounts = Long.BYTES + 2 * WireMessage.HASH_BYTES + Short.BYTES;
        int minimumRootReady = Short.BYTES + 1
                + Long.BYTES + 2 * WireMessage.HASH_BYTES;
        int maximumRootReady = minimumRootReady - 1 + WireMessage.MAX_DIMENSION_BYTES;
        boolean valid = switch (type) {
            case C_HELLO -> length >= Short.BYTES + 1
                    && length <= Short.BYTES + WireMessage.MAX_DIMENSION_BYTES;
            case C_PING, C_CREDIT -> length == Long.BYTES;
            case WireMessage.C_SUBTREE_REQUEST, WireMessage.C_OBJECT_REQUEST ->
                    length >= rootAndCounts + WireMessage.HASH_BYTES
                    && length <= rootAndCounts + WireMessage.MAX_REQUEST_ENTRIES
                    * WireMessage.HASH_BYTES
                    && (length - rootAndCounts) % WireMessage.HASH_BYTES == 0;
            case WireMessage.C_ROOT_READY ->
                    length >= minimumRootReady && length <= maximumRootReady;
            case C_CAMERA_DOMAIN -> length == Long.BYTES + 2 * WireMessage.HASH_BYTES
                    + Long.BYTES + 3 * Integer.BYTES;
            default -> false;
        };
        if (!valid) throw new FrameException("impossible client frame length or type");
    }

    private static ByteBuffer little(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static final class FrameException extends IOException {
        public FrameException(String message) { super(message); }
    }
}
