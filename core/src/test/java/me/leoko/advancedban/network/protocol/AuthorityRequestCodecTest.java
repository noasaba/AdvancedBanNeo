package me.leoko.advancedban.network.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityRequestCodecTest {
    private final AuthorityRequestCodec codec = new AuthorityRequestCodec();

    @Test
    void everyActionAndSenderKindRoundTripsWithoutChangingIdentityOrArguments() throws Exception {
        for (AuthorityRequest.Action action : AuthorityRequest.Action.values()) {
            for (AuthorityRequest.SenderKind sender : AuthorityRequest.SenderKind.values()) {
                UUID requestId = UUID.randomUUID();
                List<String> values = Arrays.asList("ban", "Player", "日本語の理由", "1d");
                AuthorityRequest original = new AuthorityRequest(requestId, action, sender,
                        "123456781234123412341234567890ab", "Operator", values);

                AuthorityRequest decoded = codec.decode(codec.encode(original));

                assertEquals(requestId, decoded.getRequestId());
                assertEquals(action, decoded.getAction());
                assertEquals(sender, decoded.getSenderKind());
                assertEquals(original.getSenderUuid(), decoded.getSenderUuid());
                assertEquals(original.getSenderName(), decoded.getSenderName());
                assertEquals(values, decoded.getValues());
            }
        }
    }

    @Test
    void requestTakesAnImmutableSnapshotOfArgumentsAndNormalizesNullableSenderFields() {
        List<String> mutable = new ArrayList<>(Collections.singletonList("before"));
        AuthorityRequest request = new AuthorityRequest(UUID.randomUUID(), AuthorityRequest.Action.COMMAND,
                AuthorityRequest.SenderKind.API, null, null, mutable);
        mutable.set(0, "after");

        assertEquals(Collections.singletonList("before"), request.getValues());
        assertEquals("", request.getSenderUuid());
        assertEquals("", request.getSenderName());
        assertThrows(UnsupportedOperationException.class, () -> request.getValues().add("mutation"));
    }

    @Test
    void resultRoundTripsSuccessFailureUnicodeAndNullableDetail() throws Exception {
        UUID successfulId = UUID.randomUUID();
        AuthorityRequestCodec.Result successful = codec.decodeResult(
                codec.encodeResult(successfulId, true, "受理済み"));
        assertEquals(successfulId, successful.getRequestId());
        assertTrue(successful.isSuccess());
        assertEquals("受理済み", successful.getDetail());

        UUID failedId = UUID.randomUUID();
        AuthorityRequestCodec.Result failed = codec.decodeResult(codec.encodeResult(failedId, false, null));
        assertEquals(failedId, failed.getRequestId());
        assertFalse(failed.isSuccess());
        assertEquals("", failed.getDetail());
    }

    @Test
    void rejectsOversizedCollectionsAndStringsBeforeTheyReachAuthorityExecution() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 129; i++) {
            tooMany.add("value");
        }
        assertThrows(IllegalArgumentException.class, () -> new AuthorityRequest(UUID.randomUUID(),
                AuthorityRequest.Action.COMMAND, AuthorityRequest.SenderKind.CONSOLE, "", "console", tooMany));

        char[] chars = new char[16 * 1024 + 1];
        Arrays.fill(chars, 'x');
        AuthorityRequest tooLarge = new AuthorityRequest(UUID.randomUUID(), AuthorityRequest.Action.COMMAND,
                AuthorityRequest.SenderKind.CONSOLE, "", "console",
                Collections.singletonList(new String(chars)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(tooLarge));
    }

    @Test
    void rejectsNullEmptyOversizedTruncatedTrailingAndUnknownWireValues() throws Exception {
        AuthorityRequest valid = new AuthorityRequest(UUID.randomUUID(), AuthorityRequest.Action.COMMAND,
                AuthorityRequest.SenderKind.CONSOLE, "", "console", Collections.singletonList("ban"));
        byte[] encoded = codec.encode(valid);

        assertThrows(ProtocolException.class, () -> codec.decode(null));
        assertThrows(ProtocolException.class, () -> codec.decode(new byte[0]));
        assertThrows(ProtocolException.class,
                () -> codec.decode(new byte[ProtocolConstants.MAX_PAYLOAD_BYTES + 1]));
        assertThrows(ProtocolException.class, () -> codec.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(ProtocolException.class, () -> codec.decode(Arrays.copyOf(encoded, encoded.length + 1)));

        byte[] unsupportedVersion = encoded.clone();
        unsupportedVersion[0] = 2;
        assertThrows(ProtocolException.class, () -> codec.decode(unsupportedVersion));

        byte[] unknownAction = encoded.clone();
        unknownAction[17] = (byte) 0xff;
        assertThrows(ProtocolException.class, () -> codec.decode(unknownAction));

        byte[] unknownSender = encoded.clone();
        unknownSender[18] = (byte) 0xff;
        assertThrows(ProtocolException.class, () -> codec.decode(unknownSender));

        assertThrows(ProtocolException.class, () -> codec.decode(requestWithValueCount(129)));
    }

    @Test
    void resultCodecRejectsTruncationTrailingBytesAndWrongVersion() throws Exception {
        byte[] encoded = codec.encodeResult(UUID.randomUUID(), true, "accepted");
        assertThrows(ProtocolException.class,
                () -> codec.decodeResult(Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(ProtocolException.class,
                () -> codec.decodeResult(Arrays.copyOf(encoded, encoded.length + 1)));
        encoded[0] = 2;
        assertThrows(ProtocolException.class, () -> codec.decodeResult(encoded));
    }

    private byte[] requestWithValueCount(int count) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(1);
        output.writeLong(0L);
        output.writeLong(1L);
        output.writeByte(AuthorityRequest.Action.COMMAND.ordinal());
        output.writeByte(AuthorityRequest.SenderKind.CONSOLE.ordinal());
        output.writeInt(0);
        output.writeInt(0);
        output.writeShort(count);
        output.flush();
        return bytes.toByteArray();
    }
}
