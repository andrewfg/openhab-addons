package org.openhab.binding.homekit.internal.media;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.enums.SrtpCryptoSuite;
import org.openhab.binding.homekit.internal.media.tlv.SrtpParameters;

/**
 * Tests for {@link SrtpSession}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class SrtpSessionTest {

    private static final byte[] MASTER_KEY = hex("E1F97A0D3E018BE0D64FA32C06DE4139");
    private static final byte[] MASTER_SALT = hex("0EC675AD498AFEEBB6960B3AABE6");

    private static final SrtpParameters PARAMETERS = new SrtpParameters(SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_80,
            MASTER_KEY, MASTER_SALT);

    @Test
    void deriveKeysMatchRfc3711TestVectors() {
        assertArrayEquals(hex("C61E7A93744F39EE10734AFE3FF7A087"),
                SrtpSession.deriveKey(MASTER_KEY, MASTER_SALT, (byte) 0x00, 16));
        assertArrayEquals(hex("30CBBC08863D8C85D49DB34A9AE1"),
                SrtpSession.deriveKey(MASTER_KEY, MASTER_SALT, (byte) 0x02, 14));
        assertArrayEquals(hex("CEBE321F6FF7716B6FD4AB49AF256A156D38BAA4"),
                SrtpSession.deriveKey(MASTER_KEY, MASTER_SALT, (byte) 0x01, 20));
    }

    @Test
    void roundTripAcrossSequenceNumberWrap() {
        SrtpSession sender = SrtpSession.create(PARAMETERS);
        SrtpSession receiver = SrtpSession.create(PARAMETERS);
        for (int i = 0; i < 70_000; i++) {
            int seq = (65_000 + i) & 0xFFFF;
            byte[] rtp = rtpPacket(seq, 0x12345678, i);
            byte[] srtp = sender.protect(rtp);
            assertEquals(rtp.length + 10, srtp.length);
            byte[] decrypted = receiver.unprotect(srtp);
            assertNotNull(decrypted, "packet " + i + " failed authentication");
            assertArrayEquals(rtp, decrypted);
        }
    }

    @Test
    void protectedPacketDiffersFromPlainText() {
        byte[] rtp = rtpPacket(1, 0xCAFEBABE, 0);
        byte[] srtp = SrtpSession.create(PARAMETERS).protect(rtp);
        assertFalse(Arrays.equals(Arrays.copyOfRange(rtp, 12, rtp.length),
                Arrays.copyOfRange(srtp, 12, rtp.length)));
    }

    @Test
    void tamperedPacketIsRejected() {
        byte[] srtp = SrtpSession.create(PARAMETERS).protect(rtpPacket(7, 0x01020304, 0));
        srtp[srtp.length - 15] ^= 0x01;
        assertNull(SrtpSession.create(PARAMETERS).unprotect(srtp));
    }

    @Test
    void packetWithWrongKeyIsRejected() {
        byte[] srtp = SrtpSession.create(PARAMETERS).protect(rtpPacket(7, 0x01020304, 0));
        SrtpParameters other = new SrtpParameters(SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_80,
                hex("00112233445566778899AABBCCDDEEFF"), MASTER_SALT);
        assertNull(SrtpSession.create(other).unprotect(srtp));
    }

    @Test
    void truncatedPacketIsRejected() {
        assertNull(SrtpSession.create(PARAMETERS).unprotect(new byte[20]));
    }

    @Test
    void noneCryptoSuiteIsRejected() {
        SrtpParameters none = new SrtpParameters(SrtpCryptoSuite.NONE, new byte[0], new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> SrtpSession.create(none));
    }

    private static byte[] rtpPacket(int seq, int ssrc, int i) {
        byte[] payload = payload(i);
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) 96;
        packet[2] = (byte) (seq >>> 8);
        packet[3] = (byte) seq;
        packet[4] = (byte) (i >>> 24);
        packet[5] = (byte) (i >>> 16);
        packet[6] = (byte) (i >>> 8);
        packet[7] = (byte) i;
        packet[8] = (byte) (ssrc >>> 24);
        packet[9] = (byte) (ssrc >>> 16);
        packet[10] = (byte) (ssrc >>> 8);
        packet[11] = (byte) ssrc;
        System.arraycopy(payload, 0, packet, 12, payload.length);
        return packet;
    }

    private static byte[] payload(int i) {
        byte[] payload = new byte[80];
        for (int j = 0; j < payload.length; j++) {
            payload[j] = (byte) (i + j * 31);
        }
        return payload;
    }

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s);
    }
}
