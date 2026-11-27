// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ClprChannelId;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.hiero.base.crypto.Ed25519;

/** Derives a verifier-bound channel ID and a reusable connector identity for tests. */
final class ClprChannelCrypto {
    private final byte[] seed = new byte[Ed25519.SEED_BYTES];
    private final byte[] channelId;
    private final byte[] salt = new byte[32];
    private final byte[] peerTrustAnchor;
    private final Timestamp localConfigTimestamp;
    private final byte[] localTrustAnchorHash;
    private final byte[] connectorSalt;
    private final byte[] connectorId;

    /** Identity for scenarios that fail before the channel ID is checked. */
    ClprChannelCrypto() {
        this(
                ClprLedgerConfiguration.newBuilder()
                        .setChainId("eip155:298")
                        .setTimestamp(ClprTestProofs.PEER_CONFIG_TIMESTAMP)
                        .build(),
                CLPR_SERVICE_ADDRESS_20,
                peerConfig("hiero:testing", CLPR_SERVICE_ADDRESS_20));
    }

    /** Build dependent operations after the ledger has assigned the deployed verifier its address. */
    static SpecOperation withVerifier(
            final String verifier,
            final String peerChain,
            final byte[] peerService,
            final Function<ClprChannelCrypto, SpecOperation> scenario) {
        return withVerifier(verifier, peerChain, peerService, null, scenario);
    }

    /**
     * Like {@link #withVerifier(String, String, byte[], Function)}, but first installs the given local ledger
     * configuration. The channel ID commits to the local configuration timestamp, so the configuration must be
     * installed before the ID is derived.
     */
    static SpecOperation withVerifier(
            final String verifier,
            final String peerChain,
            final byte[] peerService,
            @Nullable final ClprLedgerConfiguration localConfigToInstall,
            final Function<ClprChannelCrypto, SpecOperation> scenario) {
        final var localConfig = new AtomicReference<ClprLedgerConfiguration>();
        final List<SpecOperation> ops = new ArrayList<>();
        if (localConfigToInstall != null) {
            ops.add(clprUpdateLedgerConfiguration()
                    .configuration(localConfigToInstall)
                    .payingWith(GENESIS));
        }
        ops.add(uploadInitCode(verifier));
        ops.add(contractCreate(verifier));
        ops.add(clprGetLedgerConfiguration().payingWith(GENESIS).exposingConfigTo(localConfig::set));
        ops.add(sourcingContextual(spec -> scenario.apply(new ClprChannelCrypto(
                localConfig.get(),
                asSolidityAddress(spec.registry().getContractId(verifier)),
                peerConfig(peerChain, peerService)))));
        return blockingOrder(ops.toArray(SpecOperation[]::new));
    }

    /**
     * Derives the channel ID as the local ledger does: from the proven peer configuration and the local
     * configuration, with the verifier on each side for the other.
     *
     * @param localConfig the local ledger configuration
     * @param verifier the local verifier address for the peer
     * @param peerConfig the peer configuration in the config proof; unset identity fields take the
     *     {@link ClprTestProofs#withPeerIdentity} defaults
     */
    ClprChannelCrypto(
            final ClprLedgerConfiguration localConfig,
            final byte[] verifier,
            final ClprLedgerConfiguration peerConfig) {
        final var rng = new SecureRandom();
        rng.nextBytes(salt);
        final var peer = ClprTestProofs.withPeerIdentity(peerConfig);
        peerTrustAnchor = peer.getInitialTrustAnchor().toByteArray();
        localConfigTimestamp = localConfig.getTimestamp();
        localTrustAnchorHash = MiscCryptoUtils.keccak256DigestOf(
                localConfig.getInitialTrustAnchor().toByteArray());
        final var peerToLocal = ClprChannelId.leg(
                peer.getChainId(),
                Bytes.wrap(peer.getServiceAddress().toByteArray()),
                toPbj(peer.getTimestamp()),
                MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(peerTrustAnchor)),
                Bytes.wrap(verifier));
        final var localToPeer = ClprChannelId.leg(
                localConfig.getChainId(),
                Bytes.wrap(CLPR_SERVICE_ADDRESS_20),
                toPbj(localConfigTimestamp),
                Bytes.wrap(localTrustAnchorHash),
                Bytes.wrap(peerVerifier()));
        channelId =
                ClprChannelId.derive(peerToLocal, localToPeer, Bytes.wrap(salt)).toByteArray();

        // Generate Ed25519 key pair
        rng.nextBytes(seed);
        final byte[] secretKey = new byte[Ed25519.SECRET_KEY_BYTES];
        final byte[] publicKey = new byte[Ed25519.PUBLIC_KEY_BYTES];
        Ed25519.newKeypair(publicKey, secretKey, seed);

        // connectorId = keccak256(pubKey || salt)  — spec §6.3 deriveConnectorId
        connectorSalt = new byte[32];
        rng.nextBytes(connectorSalt);
        connectorId = MiscCryptoUtils.keccak256DigestOf(concat(publicKey, connectorSalt));
    }

    byte[] seed() {
        return seed;
    }

    byte[] salt() {
        return salt;
    }

    byte[] peerVerifier() {
        return CLPR_SERVICE_ADDRESS_20;
    }

    byte[] channelId() {
        return channelId;
    }

    /** The peer's initial trust anchor, supplied as {@code trust_anchor}. */
    byte[] peerTrustAnchor() {
        return peerTrustAnchor;
    }

    /** The local configuration timestamp the channel ID commits to. */
    Timestamp localConfigTimestamp() {
        return localConfigTimestamp;
    }

    /** keccak256 of the local initial trust anchor the channel ID commits to. */
    byte[] localTrustAnchorHash() {
        return localTrustAnchorHash;
    }

    private static ClprLedgerConfiguration peerConfig(final String chainId, final byte[] serviceAddress) {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId(chainId)
                .setServiceAddress(ByteString.copyFrom(serviceAddress))
                .build();
    }

    private static com.hedera.hapi.node.base.Timestamp toPbj(final Timestamp timestamp) {
        return com.hedera.hapi.node.base.Timestamp.newBuilder()
                .seconds(timestamp.getSeconds())
                .nanos(timestamp.getNanos())
                .build();
    }

    byte[] connectorSalt() {
        return connectorSalt;
    }

    byte[] connectorId() {
        return connectorId;
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final var r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
