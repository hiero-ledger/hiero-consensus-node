// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import static org.hiero.base.utility.NonCryptographicHashing.hash32;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Objects;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.SerializablePublicKey;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.node.NodeId;

/**
 * One address in an address book, including all the info about a member. It is immutable. Each getter for a byte array
 * returns a clone of that array. The constructor clones all the arrays passed to it. Each "copySet" simply returns a
 * new deep copy with one variable different. So it isn't a setter, but is a variant of the builder design pattern
 * without a separate build() method.
 */
public class Address implements SelfSerializable {
    private static final long CLASS_ID = 0x5acfd3a4a32376eL;

    /** The Class Versions for this class */
    private static class ClassVersion {
        /**
         * The original version of the class.
         */
        public static final int ORIGINAL = 3;
        /**
         * The NodeId is SelfSerializable.
         *
         * @since 0.39.0
         */
        public static final int SELF_SERIALIZABLE_NODE_ID = 4;
        /**
         * added support for dns, removed unused IPv6
         */
        public static final int ADD_DNS_SUPPORT = 5;
        /**
         * added support for {@link X509Certificate}
         *
         * @since 0.48.0
         */
        public static final int X509_CERT_SUPPORT = 6;
    }

    private static final int STRING_MAX_BYTES = 512;

    /** The serialization version of this class, defaulting to most recent version.  Deserialization will override. */
    private int serialization = ClassVersion.X509_CERT_SUPPORT;

    /** ID of this member. All agree on numbering for old members, and if config.txt used */
    private NodeId id;
    /** name this member uses to refer to that member */
    private String nickname;
    /** name that member uses to refer to their self */
    private String selfName;
    /** the member's nonnegative weight, used for weighted voting */
    private long weight;
    /** the IP or DNS name on the local network */
    private String hostnameInternal;
    /** port used on the local network */
    private int portInternal;
    /** the IP or DNS name outside the NATing firewall */
    private String hostnameExternal;
    /** port used outside the NATing firewall */
    private int portExternal;
    /** public key of the member used for signing */
    // deprecated for removal in version 0.49.0 or later
    private SerializablePublicKey sigPublicKey = null;
    /** public key of the member used for encrypting */
    // deprecated for removal in version 0.49.0 or later
    private SerializablePublicKey encPublicKey;
    /** public key of the member used for TLS key agreement */
    // deprecated for removal in version 0.49.0 or later
    private SerializablePublicKey agreePublicKey = null;
    /** signing x509 certificate of the member, contains the public key used for signing */
    private SerializableX509Certificate sigCert = null;
    /** agreement x509 certificate of the member, used for establishing TLS connections. */
    // now deprecated for removal in version 0.51.0 or later
    private SerializableX509Certificate agreeCert = null;
    /**
     * a String that can be part of any address to supply additional information about that node
     */
    private String memo;

    /**
     * Default constructor for Address.
     */
    public Address() {
        this(NodeId.FIRST_NODE_ID, "", "", 1, null, -1, null, -1, null, null, "");
    }

