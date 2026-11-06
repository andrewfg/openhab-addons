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
package org.openhab.binding.homekit.internal.audio.tlv;

import static org.openhab.binding.homekit.internal.audio.tlv.TlvHelpers.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.homekit.internal.crypto.Tlv8Codec;
import org.openhab.binding.homekit.internal.enums.SrtpCryptoSuite;

/**
 * SRTP parameters for one camera stream (video or audio), as carried in SetupEndpoints.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public record SrtpParameters(SrtpCryptoSuite cryptoSuite, byte[] masterKey, byte[] masterSalt) {

    private static final int TYPE_CRYPTO_SUITE = 0x01;
    private static final int TYPE_MASTER_KEY = 0x02;
    private static final int TYPE_MASTER_SALT = 0x03;

    public SrtpParameters {
        if (cryptoSuite == SrtpCryptoSuite.NONE) {
            if (masterKey.length != 0 || masterSalt.length != 0) {
                throw new IllegalArgumentException("Key and salt must be empty when crypto suite is NONE");
            }
        } else {
            requireLength(masterKey, cryptoSuite.keyLength);
            requireLength(masterSalt, cryptoSuite.saltLength);
        }
    }

    public byte[] encode() {
        Map<Integer, byte[]> tlv = new LinkedHashMap<>();
        tlv.put(TYPE_CRYPTO_SUITE, u8(cryptoSuite.value));
        tlv.put(TYPE_MASTER_KEY, masterKey);
        tlv.put(TYPE_MASTER_SALT, masterSalt);
        return Tlv8Codec.encode(tlv);
    }

    public static SrtpParameters decode(byte[] data) {
        Map<Integer, byte[]> tlv = Tlv8Codec.decode(data);
        SrtpCryptoSuite suite = SrtpCryptoSuite.fromValue(readU8(required(tlv, TYPE_CRYPTO_SUITE)));
        return new SrtpParameters(suite, tlv.getOrDefault(TYPE_MASTER_KEY, new byte[0]),
                tlv.getOrDefault(TYPE_MASTER_SALT, new byte[0]));
    }
}
