package me.leoko.advancedban.network.state;

import me.leoko.advancedban.network.protocol.ProtocolException;
import me.leoko.advancedban.network.protocol.ProtocolConstants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Bounded codec for snapshot and incremental punishment payloads. */
public final class RuntimePunishmentCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_STRING_BYTES = 16 * 1024;
    private static final int MAX_SNAPSHOT_ENTRIES = 100_000;

    public byte[] encode(RuntimePunishment punishment) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT_VERSION);
            writePunishment(out, punishment);
            out.flush();
            return checkedPayload(bytes);
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory encoding failed", impossible);
        }
    }

    public RuntimePunishment decode(byte[] payload) throws ProtocolException {
        try {
            DataInputStream in = input(payload);
            requireVersion(in);
            RuntimePunishment punishment = readPunishment(in);
            requireEnd(in);
            return punishment;
        } catch (EOFException ex) {
            throw new ProtocolException("truncated punishment payload", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new ProtocolException("invalid punishment payload", ex);
        }
    }

    public byte[] encodeSnapshot(Collection<RuntimePunishment> punishments) {
        if (punishments == null || punishments.size() > MAX_SNAPSHOT_ENTRIES) {
            throw new IllegalArgumentException("snapshot is too large");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT_VERSION);
            out.writeInt(punishments.size());
            for (RuntimePunishment punishment : punishments) {
                writePunishment(out, punishment);
                if (bytes.size() > ProtocolConstants.MAX_PAYLOAD_BYTES) {
                    throw new IllegalArgumentException("snapshot payload is too large");
                }
            }
            out.flush();
            return checkedPayload(bytes);
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory encoding failed", impossible);
        }
    }

    public List<RuntimePunishment> decodeSnapshot(byte[] payload) throws ProtocolException {
        try {
            DataInputStream in = input(payload);
            requireVersion(in);
            int size = in.readInt();
            if (size < 0 || size > MAX_SNAPSHOT_ENTRIES) {
                throw new ProtocolException("invalid snapshot size");
            }
            List<RuntimePunishment> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                result.add(readPunishment(in));
            }
            requireEnd(in);
            return result;
        } catch (EOFException ex) {
            throw new ProtocolException("truncated snapshot payload", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new ProtocolException("invalid snapshot payload", ex);
        }
    }

    public byte[] encodeRevoke(long punishmentId) {
        if (punishmentId < 0) {
            throw new IllegalArgumentException("id must not be negative");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT_VERSION);
            out.writeLong(punishmentId);
            out.flush();
            return checkedPayload(bytes);
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory encoding failed", impossible);
        }
    }

    public long decodeRevoke(byte[] payload) throws ProtocolException {
        try {
            DataInputStream in = input(payload);
            requireVersion(in);
            long id = in.readLong();
            if (id < 0) {
                throw new ProtocolException("invalid punishment id");
            }
            requireEnd(in);
            return id;
        } catch (EOFException ex) {
            throw new ProtocolException("truncated revoke payload", ex);
        } catch (IOException ex) {
            throw new ProtocolException("invalid revoke payload", ex);
        }
    }

    private DataInputStream input(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length == 0
                || payload.length > ProtocolConstants.MAX_PAYLOAD_BYTES) {
            throw new ProtocolException("empty payload");
        }
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    private void requireVersion(DataInputStream in) throws IOException, ProtocolException {
        if (in.readUnsignedByte() != FORMAT_VERSION) {
            throw new ProtocolException("unsupported punishment payload format");
        }
    }

    private void requireEnd(DataInputStream in) throws IOException, ProtocolException {
        if (in.available() != 0) {
            throw new ProtocolException("trailing punishment payload data");
        }
    }

    private void writePunishment(DataOutputStream out, RuntimePunishment punishment) throws IOException {
        out.writeLong(punishment.getId());
        writeNullable(out, punishment.getTargetName());
        writeNullable(out, punishment.getTargetUuid());
        writeNullable(out, punishment.getTargetIp());
        out.writeByte(punishment.getType().getWireId());
        writeString(out, punishment.getReason());
        writeString(out, punishment.getOperator());
        out.writeLong(punishment.getStartMillis());
        out.writeLong(punishment.getEndMillis());
        out.writeBoolean(punishment.isPermanent());
        out.writeBoolean(punishment.isSilent());
        writeString(out, punishment.getCalculation());
    }

    private RuntimePunishment readPunishment(DataInputStream in) throws IOException, ProtocolException {
        long id = in.readLong();
        String name = readNullable(in);
        String uuid = readNullable(in);
        String ip = readNullable(in);
        RuntimePunishmentType type = RuntimePunishmentType.fromWireId(in.readUnsignedByte());
        if (type == null) {
            throw new ProtocolException("invalid punishment type");
        }
        String reason = readString(in);
        String operator = readString(in);
        long start = in.readLong();
        long end = in.readLong();
        boolean permanent = in.readBoolean();
        boolean silent = in.readBoolean();
        String calculation = readString(in);
        return new RuntimePunishment(id, name, uuid, ip, type, reason,
                operator, start, end, permanent, silent, calculation);
    }

    private void writeNullable(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            writeString(out, value);
        }
    }

    private String readNullable(DataInputStream in) throws IOException, ProtocolException {
        return in.readBoolean() ? readString(in) : null;
    }

    private void writeString(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("punishment string is too large");
        }
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private String readString(DataInputStream in) throws IOException, ProtocolException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new ProtocolException("invalid punishment string length");
        }
        byte[] encoded = new byte[length];
        in.readFully(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private byte[] checkedPayload(ByteArrayOutputStream bytes) {
        if (bytes.size() > ProtocolConstants.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("punishment payload is too large");
        }
        return bytes.toByteArray();
    }
}
