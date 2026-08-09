package me.leoko.advancedban.network.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Bounded, deterministic binary codec. Its unsigned form is the exact HMAC input. */
public final class ProtocolCodec {
    private static final int MAGIC = 0x41424E50; // ABNP

    public byte[] encodeUnsigned(ProtocolPacket packet) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writePacket(out, packet);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory encoding failed", impossible);
        }
    }

    public byte[] encode(SignedPacket signedPacket) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writePacket(out, signedPacket.getPacket());
            byte[] tag = signedPacket.getAuthenticationTag();
            out.writeByte(tag.length);
            out.write(tag);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory encoding failed", impossible);
        }
    }

    public SignedPacket decode(byte[] encoded) throws ProtocolException {
        if (encoded == null || encoded.length == 0 || encoded.length > ProtocolConstants.MAX_PACKET_BYTES) {
            throw new ProtocolException("invalid packet size");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new ProtocolException("invalid packet magic");
            }
            int wireVersion = in.readUnsignedByte();
            if (wireVersion != ProtocolConstants.WIRE_FORMAT_VERSION) {
                throw new ProtocolException("unsupported wire format");
            }
            int protocolVersion = in.readInt();
            UUID sessionId = new UUID(in.readLong(), in.readLong());
            long sequence = in.readLong();
            long timestamp = in.readLong();
            String source = readString(in);
            String target = readString(in);
            MessageKind kind = MessageKind.fromWireId(in.readUnsignedByte());
            if (kind == null) {
                throw new ProtocolException("unknown message kind");
            }
            int payloadLength = in.readInt();
            if (payloadLength < 0 || payloadLength > ProtocolConstants.MAX_PAYLOAD_BYTES) {
                throw new ProtocolException("invalid payload length");
            }
            byte[] payload = new byte[payloadLength];
            in.readFully(payload);
            int tagLength = in.readUnsignedByte();
            if (tagLength != ProtocolConstants.HMAC_SIZE_BYTES) {
                throw new ProtocolException("invalid authentication tag length");
            }
            byte[] tag = new byte[tagLength];
            in.readFully(tag);
            if (in.available() != 0) {
                throw new ProtocolException("trailing packet data");
            }
            ProtocolPacket packet = new ProtocolPacket(protocolVersion, sessionId, sequence, timestamp,
                    source, target, kind, payload);
            return new SignedPacket(packet, tag);
        } catch (EOFException ex) {
            throw new ProtocolException("truncated packet", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new ProtocolException("invalid packet", ex);
        }
    }

    private void writePacket(DataOutputStream out, ProtocolPacket packet) throws IOException {
        out.writeInt(MAGIC);
        out.writeByte(ProtocolConstants.WIRE_FORMAT_VERSION);
        out.writeInt(packet.getProtocolVersion());
        out.writeLong(packet.getSessionId().getMostSignificantBits());
        out.writeLong(packet.getSessionId().getLeastSignificantBits());
        out.writeLong(packet.getSequence());
        out.writeLong(packet.getTimestampMillis());
        writeString(out, packet.getSourceNode());
        writeString(out, packet.getTargetNode());
        out.writeByte(packet.getKind().getWireId());
        byte[] payload = packet.getPayload();
        out.writeInt(payload.length);
        out.write(payload);
    }

    private void writeString(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > ProtocolConstants.MAX_NODE_ID_BYTES) {
            throw new IllegalArgumentException("invalid node id length");
        }
        out.writeShort(encoded.length);
        out.write(encoded);
    }

    private String readString(DataInputStream in) throws IOException, ProtocolException {
        int length = in.readUnsignedShort();
        if (length == 0 || length > ProtocolConstants.MAX_NODE_ID_BYTES) {
            throw new ProtocolException("invalid node id length");
        }
        byte[] encoded = new byte[length];
        in.readFully(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }
}
