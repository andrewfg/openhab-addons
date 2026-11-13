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
package org.openhab.binding.homekit.internal.camera.go2rtc;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Time;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates a self-signed HTTPS certificate and private key as PEM files, which go2rtc requires for its TLS listener.
 * The certificate carries the go2rtc host IP address as a Subject Alternative Name, so browsers that connect by IP
 * address can match it, and it is regenerated when the host address changes or it nears expiry.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class Go2RtcTls {

    /**
     * The PEM files for go2rtc: the certificate chain and the private key.
     */
    public record TlsFiles(Path cert, Path key) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Go2RtcTls.class);

    private static final String CERT_FILE_NAME = "go2rtc-cert.pem";
    private static final String KEY_FILE_NAME = "go2rtc-key.pem";
    private static final long VALIDITY_MILLIS = 397L * 24 * 60 * 60 * 1000;
    private static final long RENEWAL_MARGIN_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final int GENERAL_NAME_IP_ADDRESS = 7;
    private static final ASN1ObjectIdentifier ECDSA_WITH_SHA256 = new ASN1ObjectIdentifier("1.2.840.10045.4.3.2");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Go2RtcTls() {
    }

    /**
     * Returns the go2rtc certificate and private key for the given host address, reusing the PEM files in the directory
     * if they still match the address and are not near expiry, otherwise generating new ones.
     *
     * @param dir the directory to write the PEM files to
     * @param host the IP address go2rtc listens on, which the certificate must match
     * @return the PEM files, or null if they cannot be generated
     */
    public static @Nullable TlsFiles generateCertificate(Path dir, String host) {
        Path certFile = dir.resolve(CERT_FILE_NAME);
        Path keyFile = dir.resolve(KEY_FILE_NAME);
        if (Files.isRegularFile(certFile) && Files.isRegularFile(keyFile) && isUsable(certFile, host)) {
            return new TlsFiles(certFile, keyFile);
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            Date notBefore = new Date();
            Date notAfter = new Date(notBefore.getTime() + VALIDITY_MILLIS);
            byte[] certDer = buildCertificate(keyPair, host, notBefore, notAfter);

            Files.createDirectories(dir);
            Files.writeString(certFile, pem("CERTIFICATE", certDer), StandardCharsets.US_ASCII);
            writeOwnerOnly(keyFile, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
            return new TlsFiles(certFile, keyFile);
        } catch (IOException | GeneralSecurityException e) {
            LOGGER.warn("Cannot generate the HTTPS certificate for go2rtc: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isUsable(Path certFile, String host) {
        try (InputStream stream = Files.newInputStream(certFile)) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(stream);
            return cert.getNotAfter().after(new Date(System.currentTimeMillis() + RENEWAL_MARGIN_MILLIS))
                    && hasIpAddress(cert, host);
        } catch (IOException | GeneralSecurityException | ClassCastException e) {
            return false;
        }
    }

    private static boolean hasIpAddress(X509Certificate cert, String host) throws GeneralSecurityException {
        Collection<List<?>> names = cert.getSubjectAlternativeNames();
        if (names == null) {
            return false;
        }
        for (List<?> name : names) {
            if (name.get(0) instanceof Integer type && type == GENERAL_NAME_IP_ADDRESS && host.equals(name.get(1))) {
                return true;
            }
        }
        return false;
    }

    private static byte[] buildCertificate(KeyPair keyPair, String host, Date notBefore, Date notAfter)
            throws IOException, GeneralSecurityException {
        X500Name subject = new X500Name("CN=" + host);
        GeneralNames sans = new GeneralNames(
                new GeneralName(GeneralName.iPAddress, new DEROctetString(InetAddress.getByName(host).getAddress())));
        Extension[] extensions = {
                new Extension(Extension.basicConstraints, true,
                        new DEROctetString(new BasicConstraints(true).getEncoded(ASN1Encoding.DER))),
                new Extension(Extension.keyUsage, true,
                        new DEROctetString(new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign)
                                .getEncoded(ASN1Encoding.DER))),
                new Extension(Extension.extendedKeyUsage, false,
                        new DEROctetString(
                                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth).getEncoded(ASN1Encoding.DER))),
                new Extension(Extension.subjectAlternativeName, false,
                        new DEROctetString(sans.getEncoded(ASN1Encoding.DER))) };

        ASN1EncodableVector tbs = new ASN1EncodableVector();
        tbs.add(new DERTaggedObject(true, 0, new ASN1Integer(2)));
        tbs.add(new ASN1Integer(new BigInteger(63, RANDOM).add(BigInteger.ONE)));
        tbs.add(new AlgorithmIdentifier(ECDSA_WITH_SHA256));
        tbs.add(subject);
        tbs.add(new DERSequence(new ASN1Encodable[] { new Time(notBefore), new Time(notAfter) }));
        tbs.add(subject);
        tbs.add(SubjectPublicKeyInfo.getInstance(ASN1Primitive.fromByteArray(keyPair.getPublic().getEncoded())));
        tbs.add(new DERTaggedObject(true, 3, new Extensions(extensions)));
        byte[] tbsDer = new DERSequence(tbs).getEncoded(ASN1Encoding.DER);

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbsDer);

        ASN1EncodableVector certificate = new ASN1EncodableVector();
        certificate.add(ASN1Primitive.fromByteArray(tbsDer));
        certificate.add(new AlgorithmIdentifier(ECDSA_WITH_SHA256));
        certificate.add(new DERBitString(signer.sign()));
        return new DERSequence(certificate).getEncoded(ASN1Encoding.DER);
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    /**
     * Writes the file so that only the owner can read it, where the file system supports POSIX permissions.
     */
    private static void writeOwnerOnly(Path file, String content) throws IOException {
        Files.deleteIfExists(file);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(file, content, StandardCharsets.US_ASCII);
    }
}
