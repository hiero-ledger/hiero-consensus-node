// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.cryptography.hints.HintsLibraryBridge;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.PeerLedgerConfigPayload.CommitteeKeys;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests the verifier's own BLS inputs against real Ethereum mainnet data: the sync committee of period 1895 and two of
 * its sync aggregates, one signed by all 512 members and one by 506. The signing roots are computed by {@link Ssz}, the
 * keys decompressed by {@link G1Compression} and the non-signers read by {@link CommitteeKeys#nonSignerPubkeys}, then
 * handed to {@link NativeBlsSignatureVerifier}: a signature verifies only if every one of them matches what the
 * committee signed and what the library expects.
 *
 * <p>The library's own checks (tampered or malformed signatures, invalid keys) are tested in hiero-cryptography's
 * {@code EthereumBlsBridgeTest}.
 */
class NativeBlsSignatureVerifierTest {

    private static final String MAINNET_SAMPLE = "/ethereum/mainnet-sync-committee-period-1895.properties";
    private static final String HEX_PREFIX = "0x";

    /** The number of members that did not sign the partial-participation aggregate. */
    private static final int PARTIAL_NON_SIGNER_COUNT = 6;

    private static final MainnetSample SAMPLE = MainnetSample.load();

    @Nested
    @DisplayName("signing root")
    class SigningRoot {

        @Test
        @DisplayName(
                "given the mainnet fork version and genesis validators root, then the signing root of each attested"
                        + " root is the one the committee signed")
        void givenMainnetDomain_thenSigningRootsAreTheSignedOnes() {
            assertThat(signingRoot(SAMPLE.full())).isEqualTo(SAMPLE.full().signedRoot32());
            assertThat(signingRoot(SAMPLE.partial())).isEqualTo(SAMPLE.partial().signedRoot32());
        }
    }

    @Nested
    @DisplayName("verifySyncAggregate")
    class VerifySyncAggregate {

        @Test
        @DisplayName("given a mainnet aggregate signed by the whole committee, then it returns true")
        void givenFullParticipation_thenReturnsTrue() {
            final List<byte[]> nonSignerPubkeys = nonSignerPubkeys(SAMPLE.full());

            assertThat(nonSignerPubkeys).isEmpty();
            assertThat(verify(SAMPLE.full(), nonSignerPubkeys)).isTrue();
        }

        @Test
        @DisplayName("given a mainnet aggregate and its six non-signers, then it returns true")
        void givenSixNonSigners_thenReturnsTrue() {
            final List<byte[]> nonSignerPubkeys = nonSignerPubkeys(SAMPLE.partial());

            assertThat(nonSignerPubkeys).hasSize(PARTIAL_NON_SIGNER_COUNT);
            assertThat(verify(SAMPLE.partial(), nonSignerPubkeys)).isTrue();
        }
    }

    /** The sync-committee signing root of {@code aggregate}'s attested root, in the mainnet signing domain. */
    private static byte[] signingRoot(final SignedRoot aggregate) {
        final byte[] domain32 = Ssz.computeSyncCommitteeDomain(SAMPLE.forkVersion4(), SAMPLE.genesisValidatorsRoot32());
        return Ssz.computeSigningRoot(aggregate.attestedRoot32(), domain32);
    }

    /** The keys of the members that did not sign {@code aggregate}, as the config path reads them. */
    private static List<byte[]> nonSignerPubkeys(final SignedRoot aggregate) {
        return SAMPLE.committee().nonSignerPubkeys(new SyncAggregate(aggregate.bits(), signature256(aggregate)));
    }

    /** {@code aggregate}'s signature in the EIP-2537 encoding, converted from the beacon chain's compressed one. */
    private static byte[] signature256(final SignedRoot aggregate) {
        return HintsLibraryBridge.getInstance().decompressG2ToEip2537(aggregate.signature96());
    }

    private static boolean verify(final SignedRoot aggregate, final List<byte[]> nonSignerPubkeys) {
        final byte[] aggregatePubkey =
                G1Compression.decompress(SAMPLE.committee().aggregatePubkey48(), "committee.aggregatePublicKey");
        return NativeBlsSignatureVerifier.INSTANCE.verifySyncAggregate(
                aggregatePubkey, nonSignerPubkeys, signingRoot(aggregate), signature256(aggregate));
    }

    /**
     * The mainnet sample: the signing domain, the committee, and its two sync aggregates.
     *
     * @param forkVersion4            the fork version of the signing domain
     * @param genesisValidatorsRoot32 the genesis validators root of the signing domain
     * @param committee               the committee's compressed keys and aggregate
     * @param full                    the aggregate signed by every member
     * @param partial                 the aggregate signed by all but six members
     */
    private record MainnetSample(
            byte[] forkVersion4,
            byte[] genesisValidatorsRoot32,
            CommitteeKeys committee,
            SignedRoot full,
            SignedRoot partial) {

        static MainnetSample load() {
            final Properties properties = new Properties();
            try (InputStream sample = NativeBlsSignatureVerifierTest.class.getResourceAsStream(MAINNET_SAMPLE)) {
                properties.load(Objects.requireNonNull(sample, MAINNET_SAMPLE));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            final byte[][] pubkeys48 = new byte[SyncCommitteePreset.MAINNET.committeeSize()][];
            for (int index = 0; index < pubkeys48.length; index++) {
                pubkeys48[index] = hexValue(properties, "committee.publicKey." + index);
            }
            return new MainnetSample(
                    hexValue(properties, "domain.forkVersion"),
                    hexValue(properties, "domain.genesisValidatorsRoot"),
                    new CommitteeKeys(pubkeys48, hexValue(properties, "committee.aggregatePublicKey")),
                    SignedRoot.load(properties, "full"),
                    SignedRoot.load(properties, "partial"));
        }
    }

    /**
     * A sync aggregate over an attested root.
     *
     * @param attestedRoot32 the root of the attested header
     * @param signedRoot32   the signing root the committee signed, as the sample states it
     * @param bits           the participation bits
     * @param signature96    the aggregate signature, compressed as the beacon chain encodes it
     */
    private record SignedRoot(byte[] attestedRoot32, byte[] signedRoot32, byte[] bits, byte[] signature96) {

        static SignedRoot load(final Properties properties, final String name) {
            return new SignedRoot(
                    hexValue(properties, name + ".attestedRoot"),
                    hexValue(properties, name + ".signingRoot"),
                    hexValue(properties, name + ".syncCommitteeBits"),
                    hexValue(properties, name + ".signature"));
        }
    }

    private static byte[] hexValue(final Properties properties, final String key) {
        final String value = Objects.requireNonNull(properties.getProperty(key), key);
        return HexFormat.of().parseHex(value.substring(HEX_PREFIX.length()));
    }
}
