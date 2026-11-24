// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;

import com.hedera.node.app.hapi.utils.ClprChannelId;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.SpecOperation;
import java.security.SecureRandom;
import java.util.function.Function;
import org.hiero.base.crypto.Ed25519;

/** Derives a channel ID and signs the connector commitment for tests that use that channel. */
final class ClprChannelCrypto {
    /**
     * CLPR Service precompile address (0x000000000000000000000000000000000000016e).
     * Spec §6.3 (Hiero note): connector signature is over
     * {@code keccak256(connector_id || service_address)}.
     */
    private static final byte[] CLPR_SERVICE_ADDRESS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0x6e
    };

    private final byte[] channelId;
    private final byte[] salt = new byte[32];
    private final byte[] publicKey;
    private final byte[] connectorSalt;
    private final byte[] connectorId;
    private final byte[] connectorCommitment;
    private final byte[] connectorSignature;

    ClprChannelCrypto() {
        this("eip155:298", CLPR_SERVICE_ADDRESS, "hiero:testing", CLPR_SERVICE_ADDRESS);
    }

    /** Build dependent operations after the ledger has assigned the deployed verifier its address. */
    static SpecOperation withVerifier(
            final String verifier,
            final String peerChain,
            final byte[] peerService,
            final Function<ClprChannelCrypto, SpecOperation> scenario) {
        return blockingOrder(
                uploadInitCode(verifier),
                contractCreate(verifier),
                sourcingContextual(spec -> scenario.apply(new ClprChannelCrypto(
                        "eip155:" + spec.startupProperties().getLong("contracts.chainId"),
                        asSolidityAddress(spec.registry().getContractId(verifier)),
                        peerChain,
                        peerService))));
    }

    ClprChannelCrypto(
            final String localChain, final byte[] verifier, final String peerChain, final byte[] peerService) {
        final var rng = new SecureRandom();
        rng.nextBytes(salt);
        channelId = ClprChannelId.derive(
                        localChain,
                        Bytes.wrap(CLPR_SERVICE_ADDRESS),
                        Bytes.wrap(verifier),
                        peerChain,
                        Bytes.wrap(peerService),
                        Bytes.wrap(peerVerifier()),
                        Bytes.wrap(salt))
                .toByteArray();

        // Generate Ed25519 key pair
        final var seed = new byte[Ed25519.SEED_BYTES];
        rng.nextBytes(seed);
        final byte[] secretKey = new byte[Ed25519.SECRET_KEY_BYTES];
        publicKey = new byte[Ed25519.PUBLIC_KEY_BYTES];
        Ed25519.newKeypair(publicKey, secretKey, seed);

        // connectorId = keccak256(channelId || pubKey || salt)  — spec §6.3 deriveConnectorId
        connectorSalt = new byte[32];
        rng.nextBytes(connectorSalt);
        connectorId = MiscCryptoUtils.keccak256DigestOf(concat(channelId, concat(publicKey, connectorSalt)));

        // connectorCommitment = keccak256(connectorId || pubKey)  — spec §6.3 Phase 1
        connectorCommitment = MiscCryptoUtils.keccak256DigestOf(concat(connectorId, publicKey));

        // connectorSignature = Ed25519.sign(keccak256(connectorId || CLPR_SERVICE_ADDRESS))
        //   — spec §6.3 Phase 2 step 3
        connectorSignature = new byte[Ed25519.SIGNATURE_BYTES];
        final var connectorSigMsg = MiscCryptoUtils.keccak256DigestOf(concat(connectorId, CLPR_SERVICE_ADDRESS));
        Ed25519.sign(connectorSignature, connectorSigMsg, secretKey);
    }

    byte[] salt() {
        return salt;
    }

    byte[] peerVerifier() {
        return CLPR_SERVICE_ADDRESS;
    }

    byte[] channelId() {
        return channelId;
    }

    byte[] publicKey() {
        return publicKey;
    }

    byte[] connectorSalt() {
        return connectorSalt;
    }

    byte[] connectorId() {
        return connectorId;
    }

    byte[] connectorCommitment() {
        return connectorCommitment;
    }

    byte[] connectorSignature() {
        return connectorSignature;
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final var r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
