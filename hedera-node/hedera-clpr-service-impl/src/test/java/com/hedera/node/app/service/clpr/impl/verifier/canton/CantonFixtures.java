// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Loads real Canton test vectors from {@code /canton/fixtures}. Raw bytes live in {@code <name>.hex} files
 * (a single hex string, no prefix).
 */
public final class CantonFixtures {
    private static final String BASE = "/canton/fixtures/";

    private CantonFixtures() {}

    /** Loads {@code <name>.hex} as bytes. */
    @NonNull
    public static byte[] load(@NonNull final String name) {
        return HexFormat.of().parseHex(text(name + ".hex").strip());
    }

    /** Loads an arbitrary text resource under the fixtures directory (e.g. a JSON file). */
    @NonNull
    public static String text(@NonNull final String fileName) {
        try (final var in = CantonFixtures.class.getResourceAsStream(BASE + fileName)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing Canton fixture: " + fileName);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Real verdict event (Canton protocol v35, update 1220aec33071...)
    public static byte[] verdictTypedContent() {
        return load("verdict-typed-content");
    }

    public static byte[] verdictMediatorSig() {
        return load("verdict-mediator-sig");
    }

    public static byte[] verdictMediatorKey() {
        return load("verdict-mediator-key");
    }

    public static byte[] verdictSequencedContent() {
        return load("verdict-sequenced-content");
    }

    public static byte[] verdictSequencerSig() {
        return load("verdict-sequencer-sig");
    }

    public static byte[] verdictSequencerKey() {
        return load("verdict-sequencer-key");
    }

    /**
     * The 34-byte multihash (0x1220 + root hash) of the real verdict update. This is the message that
     * the synthetic mediator signatures in the Solidity gas fixtures sign; the bytes themselves are real.
     */
    public static byte[] updateMultihash() {
        return load("update-multihash");
    }

    /** {@code sha256(updateMultihash())}, as used by the Solidity gas fixtures ({@code SYN_DIGEST}). */
    public static byte[] updateMultihashSha256() {
        return load("update-multihash-sha256");
    }

    // Topology (PR B scope, co-located)
    public static byte[] topologyOwnerSet() {
        return load("topology-owner-set");
    }

    public static byte[] topologyMediatorSet() {
        return load("topology-mediator-set");
    }

    public static byte[] topologyRotations() {
        return load("topology-rotations");
    }

    public static byte[] topologyTxLargest() {
        return load("topology-tx-largest");
    }

    public static byte[] lsuAnnouncement() {
        return load("lsu-announcement");
    }

    public static byte[] lsuOwnerSet() {
        return load("lsu-owner-set");
    }

    /** Real Splice LocalNet example proof bundle (JSON, as fetched from the Canton ledger API). */
    public static String localnetExampleProofBundleJson() {
        return text("localnet-example-proof-bundle.json");
    }

    /**
     * The real {@code createdEventBlob} of the WalletAppInstall contract from the LocalNet bundle, decoded
     * from its base64 in {@code localnet-example-proof-bundle.json}. It is a
     * {@code Versioned{1: version "2.1", 2: FatContractInstance{1: contract_id, 3: template_id (Identifier),
     * 4: create_arg (Daml-LF Value/Record), 5: signatories, ...}}} and exercises the contract and Daml-LF
     * accessors on real Canton bytes.
     */
    public static byte[] createdEventBlob() {
        return load("created-event-blob");
    }
}
