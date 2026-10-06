// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import java.security.SecureRandom;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

/**
 * Generates Ed25519 key material for the register → complete flow, both for the
 * Channel (spec §5.1) and the Connector (spec §6.3). Tests that only exercise a
 * registered channel can ignore the connector accessors.
 */
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
    private final byte[] publicKey;
    private final byte[] commitment;
    private final byte[] signature;
    private final byte[] connectorSalt;
    private final byte[] connectorId;
    private final byte[] connectorCommitment;
    private final byte[] connectorSignature;

    ClprChannelCrypto() {
        final var rng = new SecureRandom();

        // Random 32-byte channel ID
        channelId = new byte[32];
        rng.nextBytes(channelId);

        // Generate Ed25519 key pair
        final var privateKey = new byte[32];
        rng.nextBytes(privateKey);
        publicKey = new byte[32];
        Ed25519.generatePublicKey(privateKey, 0, publicKey, 0);

        // commitment = keccak256(channelId || publicKey)  — spec §5.1.2
        commitment = keccak256(concat(channelId, publicKey));

        // signature = Ed25519.sign(keccak256(channelId))  — spec §5.1.3
        signature = new byte[Ed25519.SIGNATURE_SIZE];
        final var messageHash = keccak256(channelId);
        Ed25519.sign(privateKey, 0, messageHash, 0, messageHash.length, signature, 0);

        // connectorId = keccak256(channelId || pubKey || salt)  — spec §6.3 deriveConnectorId
        connectorSalt = new byte[32];
        rng.nextBytes(connectorSalt);
        connectorId = keccak256(concat(channelId, concat(publicKey, connectorSalt)));

        // connectorCommitment = keccak256(connectorId || pubKey)  — spec §6.3 Phase 1
        connectorCommitment = keccak256(concat(connectorId, publicKey));

        // connectorSignature = Ed25519.sign(keccak256(connectorId || CLPR_SERVICE_ADDRESS))
        //   — spec §6.3 Phase 2 step 3
        connectorSignature = new byte[Ed25519.SIGNATURE_SIZE];
        final var connectorSigMsg = keccak256(concat(connectorId, CLPR_SERVICE_ADDRESS));
        Ed25519.sign(privateKey, 0, connectorSigMsg, 0, connectorSigMsg.length, connectorSignature, 0);
    }

    byte[] channelId() {
        return channelId;
    }

    byte[] publicKey() {
        return publicKey;
    }

    byte[] commitment() {
        return commitment;
    }

    byte[] signature() {
        return signature;
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

    private static byte[] keccak256(final byte[] input) {
        return new Keccak.Digest256().digest(input);
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final var r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