    /**
     * constructor for a mutable address for one member.
     *
     * @param id               the ID for that member
     * @param nickname         the name given to that member by the member creating this address
     * @param selfName         the name given to that member by themself
     * @param weight           the amount of weight (0 if they should have no influence on the consensus)
     * @param hostnameInternal the IP or DNS name on the local network
     * @param portInternal     port for the internal address
     * @param hostnameExternal the IP or DNS name outside the NATing firewall
     * @param portExternal     port for the external address
     * @param sigCert          certificate used for signing
     * @param agreeCert        certificate used for agreement in TLS
     * @param memo             additional information about the node, can be null
     */
    public Address(
            @NonNull final NodeId id,
            @NonNull final String nickname,
            @NonNull final String selfName,
            final long weight,
            @Nullable final String hostnameInternal,
            final int portInternal,
            @Nullable final String hostnameExternal,
            final int portExternal,
            @Nullable final SerializableX509Certificate sigCert,
            @Nullable final SerializableX509Certificate agreeCert,
            @NonNull final String memo) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.nickname = Objects.requireNonNull(nickname, "nickname must not be null");
        this.selfName = Objects.requireNonNull(selfName, "selfName must not be null");
        this.weight = weight;
        this.portInternal = portInternal;
        this.portExternal = portExternal;
        this.hostnameInternal = hostnameInternal;
        this.hostnameExternal = hostnameExternal;
        this.sigCert = sigCert == null ? null : checkCertificateEncoding(sigCert);
        this.agreeCert = agreeCert == null ? null : checkCertificateEncoding(agreeCert);
        this.memo = Objects.requireNonNull(memo, "memo must not be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.ADD_DNS_SUPPORT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return serialization;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * The nonnegative weight for this member, which is the voting weight for consensus.
     *
     * @return the weight
     */
    public long getWeight() {
        return weight;
    }

    /**
     * Convenience method to check if this node has zero weight.
     *
     * @return true if this node has zero weight
     */
    public boolean isZeroWeight() {
        return weight == 0;
    }

    /**
     * Get the NodeId of this address.
     *
     * @return the NodeId of this address.
     */
    @NonNull
    public NodeId getNodeId() {
        return id;
    }

    /**
     * Get name this member uses to refer to that member.
     *
     * @return The nickname of this member.
     */
    @NonNull
    public String getNickname() {
        return nickname;
    }

    /**
     * Get the name this member uses to refer to itself
     *
     * @return Name to refer itself.
     */
    @NonNull
    public String getSelfName() {
        return selfName;
    }

    /**
     * Get listening port used on the local network.
     *
     * @return The port number.
     */
    public int getListenPort() {
        return getPortInternal();
    }

    /**
     * Get port used on the local network.
     *
     * @return The port number.
     */
    public int getPortInternal() {
        return portInternal;
    }

    /**
     * Get port used on the external network.
     *
     * @return The port number.
     */
    public int getPortExternal() {
        return portExternal;
    }

    /**
     * @return the IP or DNS name on the local network
     */
    @Nullable
    public String getHostnameInternal() {
        return hostnameInternal;
    }

    /**
     * @return the IP or DNS name outside the NATing firewall
     */
    @Nullable
    public String getHostnameExternal() {
        return hostnameExternal;
    }

    /**
     * Get public key of the member used for signing.
     *
     * @return This member's PublicKey for signing.
     */
    @Nullable
    public PublicKey getSigPublicKey() {
        if (sigCert != null) {
            return sigCert.getPublicKey();
        }
        return sigPublicKey == null ? null : sigPublicKey.getPublicKey();
    }

    /**
     * Get the public key of the member used for TLS key agreement.
     *
     * @return The member's PublicKey used for TLS key agreement.
     */
    @Deprecated(since = "0.51.0", forRemoval = true)
    @Nullable
    public PublicKey getAgreePublicKey() {
        if (agreeCert != null) {
            return agreeCert.getPublicKey();
        }
        return agreePublicKey == null ? null : agreePublicKey.getPublicKey();
    }

    /**
     * Get the {@link X509Certificate} of the member used for signing.
     *
     * @return The member's x509 certificate used for signing, if it exists.
     */
    @Nullable
    public X509Certificate getSigCert() {
        return sigCert == null ? null : sigCert.getCertificate();
    }

    /**
     * Get the {@link X509Certificate} of the member used for TLS key agreement.
     *
     * @return The member's x509 certificate used for TLS key agreement, if it exists.
     */
    @Deprecated(since = "0.51.0", forRemoval = true)
    @Nullable
    public X509Certificate getAgreeCert() {
        return agreeCert == null ? null : agreeCert.getCertificate();
    }

    /**
     * Get the String that is part of any address to supply additional information about this node.
     *
     * @return The String to supply additional information about this node.
     */
    @NonNull
    public String getMemo() {
        return memo;
    }

    /**
     * Create a new Address object based this one with different NodeId.
     *
     * @param id new NodeId for the created Address.
     * @return the new Address.
     */
    @NonNull
    public Address copySetNodeId(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "id must not be null");
        final Address a = copy();
        a.id = id;
        return a;
    }

    /**
     * Create a new Address object based this one with different weight.
     *
     * @param weight New weight for the created Address.
     * @return The new Address.
     */
    @NonNull
    public Address copySetWeight(long weight) {
        Address a = copy();
        a.weight = weight;
        return a;
    }

    /**
     * Create a new Address object based this one with different nickname.
     *
     * @param nickname New nickname for the created Address.
     * @return The new Address.
     */
    @NonNull
    public Address copySetNickname(@NonNull final String nickname) {
        Objects.requireNonNull(nickname, "nickname must not be null");
        Address a = copy();
        a.nickname = nickname;
        return a;
    }

    /**
     * Create a new Address object based this one with different selfName.
     *
     * @param selfName New selfName for the created Address.
     * @return The new Address.
     */
    @NonNull
    public Address copySetSelfName(@NonNull final String selfName) {
        Objects.requireNonNull(selfName, "selfName must not be null");
        Address a = copy();
        a.selfName = selfName;
        return a;
    }

