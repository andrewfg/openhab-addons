/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.homekit.internal.camera.srtp;

import java.util.Arrays;

import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.CTRModeCipher;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Encrypts and decrypts RTP packets using SRTP as defined in RFC 3711, for the AES_CM_128_HMAC_SHA1_80 and
 * AES_CM_256_HMAC_SHA1_80 crypto suites. An instance handles one direction of one stream, with a single SSRC.
 * <p>
 * For optimisation reasons the code is NOT thread safe; the caller must ensure that packets are processed in
 * order and not concurrently.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class SrtpSession {

    private static final int RTP_HEADER_LENGTH = 12;
    private static final int AUTH_TAG_LENGTH = 10;
    private static final int AUTH_KEY_LENGTH = 20;
    private static final int BLOCK_LENGTH = 16;

    private static final byte LABEL_ENCRYPTION = 0x00;
    private static final byte LABEL_AUTHENTICATION = 0x01;
    private static final byte LABEL_SALT = 0x02;

    private final HMac hmac = new HMac(new SHA1Digest());
    private final byte[] macBuffer = new byte[20];
    private final byte[] iv = new byte[BLOCK_LENGTH];
    private final CTRModeCipher keystream = SICBlockCipher.newInstance(AESEngine.newInstance());

    private final byte[] sessionKey;
    private final byte[] sessionSalt;
    private final KeyParameter authKey;

    private long highestIndex = -1;

    private SrtpSession(byte[] sessionKey, byte[] sessionSalt, byte[] authKey) {
        this.sessionKey = sessionKey;
        this.sessionSalt = sessionSalt;
        this.authKey = new KeyParameter(authKey);
    }

    /**
     * Creates a session with keys derived from the master key and salt, as defined in RFC 3711 section 4.3.
     *
     * @param parameters the SRTP crypto suite and master key and salt
     * @throws IllegalArgumentException if the crypto suite is NONE or the key or salt lengths are wrong
     */
    public static SrtpSession create(SrtpParameters parameters) {
        SrtpCryptoSuite suite = parameters.cryptoSuite();
        if (suite == SrtpCryptoSuite.NONE) {
            throw new IllegalArgumentException("SRTP crypto suite NONE cannot be used");
        }
        byte[] masterKey = parameters.masterKey();
        byte[] masterSalt = parameters.masterSalt();
        if (masterKey.length != suite.keyLength || masterSalt.length != suite.saltLength) {
            throw new IllegalArgumentException("Invalid SRTP key or salt length for " + suite);
        }
        return new SrtpSession(deriveKey(masterKey, masterSalt, LABEL_ENCRYPTION, suite.keyLength),
                deriveKey(masterKey, masterSalt, LABEL_SALT, suite.saltLength),
                deriveKey(masterKey, masterSalt, LABEL_AUTHENTICATION, AUTH_KEY_LENGTH));
    }

    /**
     * Encrypts and authenticates an RTP packet, returning the SRTP packet.
     *
     * @param rtp the RTP packet
     * @return the SRTP packet
     * @throws IllegalArgumentException if the packet is not a valid RTP packet
     */
    public int encrypt(byte[] rtp, int rtpLength, byte[] srtp) {
        int headerLength = headerLength(rtp, rtpLength);
        if (headerLength < 0) {
            throw new IllegalArgumentException("Invalid RTP packet");
        }
        long index = nextIndex(sequenceNumber(rtp));
        int ssrc = ssrc(rtp);
        System.arraycopy(rtp, 0, srtp, 0, rtpLength);
        applyKeystream(srtp, headerLength, rtpLength, ssrc, index);
        authenticate(srtp, rtpLength, index, srtp, rtpLength);
        return rtpLength + AUTH_TAG_LENGTH;
    }

    /**
     * Authenticates and decrypts an SRTP packet, returning the RTP packet.
     *
     * @param srtp the SRTP packet
     * @return the RTP packet, or null if the packet is malformed or fails authentication
     */
    public int decrypt(byte[] srtp, int srtpLength, byte[] rtp) {
        int headerLength = headerLength(srtp, srtpLength);
        int rtpLength = srtpLength - AUTH_TAG_LENGTH;
        if (headerLength < 0 || rtpLength < headerLength) {
            return -1;
        }
        long index = estimateIndex(sequenceNumber(srtp));
        if (!authenticateAndVerify(srtp, rtpLength, index)) {
            return -1;
        }
        System.arraycopy(srtp, 0, rtp, 0, rtpLength);
        applyKeystream(rtp, headerLength, rtpLength, ssrc(srtp), index);
        advance(index);
        return rtpLength;
    }

    /**
     * Returns the packet index for the next outgoing packet, and records it.
     */
    private long nextIndex(int seq) {
        long index = estimateIndex(seq);
        advance(index);
        return index;
    }

    /**
     * Estimates the 48-bit packet index (rollover counter and sequence number) of a packet, as in RFC 3711 appendix A.
     * The rollover counter that brings the sequence number closest to the highest index seen so far is chosen.
     */
    private long estimateIndex(int seq) {
        if (highestIndex < 0) {
            return seq;
        }
        long roc = highestIndex >>> 16;
        long best = seq;
        long bestDistance = Long.MAX_VALUE;
        for (long candidateRoc = Math.max(0, roc - 1); candidateRoc <= roc + 1; candidateRoc++) {
            long candidate = (candidateRoc << 16) | seq;
            long distance = Math.abs(candidate - highestIndex);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private void advance(long index) {
        if (index > highestIndex) {
            highestIndex = index;
        }
    }

    /**
     * Applies the AES-CM keystream to the payload of a packet, in place, from the given offset to the given end.
     */
    private void applyKeystream(byte[] packet, int from, int to, int syncSource, long index) {
        Arrays.fill(iv, (byte) 0);
        System.arraycopy(sessionSalt, 0, iv, 0, sessionSalt.length);
        for (int i = 0; i < 4; i++) {
            iv[4 + i] ^= (byte) (syncSource >>> (24 - 8 * i));
        }
        for (int i = 0; i < 6; i++) {
            iv[8 + i] ^= (byte) (index >>> (40 - 8 * i));
        }
        aesCtr(keystream, sessionKey, iv, packet, from, to);
    }

    /**
     * Computes the HMAC-SHA1 authentication tag over the packet up to the given end, followed by the rollover counter.
     */
    private void authenticate(byte[] packet, int end, long index, byte[] output, int outputOffset) {
        hmac.reset();
        hmac.init(authKey);
        hmac.update(packet, 0, end);
        long roc = index >>> 16;
        hmac.update((byte) (roc >>> 24));
        hmac.update((byte) (roc >>> 16));
        hmac.update((byte) (roc >>> 8));
        hmac.update((byte) roc);
        hmac.doFinal(macBuffer, 0);
        System.arraycopy(macBuffer, 0, output, outputOffset, AUTH_TAG_LENGTH);
    }

    private boolean authenticateAndVerify(byte[] packet, int end, long index) {
        hmac.reset();
        hmac.init(authKey);
        hmac.update(packet, 0, end);
        long roc = index >>> 16;
        hmac.update((byte) (roc >>> 24));
        hmac.update((byte) (roc >>> 16));
        hmac.update((byte) (roc >>> 8));
        hmac.update((byte) roc);
        hmac.doFinal(macBuffer, 0);
        int diff = 0;
        for (int i = 0; i < AUTH_TAG_LENGTH; i++) {
            diff |= macBuffer[i] ^ packet[end + i];
        }
        return diff == 0;
    }

    /**
     * Derives a session key, salt or authentication key from the master key and salt (RFC 3711 section 4.3.1, with a
     * zero key derivation rate).
     */
    public static byte[] deriveKey(byte[] masterKey, byte[] masterSalt, byte label, int length) {
        byte[] iv = new byte[BLOCK_LENGTH];
        System.arraycopy(masterSalt, 0, iv, 0, masterSalt.length);
        iv[7] ^= label;
        byte[] key = new byte[length];
        aesCtr(SICBlockCipher.newInstance(AESEngine.newInstance()), masterKey, iv, key, 0, length);
        return key;
    }

    /**
     * XORs the AES counter-mode keystream for the given IV into data[from..to), in place.
     */
    private static void aesCtr(CTRModeCipher cipher, byte[] key, byte[] iv, byte[] data, int from, int to) {
        cipher.init(true, new ParametersWithIV(new KeyParameter(key), iv));
        cipher.processBytes(data, from, to - from, data, from);
    }

    /**
     * Returns the length of the RTP header including CSRC identifiers and any header extension, or -1 if the packet is
     * too short or has the wrong version.
     */
    private static int headerLength(byte[] packet, int packetLength) {
        if (packetLength < RTP_HEADER_LENGTH || (packet[0] & 0xC0) != 0x80) {
            return -1;
        }
        int length = RTP_HEADER_LENGTH + 4 * (packet[0] & 0x0F);
        if ((packet[0] & 0x10) != 0) {
            if (packetLength < length + 4) {
                return -1;
            }
            int extensionWords = ((packet[length + 2] & 0xFF) << 8) | (packet[length + 3] & 0xFF);
            length += 4 + 4 * extensionWords;
        }
        return length <= packetLength ? length : -1;
    }

    private static int sequenceNumber(byte[] packet) {
        return ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
    }

    private static int ssrc(byte[] packet) {
        return ((packet[8] & 0xFF) << 24) | ((packet[9] & 0xFF) << 16) | ((packet[10] & 0xFF) << 8)
                | (packet[11] & 0xFF);
    }
}
