// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.hedera.cryptography.security.der.model.AVA;
import com.hedera.cryptography.security.der.model.BasicConstraints;
import com.hedera.cryptography.security.der.model.DN;
import com.hedera.cryptography.security.der.model.DerString;
import com.hedera.cryptography.security.der.model.Extensions;
import com.hedera.cryptography.security.der.model.Interval;
import com.hedera.cryptography.security.der.model.OID;
import com.hedera.cryptography.security.der.model.RDN;
import com.hedera.cryptography.security.der.model.SignatureAlgorithm;
import com.hedera.cryptography.security.der.model.X509CertificateInfo;
import com.hedera.cryptography.security.x509.X509CertificateSigner;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for generating X.509 certificates and distinguished names.
 */
public final class CertificateUtils {
    private static final int SERIAL_NUMBER_BITS = 64;
    private static final OID OID_CN = new OID("2.5.4.3");

    /// That's all we support for now.
    private static final Pattern DN_PATTERN =
            Pattern.compile("^CN=([\\w\\h\\.\\\\!\"#$%&'\\(\\)\\*\\+\\,\\-\\/:;<>?@\\[\\]\\^_`\\{\\|\\}\\~]*)$");

    private CertificateUtils() {}

    /**
     * Return the distinguished name for an entity for use in an x509 certificate.
     *
     * @param commonName name such as "John Smith" or "Acme Inc"
     * @return the distinguished name, suitable for passing to {@link #generateCertificate}
     */
    public static String distinguishedName(final String commonName) {
        return "CN=" + escapeValue(commonName);
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
        return generateCertificate(
                distinguishedName, pair, caDistinguishedName, caPair, secureRandom, signatureAlgorithm, false);
    }

    public static X509Certificate generateCertificate(
            final String distinguishedName,
            final KeyPair pair,
            final String caDistinguishedName,
            final KeyPair caPair,
            final SecureRandom secureRandom,
            final String signatureAlgorithm,
            final boolean withBasicConstraintsAsCA)
            throws KeyGeneratingException {
        try {
            final X509CertificateInfo info = new X509CertificateInfo(
                    buildDN(distinguishedName),
                    pair.getPublic(),
                    buildDN(caDistinguishedName),
                    new BigInteger(SERIAL_NUMBER_BITS, secureRandom),
                    new Interval(CryptoConstants.DEFAULT_VALID_FROM, CryptoConstants.DEFAULT_VALID_TO),
                    new SignatureAlgorithm(signatureAlgorithm),
                    withBasicConstraintsAsCA
                            ? new Extensions(List.of(new BasicConstraints(true, null).newExtension(true)))
                            : null);

            return X509CertificateSigner.sign(info, caPair.getPrivate());
        } catch (final Exception e) {
            throw new KeyGeneratingException("Could not generate certificate!", e);
        }
    }

    /// All attribute values must be escaped already. Use CertificateUtils.escapeValue() for that.
    static DN buildDN(final String distinguishedName) {
        final Matcher matcher = DN_PATTERN.matcher(distinguishedName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("distinguishedName must match `" + DN_PATTERN.pattern()
                    + "`, instead got `" + distinguishedName + "`");
        }
        return new DN(List.of(new RDN(List.of(new AVA(OID_CN, new DerString(matcher.group(1)))))));
    }

    /// Escape a value string in a distinguished name.
    public static String escapeValue(String attributeValue) {
        attributeValue = attributeValue.replace("\\", "\\\\");
        attributeValue = attributeValue.replace("\"", "\\\"");
        attributeValue = attributeValue.replace(",", "\\,");
        attributeValue = attributeValue.replace(";", "\\;");
        attributeValue = attributeValue.replace("<", "\\<");
        attributeValue = attributeValue.replace(">", "\\>");
        attributeValue = attributeValue.replaceAll(" $", "\\ ");
        attributeValue = attributeValue.replaceAll("^ ", "\\ ");
        attributeValue = attributeValue.replaceAll("^#", "\\#");
        return attributeValue;
    }
}