    /**
     * Create a new Address object based this one with different internal port.
     *
     * @param portInternal New portInternal for the created Address.
     * @return The new Address.
     */
    @NonNull
    public Address copySetPortInternal(int portInternal) {
        Address a = copy();
        a.portInternal = portInternal;
        return a;
    }

    /**
     * Create a new Address object based this one with different external port.
     *
     * @param portExternal New portExternal for the created Address.
     * @return The new Address.
     */
    @NonNull
    public Address copySetPortExternal(int portExternal) {
        Address a = copy();
        a.portExternal = portExternal;
        return a;
    }

    /**
     * Create a new Address object based this one with different internal hostname.
     *
     * @param hostnameInternal New hostnameInternal for the created Address.
     * @return The new Address.
     */
    @NonNull
    public Address copySetHostnameInternal(@NonNull final String hostnameInternal) {
        Objects.requireNonNull(hostnameInternal, "hostnameInternal must not be null");
        Address a = copy();
        a.hostnameInternal = hostnameInternal;
        return a;
    }

    /**
     * Create a new Address object based this one with different externam hostname.
     *
     * @param hostnameExternal New hostnameExternal for the created Address.
     * @return The new Address.
     */
    @NonNull
    public Address copySetHostnameExternal(@NonNull final String hostnameExternal) {
        Objects.requireNonNull(hostnameExternal, "hostnameExternal must not be null");
        Address a = copy();
        a.hostnameExternal = hostnameExternal;
        return a;
    }

    /**
     * Create a new Address object based this one with different {@link X509Certificate} for signature.  If the
     * certificate is not encodable, the field is set to null.
     *
     * @param sigCert New signing certificate for the created Address.
     * @return The new Address.
     */
    @NonNull
    public Address copySetSigCert(@Nullable final X509Certificate sigCert) {
        final Address a = copy();
        a.sigCert = sigCert == null ? null : checkCertificateEncoding(new SerializableX509Certificate(sigCert));
        return a;
    }

    /**
     * Create a new Address object based this one with different {@link X509Certificate} for TLS key agreement.
     *
     * @param agreeCert new agreement certificate for the created Address.
     * @return The new Address.
     */
    @Deprecated(since = "0.51.0", forRemoval = true)
    @NonNull
    public Address copySetAgreeCert(@Nullable final X509Certificate agreeCert) {
        Address a = copy();
        a.agreeCert = agreeCert == null ? null : checkCertificateEncoding(new SerializableX509Certificate(agreeCert));
        return a;
    }

    /**
     * Create a new Address object based this one with different memo.
     *
     * @param memo New memo for the created Address.
     * @return The new Address.
     */
    @NonNull
    public Address copySetMemo(@NonNull final String memo) {
        Objects.requireNonNull(memo, "memo must not be null");
        final Address a = copy();
        a.memo = memo;
        return a;
    }

