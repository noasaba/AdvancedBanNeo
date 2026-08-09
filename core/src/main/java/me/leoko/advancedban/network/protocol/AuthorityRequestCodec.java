package me.leoko.advancedban.network.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Strict bounded codec for Agent mutation/command requests and results. */
public final class AuthorityRequestCodec {
    private static final int VERSION = 1;
    private static final int MAX_STRING = 16 * 1024;

    public byte[] encode(AuthorityRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeLong(request.getRequestId().getMostSignificantBits());
            out.writeLong(request.getRequestId().getLeastSignificantBits());
            out.writeByte(request.getAction().ordinal());
            out.writeByte(request.getSenderKind().ordinal());
            writeString(out, request.getSenderUuid());
            writeString(out, request.getSenderName());
            out.writeShort(request.getValues().size());
            for (String value : request.getValues()) {
                writeString(out, value == null ? "" : value);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory request encoding failed", impossible);
        }
    }

    public AuthorityRequest decode(byte[] payload) throws ProtocolException {
        try {
            DataInputStream in = input(payload);
            requireVersion(in);
            UUID requestId = new UUID(in.readLong(), in.readLong());
            int action = in.readUnsignedByte();
            int sender = in.readUnsignedByte();
            if (action >= AuthorityRequest.Action.values().length
                    || sender >= AuthorityRequest.SenderKind.values().length) {
                throw new ProtocolException("unknown Authority request type");
            }
            String senderUuid = readString(in);
            String senderName = readString(in);
            int count = in.readUnsignedShort();
            if (count > 128) {
                throw new ProtocolException("too many request values");
            }
            List<String> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(readString(in));
            }
            requireEnd(in);
            return new AuthorityRequest(requestId, AuthorityRequest.Action.values()[action],
                    AuthorityRequest.SenderKind.values()[sender], senderUuid, senderName, values);
        } catch (EOFException exception) {
            throw new ProtocolException("truncated Authority request", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ProtocolException("invalid Authority request", exception);
        }
    }

    public byte[] encodeResult(UUID requestId, boolean success, String detail) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeLong(requestId.getMostSignificantBits());
            out.writeLong(requestId.getLeastSignificantBits());
            out.writeBoolean(success);
            writeString(out, detail == null ? "" : detail);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory result encoding failed", impossible);
        }
    }

    public Result decodeResult(byte[] payload) throws ProtocolException {
        try {
            DataInputStream in = input(payload);
            requireVersion(in);
            Result result = new Result(new UUID(in.readLong(), in.readLong()),
                    in.readBoolean(), readString(in));
            requireEnd(in);
            return result;
        } catch (EOFException exception) {
            throw new ProtocolException("truncated Authority result", exception);
        } catch (IOException exception) {
            throw new ProtocolException("invalid Authority result", exception);
        }
    }

    private static DataInputStream input(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length == 0 || payload.length > ProtocolConstants.MAX_PAYLOAD_BYTES) {
            throw new ProtocolException("invalid Authority payload size");
        }
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    private static void requireVersion(DataInputStream in) throws IOException, ProtocolException {
        if (in.readUnsignedByte() != VERSION) {
            throw new ProtocolException("unsupported Authority payload format");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_STRING) {
            throw new IllegalArgumentException("request string is too large");
        }
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static String readString(DataInputStream in) throws IOException, ProtocolException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING) {
            throw new ProtocolException("invalid request string length");
        }
        byte[] encoded = new byte[length];
        in.readFully(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static void requireEnd(DataInputStream in) throws IOException, ProtocolException {
        if (in.available() != 0) {
            throw new ProtocolException("trailing Authority payload data");
        }
    }

    public static final class Result {
        private final UUID requestId;
        private final boolean success;
        private final String detail;

        private Result(UUID requestId, boolean success, String detail) {
            this.requestId = requestId;
            this.success = success;
            this.detail = detail;
        }

        public UUID getRequestId() { return requestId; }
        public boolean isSuccess() { return success; }
        public String getDetail() { return detail; }
    }
}
