// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;

import com.hedera.node.app.hapi.utils.ClprChannelId;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.SpecOperation;
import java.security.SecureRandom;
import java.util.function.Function;
import org.hiero.base.crypto.Ed25519;

/** Derives a verifier-bound channel ID and a reusable connector identity for tests. */
final class ClprChannelCrypto {
    private final byte[] seed = new byte[Ed25519.SEED_BYTES];
    private final byte[] channelId;
    private final byte[] salt = new byte[32];
    private final byte[] connectorSalt;
    private final byte[] connectorId;

    ClprChannelCrypto() {
        this("eip155:298", CLPR_SERVICE_ADDRESS_20, "hiero:testing", CLPR_SERVICE_ADDRESS_20);
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
                        Bytes.wrap(CLPR_SERVICE_ADDRESS_20),
                        Bytes.wrap(verifier),
                        peerChain,
                        Bytes.wrap(peerService),
                        Bytes.wrap(peerVerifier()),
                        Bytes.wrap(salt))
                .toByteArray();

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
