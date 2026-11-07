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
package org.openhab.binding.homekit.internal.media;

import java.security.MessageDigest;
import java.util.Arrays;

import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.CTRModeCipher;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.enums.SrtpCryptoSuite;
import org.openhab.binding.homekit.internal.media.tlv.SrtpParameters;

/**
 * Protects and unprotects RTP packets using SRTP as defined in RFC 3711, for the AES_CM_128_HMAC_SHA1_80 and
 * AES_CM_256_HMAC_SHA1_80 crypto suites.
 * <p>
 * An instance handles one direction of one stream, with a single SSRC. It is not thread-safe.
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

    private final byte[] sessionKey;
    private final byte[] sessionSalt;
    private final byte[] authKey;
    private final CTRModeCipher keystream = SICBlockCipher.newInstance(AESEngine.newInstance());

    /** The highest packet index seen so far, or -1 if no packet has been processed. */
    private long highestIndex = -1;

    private SrtpSession(byte[] sessionKey, byte[] sessionSalt, byte[] authKey) {
        this.sessionKey = sessionKey;
        this.sessionSalt = sessionSalt;
        this.authKey = authKey;
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
    public byte[] protect(byte[] rtp) {
        int headerLength = headerLength(rtp);
        if (headerLength < 0) {
            throw new IllegalArgumentException("Invalid RTP packet");
        }
        long index = nextIndex(sequenceNumber(rtp));
        int ssrc = ssrc(rtp);
        byte[] srtp = Arrays.copyOf(rtp, rtp.length + AUTH_TAG_LENGTH);
        applyKeystream(srtp, headerLength, rtp.length, ssrc, index);
        byte[] tag = authenticate(srtp, rtp.length, index);
        System.arraycopy(tag, 0, srtp, rtp.length, AUTH_TAG_LENGTH);
        return srtp;
    }

    /**
     * Authenticates and decrypts an SRTP packet, returning the RTP packet.
     *
     * @param srtp the SRTP packet
     * @return the RTP packet, or null if the packet is malformed or fails authentication
     */
    public byte @Nullable [] unprotect(byte[] srtp) {
        int headerLength = headerLength(srtp);
        int payloadEnd = srtp.length - AUTH_TAG_LENGTH;
        if (headerLength < 0 || payloadEnd < headerLength) {
            return null;
        }
        long index = estimateIndex(sequenceNumber(srtp));
        byte[] expected = authenticate(srtp, payloadEnd, index);
        byte[] received = Arrays.copyOfRange(srtp, payloadEnd, srtp.length);
        if (!MessageDigest.isEqual(expected, received)) {
            return null;
        }
        byte[] rtp = Arrays.copyOf(srtp, payloadEnd);
        applyKeystream(rtp, headerLength, payloadEnd, ssrc(srtp), index);
        advance(index);
        return rtp;
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
    private void applyKeystream(byte[] packet, int from, int to, int ssrc, long index) {
        byte[] iv = new byte[BLOCK_LENGTH];
        System.arraycopy(sessionSalt, 0, iv, 0, sessionSalt.length);
        for (int i = 0; i < 4; i++) {
            iv[4 + i] ^= (byte) (ssrc >>> (24 - 8 * i));
        }
        for (int i = 0; i < 6; i++) {
            iv[8 + i] ^= (byte) (index >>> (40 - 8 * i));
        }
        aesCtr(keystream, sessionKey, iv, packet, from, to);
    }

    /**
     * Computes the HMAC-SHA1 authentication tag over the packet up to the given end, followed by the rollover counter.
     */
    private byte[] authenticate(byte[] packet, int end, long index) {
        HMac hmac = new HMac(new SHA1Digest());
        hmac.init(new KeyParameter(authKey));
        hmac.update(packet, 0, end);
        long roc = index >>> 16;
        hmac.update((byte) (roc >>> 24));
        hmac.update((byte) (roc >>> 16));
        hmac.update((byte) (roc >>> 8));
        hmac.update((byte) roc);
        byte[] mac = new byte[hmac.getMacSize()];
        hmac.doFinal(mac, 0);
        return Arrays.copyOf(mac, AUTH_TAG_LENGTH);
    }

    /**
     * Derives a session key, salt or authentication key from the master key and salt (RFC 3711 section 4.3.1, with a
     * zero key derivation rate).
     */
    static byte[] deriveKey(byte[] masterKey, byte[] masterSalt, byte label, int length) {
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
        byte[] block = new byte[BLOCK_LENGTH];
        byte[] out = new byte[BLOCK_LENGTH];
        for (int offset = from; offset < to; offset += BLOCK_LENGTH) {
            int count = Math.min(BLOCK_LENGTH, to - offset);
            Arrays.fill(block, (byte) 0);
            System.arraycopy(data, offset, block, 0, count);
            cipher.processBlock(block, 0, out, 0);
            System.arraycopy(out, 0, data, offset, count);
        }
    }

    /**
     * Returns the length of the RTP header including CSRC identifiers and any header extension, or -1 if the packet is
     * too short or has the wrong version.
     */
    private static int headerLength(byte[] packet) {
        if (packet.length < RTP_HEADER_LENGTH || (packet[0] & 0xC0) != 0x80) {
            return -1;
        }
        int length = RTP_HEADER_LENGTH + 4 * (packet[0] & 0x0F);
        if ((packet[0] & 0x10) != 0) {
            if (packet.length < length + 4) {
                return -1;
            }
            int extensionWords = ((packet[length + 2] & 0xFF) << 8) | (packet[length + 3] & 0xFF);
            length += 4 + 4 * extensionWords;
        }
        return length <= packet.length ? length : -1;
    }

    private static int sequenceNumber(byte[] packet) {
        return ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
    }

    private static int ssrc(byte[] packet) {
        return ((packet[8] & 0xFF) << 24) | ((packet[9] & 0xFF) << 16) | ((packet[10] & 0xFF) << 8)
                | (packet[11] & 0xFF);
    }
}
