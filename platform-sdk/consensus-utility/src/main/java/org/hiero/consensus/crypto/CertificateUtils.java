// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Utility methods for generating X.509 certificates and distinguished names.
 */
public final class CertificateUtils {
    private static final int SERIAL_NUMBER_BITS = 64;

    private CertificateUtils() {}

    /**
     * Return the distinguished name for an entity for use in an x509 certificate.
     *
     * @param commonName name such as "John Smith" or "Acme Inc"
     * @return the distinguished name, suitable for passing to {@link #generateCertificate}
     */
    public static String distinguishedName(final String commonName) {
        final String[] commaSeparator = new String[] {""};
        return rdn(commaSeparator, "CN", commonName)
                + rdn(commaSeparator, "O", null)
                + rdn(commaSeparator, "STREET", null)
                + rdn(commaSeparator, "L", null)
                + rdn(commaSeparator, "ST", null)
                + rdn(commaSeparator, "C", null)
                + rdn(commaSeparator, "UID", null);
    }

    /**
     * Create a signed X.509 Certificate.
     *
     * @param distinguishedName   the X.509 Distinguished Name
     * @param pair                the KeyPair whose public key is to be listed as belonging to distinguishedName
     * @param caDistinguishedName the name of the CA
     * @param caPair              the KeyPair of the CA
     * @param secureRandom        the random number generator used to generate the certificate
     * @param signatureAlgorithm  the algorithm used to sign the certificates with the signing key
     * @return the signed certificate
     * @throws KeyGeneratingException if any issue occurs
     */
    public static X509Certificate generateCertificate(
            final String distinguishedName,
            final KeyPair pair,
            final String caDistinguishedName,
            final KeyPair caPair,
            final SecureRandom secureRandom,
            final String signatureAlgorithm)
            throws KeyGeneratingException {
        try {
            final X509v3CertificateBuilder v3CertBldr = new JcaX509v3CertificateBuilder(
                    new X500Principal(caDistinguishedName),
                    new BigInteger(SERIAL_NUMBER_BITS, secureRandom),
                    Date.from(CryptoConstants.DEFAULT_VALID_FROM),
                    Date.from(CryptoConstants.DEFAULT_VALID_TO),
                    new X500Principal(distinguishedName),
                    pair.getPublic());

            final JcaContentSignerBuilder signerBuilder =
                    new JcaContentSignerBuilder(signatureAlgorithm).setProvider(BouncyCastleProvider.PROVIDER_NAME);
            return new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(v3CertBldr.build(signerBuilder.build(caPair.getPrivate())));
        } catch (final CertificateException | OperatorCreationException e) {
            throw new KeyGeneratingException("Could not generate certificate!", e);
        }
    }

    private static String rdn(final String[] commaSeparator, final String attributeType, String attributeValue) {
        if (attributeValue == null || attributeValue.isEmpty()) {
            return "";
        }
        attributeValue = attributeValue.replace("\\", "\\\\");
        attributeValue = attributeValue.replace("\"", "\\\"");
        attributeValue = attributeValue.replace(",", "\\,");
        attributeValue = attributeValue.replace(";", "\\;");
        attributeValue = attributeValue.replace("<", "\\<");
        attributeValue = attributeValue.replace(">", "\\>");
        attributeValue = attributeValue.replaceAll(" $", "\\ ");
        attributeValue = attributeValue.replaceAll("^ ", "\\ ");
        attributeValue = attributeValue.replaceAll("^#", "\\#");
        final String s = commaSeparator[0] + attributeType + "=" + attributeValue;
        commaSeparator[0] = ",";
        return s;
    }
}