    /**
     * Create a new Address object based on this one.
     *
     * @return A duplication of current Address object.
     */
    public Address copy() {
        return new Address(
                id,
                nickname,
                selfName,
                weight,
                hostnameInternal,
                portInternal,
                hostnameExternal,
                portExternal,
                sigCert,
                agreeCert,
                memo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream outStream) throws IOException {
        outStream.writeSerializable(id, false);
        outStream.writeNormalisedString(nickname);
        outStream.writeNormalisedString(selfName);
        outStream.writeLong(weight);
        outStream.writeNormalisedString(hostnameInternal);
        outStream.writeInt(portInternal);
        outStream.writeNormalisedString(hostnameExternal);
        outStream.writeInt(portExternal);
        if (serialization < ClassVersion.X509_CERT_SUPPORT) {
            outStream.writeSerializable(sigPublicKey, false);
            outStream.writeSerializable(encPublicKey, false);
            outStream.writeSerializable(agreePublicKey, false);
        } else {
            outStream.writeSerializable(sigCert, false);
            outStream.writeSerializable(agreeCert, false);
        }
        outStream.writeNormalisedString(memo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream inStream, int version) throws IOException {
        serialization = version;
        id = inStream.readSerializable(false, NodeId::new);
        nickname = inStream.readNormalisedString(STRING_MAX_BYTES);
        selfName = inStream.readNormalisedString(STRING_MAX_BYTES);
        weight = inStream.readLong();

        hostnameInternal = inStream.readNormalisedString(STRING_MAX_BYTES);
        portInternal = inStream.readInt();
        hostnameExternal = inStream.readNormalisedString(STRING_MAX_BYTES);
        portExternal = inStream.readInt();

        if (version < ClassVersion.X509_CERT_SUPPORT) {
            sigPublicKey = inStream.readSerializable(false, SerializablePublicKey::new);
            encPublicKey = inStream.readSerializable(false, SerializablePublicKey::new);
            agreePublicKey = inStream.readSerializable(false, SerializablePublicKey::new);
        } else {
            try {
                sigCert = checkCertificateEncoding(inStream.readSerializable(false, SerializableX509Certificate::new));
                agreeCert =
                        checkCertificateEncoding(inStream.readSerializable(false, SerializableX509Certificate::new));
            } catch (final IllegalArgumentException e) {
                throw new IOException("Deserialized certificate fails to generate binary encoding.", e);
            }
        }
        memo = inStream.readNormalisedString(STRING_MAX_BYTES);
    }

    /**
     * Return the String of dot format of the IPv4 address.
     *
     * @param ip IP address.
     * @return IP address String of dot format.
     */
    public static String ipString(byte[] ip) {
        return "" + (0xff & ip[0]) + "." + (0xff & ip[1]) + "." + (0xff & ip[2]) + "." + (0xff & ip[3]);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Address address = (Address) o;
        return equalsWithoutWeight(address) && weight == address.weight;
    }

    /**
     * Checks for equality with another addresses without checking the equality of weight.
     *
     * @param address The other address to check for equality with this address.
     * @return true if all values in the other address match this address without consideration of weight, false
     * otherwise.
     */
    public boolean equalsWithoutWeight(@NonNull final Address address) {
        return Objects.equals(id, address.id)
                && portInternal == address.portInternal
                && portExternal == address.portExternal
                && Objects.equals(nickname, address.nickname)
                && Objects.equals(selfName, address.selfName)
                && Objects.equals(hostnameInternal, address.hostnameInternal)
                && Objects.equals(hostnameExternal, address.hostnameExternal)
                && equalsPublicKey(sigPublicKey, address.sigPublicKey)
                && equalsPublicKey(agreePublicKey, address.agreePublicKey)
                && equalsCertificate(sigCert, address.sigCert)
                && equalsCertificate(agreeCert, address.agreeCert)
                && Objects.equals(memo, address.memo);
    }

    /**
     * checks for the equality of two public keys by comparing their binary encoding.
     *
     * @param publicKey1 the first public key to compare
     * @param publicKey2 the second public key to compare
     * @return true if the public keys are equal in binary encoding, false otherwise.
     */
    private boolean equalsPublicKey(
            @NonNull final SerializablePublicKey publicKey1, @NonNull final SerializablePublicKey publicKey2) {
        if (publicKey1 != null && publicKey2 != null) {
            return Arrays.equals(
                    publicKey1.getPublicKey().getEncoded(),
                    publicKey2.getPublicKey().getEncoded());
        }
        return publicKey1 == publicKey2;
    }

    /**
     * checks for the equality of two certificates by comparing their binary encoding.
     *
     * @param certificate1 the first certificate to compare
     * @param certificate2 the second certificate to compare
     * @return true if the certificates are equal in binary encoding, false otherwise.
     */
    private boolean equalsCertificate(
            @NonNull final SerializableX509Certificate certificate1,
            @NonNull final SerializableX509Certificate certificate2) {
        if (certificate1 != null && certificate2 != null) {
            try {
                return Arrays.equals(
                        certificate1.getCertificate().getEncoded(),
                        certificate2.getCertificate().getEncoded());
            } catch (CertificateEncodingException e) {
                // this should never happen due to checking the encoding when the field is set.
                return false;
            }
        }
        return certificate1 == certificate2;
    }

    /**
     * Checks if the certificate is encodable and returns it if it is, otherwise returns null.
     *
     * @param certificate the certificate to check.
     * @return the certificate if it is encodable.
     */
    @Nullable
    private SerializableX509Certificate checkCertificateEncoding(
            @Nullable final SerializableX509Certificate certificate) {
        if (certificate == null) {
            return null;
        }
        return CryptoUtils.checkCertificate(certificate.getCertificate()) ? certificate : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return hash32(id.id());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("id", id)
                .append("nickname", nickname)
                .append("selfName", selfName)
                .append("weight", weight)
                .append("hostnameInternal", hostnameInternal)
                .append("portInternalIpv4", portInternal)
                .append("hostnameExternal", hostnameExternal)
                .append("portExternalIpv4", portExternal)
                .append("sigPublicKey", sigPublicKey)
                .append("agreePublicKey", agreePublicKey)
                .append("sigCert", sigCert)
                .append("agreeCert", agreeCert)
                .append("memo", memo)
                .toString();
    }
}
