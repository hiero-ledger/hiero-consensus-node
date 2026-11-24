// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.utf8;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
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
    private static final HexFormat HEX = HexFormat.of();

    // Fixture constants from reference implementation, shared by the topology tests.
    public static final byte[] SYNCHRONIZER_NAMESPACE =
            HEX.parseHex("fe3624c1c2190638c3a9f88ec65abdb7b8a3908031d62dc2a0f65ed68c1e9922");
    public static final byte[] MEDIATOR1_KEY_1 =
            HEX.parseHex("47d7e478d17269b47ac84ed799101a84156ac084b2b186ee31c91cf7a1e67b40");
    public static final byte[] MEDIATOR1_KEY_2 =
            HEX.parseHex("4318031c692a30b683240a94c7fea3bc3a439e64d1cbe4e1f2e9599ba2938dcc");
    static final byte[] MEDIATOR1 = hex("6e8899fea6adfe7b3965493fb60343bd44215fe4c695d02e6c8a761bd7bfa9bc");
    public static final byte[] MEDIATOR1_P256 =
            HEX.parseHex("fe282f9c0c644fb7fd24f7029187d266a60edee684c0d2ad87cd34efcf4ba678");
    public static final byte[] MEDIATOR1_DELEGATED =
            HEX.parseHex("5191509ee4422119ae336fde0a17e775ea8540ee50c9a1f3904b04fbe42fa48b");
    public static final byte[] MEDIATOR2_KEY =
            HEX.parseHex("acc89538ec7fd9a5a0b31428b77ffdf69cea9c238b375531d8f5c18cbf45a16e");
    public static final byte[] OWNER_DELEGATED_P256 =
            HEX.parseHex("55f33859de079494a9ac84ad47db6a9e5bb911bee79131d29cd5b6faceff3d32");
    static final byte[] OWNER_SEQUENCER = hex("65971ff3bf94bdd46a9bab769b1c6e7e8e6a324031d3719586eb51c058e6d2a8");

    private CantonFixtures() {}

    /** Loads {@code <name>.hex} as bytes. */
    @NonNull
    public static byte[] load(@NonNull final String name) {
        return HEX.parseHex(text(name + ".hex").strip());
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

    static byte[] hex(final String s) {
        return HEX.parseHex(s);
    }

    static String formatHex(final byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    static byte[] fp(final byte[] namespace) {
        return utf8("1220" + HEX.formatHex(namespace));
    }

    /** A {@code Versioned{1: data, 2: version}} wrapper. */
    static byte[] versioned(final byte[] data, final int version) {
        return CantonBytes.concat(
                Pb.lenField(1, data), new Pb().varint(2, version).build());
    }

    /** A {@code Versioned} wrapper at the topology protocol version. */
    static byte[] versionedV30(final byte[] data) {
        return versioned(data, (int) CantonTopology.VERSION_V30);
    }

    /** A sha256 multihash: {@code 0x12 0x20 || digest}. */
    static byte[] multihash(final byte[] digest) {
        return CantonBytes.concat(new byte[] {0x12, 0x20}, digest);
    }

    static byte[] rlpString(final byte[] b) {
        if (b.length == 1 && (b[0] & 0xff) < 0x80) {
            return b.clone();
        }
        return CantonBytes.concat(rlpHeader(0x80, b.length), b);
    }

    static byte[] rlpList(final byte[]... items) {
        final byte[] payload = CantonBytes.concat(items);
        return CantonBytes.concat(rlpHeader(0xc0, payload.length), payload);
    }

    private static byte[] rlpHeader(final int base, final int len) {
        if (len <= 55) {
            return new byte[] {(byte) (base + len)};
        }
        int n = 4;
        while (n > 1 && (len >>> (8 * (n - 1))) == 0) {
            --n;
        }
        final byte[] lenBytes = new byte[n];
        for (int i = 0; i < n; ++i) {
            lenBytes[i] = (byte) (len >>> (8 * (n - 1 - i)));
        }
        return CantonBytes.concat(new byte[] {(byte) (base + 55 + n)}, lenBytes);
    }

    /** Minimal protobuf wire-format builder for hand-crafted test messages. */
    static final class Pb {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb field(final int number, final byte[] value) {
            out.writeBytes(lenField(number, value));
            return this;
        }

        Pb varint(final int number, final long value) {
            writeVarint(out, (long) number << 3);
            writeVarint(out, value);
            return this;
        }

        byte[] build() {
            return out.toByteArray();
        }

        /** A single LEN-delimited field {@code number: value}. */
        static byte[] lenField(final long number, final byte[] value) {
            final var o = new ByteArrayOutputStream();
            writeVarint(o, (number << 3) | CantonProto.WIRE_LEN);
            writeVarint(o, value.length);
            o.writeBytes(value);
            return o.toByteArray();
        }

        /** Treat raw field bytes as a complete message body (identity wrapper for readability). */
        static byte[] msg(final byte[] fields) {
            return fields;
        }

        private static void writeVarint(final ByteArrayOutputStream o, long v) {
            while (true) {
                final int b = (int) (v & 0x7f);
                v >>>= 7;
                if (v != 0) {
                    o.write(b | 0x80);
                } else {
                    o.write(b);
                    return;
                }
            }
        }
    }
}
