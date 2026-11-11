// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CantonProofVerifier#openTree}.
 *
 * <h2>Fixture provenance (binding ruling)</h2>
 * The upstream reference has no monolithic opening fixture: it builds openings at test time, and the LocalNet
 * bundle's transaction views are encrypted ({@code 3_merkle_path_from_root_to_create_node: NOT YET EXTRACTABLE}),
 * so no REAL opening can be derived from {@code localnet-example-proof-bundle.json}. Every opening here is
 * therefore <b>CONSTRUCTED</b>: {@link Builder} ports the opening construction:  ({@code _openingWithMetadataVersion},
 * {@code _openingWithSubviews}, {@code _openingBelowWorkflow}, {@code _rootViewPath}, {@code _step},
 * {@code _leafRlp}, {@code _versioned}, {@code _commonMetadata}). The root is computed bottom-up from the tree
 * shape (never through the verifier's fold) and each test self-checks the round trip: the verifier's rebuilt root
 * equals that independently computed root. Real inputs used inside the constructed vectors:
 * <ul>
 *   <li>REAL: the synchronizer id in common metadata is {@code transaction.synchronizerId} of the LocalNet JSON;
 *   <li>REAL: the VPD leaf payload is the LocalNet {@code created-event-blob.hex} bytes (opaque to the opening);
 *   <li>CONSTRUCTED (as in the Solidity builder): salts (SHA-256 of the leaf data here, keccak256 there; a salt is
 *       an opaque hash input), the placeholder submitter/participant-metadata hashes and the workflow siblings.
 * </ul>
 *
 * <p><b>Note:</b> Not byte-identical to a Solidity-run output (no forge run was done); structure and hash inputs
 * are the same.
 */
class CantonOpeningTest {

    private static final String SYNC_ID = realSynchronizerId();
    private static final byte[] VPD_DATA = CantonFixtures.load("created-event-blob");
    private static final byte[] VCD_DATA = lenField(2, "app-one".getBytes(StandardCharsets.UTF_8));

    private static String realSynchronizerId() {
        final var m = Pattern.compile("\"synchronizerId\":\\s*\"([^\"]+)\"")
                .matcher(CantonFixtures.text("localnet-example-proof-bundle.json"));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static CantonAnchor.Anchor anchor(final String sync, final int group) {
        return new CantonAnchor.Anchor(sync, 0L, group, new byte[32], new byte[32], new byte[32], "", 0L, 0, "app-one");
    }

    private static CantonAnchor.Anchor anchor() {
        return anchor(SYNC_ID, 7);
    }

    private static String error(final Runnable r) {
        return assertThrows(CantonProofException.class, r::run).errorName();
    }

    // ---- accepted shapes --------------------------------------------------------------------------------------

    /** CONSTRUCTED (_opening / _rootViewPath): root view, path [[[],[]]], one-child MerkleSeq.*/
    @Test
    void rootViewOneChildSeqRebuildsToRoot() {
        final var b = new Builder();
        final var op = b.rootView(31);
        final var opened = CantonProofVerifier.openTree(op.rlp(), anchor(), op.root());
        assertThat(opened.vcd()).isEqualTo(VCD_DATA);
        assertThat(opened.vpd()).isEqualTo(VPD_DATA);
        assertThat(opened.vpdIsV32()).isFalse();
    }

    /** CONSTRUCTED: same shape with a v32 ViewParticipantData leaf. */
    @Test
    void vpdVersion32IsFlagged() {
        final var op = new Builder().rootView(32);
        assertThat(CantonProofVerifier.openTree(op.rlp(), anchor(), op.root()).vpdIsV32())
                .isTrue();
    }

    /** CONSTRUCTED (_openingWithSubviews): workflow view with its subviews as a single hash. */
    @Test
    void viewWithSubviewsHashRebuildsToRoot() {
        final var op = new Builder().withSubviews(sha("the issuer's subviews"));
        final var opened = CantonProofVerifier.openTree(op.rlp(), anchor(), op.root());
        assertThat(opened.vpd()).isEqualTo(VPD_DATA);
    }

    /** CONSTRUCTED (_openingBelowWorkflow): child of a workflow view; 4 steps incl. non-empty "before" siblings. */
    @Test
    void viewBelowWorkflowRebuildsToRoot() {
        final var op = new Builder().belowWorkflow();
        assertThat(CantonProofVerifier.openTree(op.rlp(), anchor(), op.root()).vpd())
                .isEqualTo(VPD_DATA);
    }

    /** A step with siblings on both sides: fold order is before ++ node ++ after. CONSTRUCTED. */
    @Test
    void siblingsOnBothSidesFoldInOrder() {
        final var op = new Builder().bothSides();
        CantonProofVerifier.openTree(op.rlp(), anchor(), op.root());
    }

    /** The depth cap: 64 steps are accepted, 65 are rejected (InvalidPayloadShape). CONSTRUCTED. */
    @Test
    void pathDepthLimit() {
        final var ok = new Builder().deep(CantonProofVerifier.MAX_PATH_DEPTH);
        CantonProofVerifier.openTree(ok.rlp(), anchor(), ok.root());
        final var tooDeep = new Builder().deep(CantonProofVerifier.MAX_PATH_DEPTH + 1);
        assertThat(error(() -> CantonProofVerifier.openTree(tooDeep.rlp(), anchor(), tooDeep.root())))
                .isEqualTo("InvalidPayloadShape");
    }

    @Test
    void emptyPathRejected() {
        final var op = new Builder().custom(31, 30, 30, SYNC_ID, 7, new byte[0][], null);
        assertThat(error(() -> CantonProofVerifier.openTree(op.rlp(), anchor(), op.root())))
                .isEqualTo("InvalidPayloadShape");
    }

    // ---- root / tamper ----------------------------------------------------------------------------------------

    @Test
    void wrongRootHashRejected() {
        final var op = new Builder().rootView(31);
        final byte[] bad = op.root().clone();
        bad[0] ^= 1;
        assertThat(error(() -> CantonProofVerifier.openTree(op.rlp(), anchor(), bad)))
                .isEqualTo("RootHashMismatch");
    }

    @Test
    void tamperedSiblingRejected() {
        final var b = new Builder();
        final var op = b.belowWorkflow();
        final byte[] tampered = b.flipByteOf(op.rlp(), sha("workflow view common data"));
        assertThat(error(() -> CantonProofVerifier.openTree(tampered, anchor(), op.root())))
                .isEqualTo("RootHashMismatch");
    }

    @Test
    void tamperedSubviewsHashRejected() {
        final var b = new Builder();
        final byte[] subviews = sha("the issuer's subviews");
        final var op = b.withSubviews(subviews);
        final byte[] tampered = b.flipByteOf(op.rlp(), subviews);
        assertThat(error(() -> CantonProofVerifier.openTree(tampered, anchor(), op.root())))
                .isEqualTo("RootHashMismatch");
    }

    @Test
    void tamperedSubmitterMetadataHashRejected() {
        final var b = new Builder();
        final var op = b.rootView(31);
        final byte[] tampered = b.flipByteOf(op.rlp(), Builder.SUBMITTER);
        assertThat(error(() -> CantonProofVerifier.openTree(tampered, anchor(), op.root())))
                .isEqualTo("RootHashMismatch");
    }

    @Test
    void tamperedViewLeafRejected() {
        final var b = new Builder();
        final var op = b.rootView(31);
        final byte[] tampered = b.flipByteOf(op.rlp(), VCD_DATA);
        assertThat(error(() -> CantonProofVerifier.openTree(tampered, anchor(), op.root())))
                .isEqualTo("RootHashMismatch");
    }

    // ---- metadata ---------------------------------------------------------------------------------------------

    @Test
    void wrongSynchronizerIdIsMetadataMismatch() {
        final var op = new Builder().rootView(31);
        assertThat(error(() -> CantonProofVerifier.openTree(op.rlp(), anchor(SYNC_ID + "x", 7), op.root())))
                .isEqualTo("MetadataMismatch");
    }

    @Test
    void wrongMediatorGroupIsMetadataMismatch() {
        final var op = new Builder().rootView(31);
        assertThat(error(() -> CantonProofVerifier.openTree(op.rlp(), anchor(SYNC_ID, 8), op.root())))
                .isEqualTo("MetadataMismatch");
    }

    /** The anchor's group is uint32: 0xFFFFFFFF must compare as 4294967295, not -1. CONSTRUCTED. */
    @Test
    void mediatorGroupIsUnsigned32() {
        final var op = new Builder().custom(31, 30, 30, SYNC_ID, 0xFFFFFFFFL, Builder.ROOT_PATH, null);
        CantonProofVerifier.openTree(op.rlp(), anchor(SYNC_ID, -1), op.root());
    }

    @Test
    void metadataIsCheckedBeforeRoot() {
        // Wrong metadata and a wrong root hash: the reference reverts MetadataMismatch first.
        final var op = new Builder().rootView(31);
        assertThat(error(() -> CantonProofVerifier.openTree(op.rlp(), anchor(SYNC_ID, 8), new byte[32])))
                .isEqualTo("MetadataMismatch");
    }

    // ---- leaf versions ----------------------------------------------------------------------------------------

    @Test
    void commonMetadataMustBeVersion30() {
        for (final int v : new int[] {29, 31, 32}) {
            final var op = new Builder().custom(31, 30, v, SYNC_ID, 7, Builder.ROOT_PATH, null);
            assertThat(error(() -> CantonProofVerifier.openTree(op.rlp(), anchor(), op.root())))
                    .as("cm version %d", v)
                    .isEqualTo("UnsupportedLeafVersion");
        }
    }

    @Test
    void viewCommonDataMustBeVersion30() {
        for (final int v : new int[] {29, 31}) {
            final var op = new Builder().custom(31, v, 30, SYNC_ID, 7, Builder.ROOT_PATH, null);
            assertThat(error(() -> CantonProofVerifier.openTree(op.rlp(), anchor(), op.root())))
                    .as("vcd version %d", v)
                    .isEqualTo("UnsupportedLeafVersion");
        }
    }

    @Test
    void participantDataMustBeVersion31Or32() {
        for (final int v : new int[] {30, 33}) {
            final var op = new Builder().custom(v, 30, 30, SYNC_ID, 7, Builder.ROOT_PATH, null);
            assertThat(error(() -> CantonProofVerifier.openTree(op.rlp(), anchor(), op.root())))
                    .as("vpd version %d", v)
                    .isEqualTo("UnsupportedLeafVersion");
        }
    }

    // ---- shape / strict RLP -----------------------------------------------------------------------------------

    @Test
    void wrongOpeningItemCountRejected() {
        final var op = new Builder().rootView(31);
        final byte[] four = rlpList(Arrays.copyOf(Builder.items(op.rlp()), 4));
        assertThat(error(() -> CantonProofVerifier.openTree(four, anchor(), op.root())))
                .isEqualTo("InvalidPayloadShape");
    }

    @Test
    void wrongViewItemCountRejected() {
        final var b = new Builder();
        final var op = b.rootView(31);
        final byte[][] o = Builder.items(op.rlp());
        final byte[][] v = Builder.items(o[3]);
        o[3] = rlpList(v[0]); // one-item view
        final byte[] bad = rlpList(o);
        assertThat(error(() -> CantonProofVerifier.openTree(bad, anchor(), op.root())))
                .isEqualTo("InvalidPayloadShape");
        o[3] = rlpList(v[0], v[1], rlpString(new byte[32]), rlpString(new byte[32]));
        final byte[] four = rlpList(o);
        assertThat(error(() -> CantonProofVerifier.openTree(four, anchor(), op.root())))
                .isEqualTo("InvalidPayloadShape");
    }

    @Test
    void stepWithWrongItemCountRejected() {
        final var op = new Builder().rootView(31);
        final byte[][] o = Builder.items(op.rlp());
        o[4] = rlpList(rlpList(rlpList()));
        assertThat(error(() -> CantonProofVerifier.openTree(rlpList(o), anchor(), op.root())))
                .isEqualTo("InvalidPayloadShape");
    }

    /** OpenZeppelin RLP is strict; the module's lenient Rlp would accept 0x81 0x05 for the byte 0x05. */
    @Test
    void nonCanonicalRlpRejected() {
        final var op = new Builder().rootView(31);
        final byte[][] o = Builder.items(op.rlp());
        // sibling 32 bytes -> encode as a non-minimal long-form string (0xb8 0x20 ...) where 0xa0 is canonical
        o[0] = concat(new byte[] {(byte) 0xb8, 0x20}, Builder.SUBMITTER);
        assertThat(error(() -> CantonProofVerifier.openTree(rlpList(o), anchor(), op.root())))
                .isEqualTo("InvalidPayloadShape");
    }

    @Test
    void trailingBytesAfterOpeningRejected() {
        final var op = new Builder().rootView(31);
        final byte[] trailing = concat(op.rlp(), new byte[] {0});
        assertThat(error(() -> CantonProofVerifier.openTree(trailing, anchor(), op.root())))
                .isEqualTo("InvalidPayloadShape");
    }

    // ---- builder --------------------------------------------------------------

    record Opening(byte[] rlp, byte[] root) {}

    static final class Builder {
        static final byte[] SUBMITTER = sha("submitter-metadata");
        static final byte[] PARTICIPANT = sha("participant-metadata");
        static final byte[][] ROOT_PATH = {step(List.of(), List.of())};
        private final byte[] workflowSibling = sha("the workflow's other subview");

        /** _opening: one root view, path [[[],[]]]. */
        Opening rootView(final int vpdVersion) {
            return custom(vpdVersion, 30, 30, SYNC_ID, 7, ROOT_PATH, null);
        }

        /** _openingWithSubviews. */
        Opening withSubviews(final byte[] subviews) {
            return custom(31, 30, 30, SYNC_ID, 7, ROOT_PATH, subviews);
        }

        /**
         * Generic: the expected root is computed bottom-up from the tree this describes ("root-view seq of one"
         * path only), independently of the verifier's fold; see {@link #expectedRoot}.
         */
        Opening custom(
                final int vpdVersion,
                final int vcdVersion,
                final int cmVersion,
                final String sync,
                final long group,
                final byte[][] path,
                final byte[] subviews) {
            final byte[] cm = commonMetadata(sync, group);
            byte[] view = subviews == null
                    ? CantonMerkle.inner2(leafHash(8, VCD_DATA, vcdVersion), leafHash(9, VPD_DATA, vpdVersion))
                    : CantonMerkle.inner(
                            new byte[][] {leafHash(8, VCD_DATA, vcdVersion), leafHash(9, VPD_DATA, vpdVersion), subviews
                            });
            // The tree these tests describe: the view is the only child of the root-views MerkleSeq.
            final byte[] node = CantonMerkle.inner1(view);
            final byte[] root =
                    CantonMerkle.inner(new byte[][] {SUBMITTER, leafHash(6, cm, cmVersion), PARTICIPANT, node});
            final byte[][] viewItems = subviews == null
                    ? new byte[][] {leafRlp(VCD_DATA, vcdVersion), leafRlp(VPD_DATA, vpdVersion)}
                    : new byte[][] {leafRlp(VCD_DATA, vcdVersion), leafRlp(VPD_DATA, vpdVersion), rlpString(subviews)};
            final byte[] rlp = rlpList(
                    rlpString(SUBMITTER),
                    leafRlp(cm, cmVersion),
                    rlpString(PARTICIPANT),
                    rlpList(viewItems),
                    rlpList(path));
            return new Opening(rlp, root);
        }

        /** _openingBelowWorkflow: workflow view {vcd,vpd,subviews}, subviews = inner(inner(sibling), inner(child)). */
        Opening belowWorkflow() {
            final byte[] wVcd = sha("workflow view common data");
            final byte[] wVpd = sha("workflow view participant data");
            final byte[] child = CantonMerkle.inner2(leafHash(8, VCD_DATA, 30), leafHash(9, VPD_DATA, 31));
            final byte[] subviews =
                    CantonMerkle.inner2(CantonMerkle.inner1(workflowSibling), CantonMerkle.inner1(child));
            final byte[] workflow = CantonMerkle.inner(new byte[][] {wVcd, wVpd, subviews});
            final byte[] cm = commonMetadata(SYNC_ID, 7);
            final byte[] root = CantonMerkle.inner(
                    new byte[][] {SUBMITTER, leafHash(6, cm, 30), PARTICIPANT, CantonMerkle.inner1(workflow)});
            final byte[][] path = {
                step(List.of(), List.of()),
                step(List.of(CantonMerkle.inner1(workflowSibling)), List.of()),
                step(List.of(wVcd, wVpd), List.of()),
                step(List.of(), List.of())
            };
            return new Opening(
                    rlpList(
                            rlpString(SUBMITTER),
                            leafRlp(cm, 30),
                            rlpString(PARTICIPANT),
                            rlpList(leafRlp(VCD_DATA, 30), leafRlp(VPD_DATA, 31)),
                            rlpList(path)),
                    root);
        }

        /** Siblings before and after the opened node at the lowest step. */
        Opening bothSides() {
            final byte[] a = sha("a");
            final byte[] z = sha("z");
            final byte[] child = CantonMerkle.inner2(leafHash(8, VCD_DATA, 30), leafHash(9, VPD_DATA, 31));
            final byte[] seq = CantonMerkle.inner(new byte[][] {a, child, z});
            final byte[] cm = commonMetadata(SYNC_ID, 7);
            final byte[] root = CantonMerkle.inner(
                    new byte[][] {SUBMITTER, leafHash(6, cm, 30), PARTICIPANT, CantonMerkle.inner1(seq)});
            final byte[][] path = {step(List.of(a), List.of(z)), step(List.of(), List.of())};
            return new Opening(
                    rlpList(
                            rlpString(SUBMITTER),
                            leafRlp(cm, 30),
                            rlpString(PARTICIPANT),
                            rlpList(leafRlp(VCD_DATA, 30), leafRlp(VPD_DATA, 31)),
                            rlpList(path)),
                    root);
        }

        /** A chain of {@code depth} single-child steps: node_{i+1} = inner1(node_i). */
        Opening deep(final int depth) {
            byte[] node = CantonMerkle.inner2(leafHash(8, VCD_DATA, 30), leafHash(9, VPD_DATA, 31));
            final byte[][] path = new byte[depth][];
            for (int i = 0; i < depth; ++i) {
                node = CantonMerkle.inner1(node);
                path[i] = step(List.of(), List.of());
            }
            final byte[] cm = commonMetadata(SYNC_ID, 7);
            final byte[] root = CantonMerkle.inner(new byte[][] {SUBMITTER, leafHash(6, cm, 30), PARTICIPANT, node});
            return new Opening(
                    rlpList(
                            rlpString(SUBMITTER),
                            leafRlp(cm, 30),
                            rlpString(PARTICIPANT),
                            rlpList(leafRlp(VCD_DATA, 30), leafRlp(VPD_DATA, 31)),
                            rlpList(path)),
                    root);
        }

        /** {@code opening} with the first byte of {@code needle} (found in its RLP encoding) flipped. */
        byte[] flipByteOf(final byte[] opening, final byte[] needle) {
            final byte[] out = opening.clone();
            outer:
            for (int i = 0; i + needle.length <= out.length; ++i) {
                for (int j = 0; j < needle.length; ++j) {
                    if (out[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                out[i] ^= 1;
                return out;
            }
            throw new AssertionError("needle not found");
        }

        static byte[][] items(final byte[] rlpList) {
            final var s = CantonRlp.of(rlpList);
            final var l = CantonRlp.readList(s);
            final byte[][] out = new byte[l.size()][];
            for (int i = 0; i < out.length; ++i) {
                out[i] = Arrays.copyOfRange(
                        l.get(i).buf(),
                        l.get(i).off(),
                        l.get(i).off() + l.get(i).len());
            }
            return out;
        }
    }

    // ---- encoding helpers (CantonProofBuilder.sol _versioned/_leafRlp/_commonMetadata/_step) ------------------

    private static byte[] sha(final String s) {
        return sha(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha(final byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** UntypedVersionedMessage{1: data, 2: version}. */
    private static byte[] versioned(final byte[] data, final int version) {
        return concat(lenField(1, data), varintField(2, version));
    }

    private static byte[] leafHash(final int purpose, final byte[] data, final int version) {
        return CantonMerkle.leaf(purpose, sha(data), versioned(data, version));
    }

    private static byte[] leafRlp(final byte[] data, final int version) {
        return rlpList(rlpString(sha(data)), rlpString(versioned(data, version)));
    }

    /** CommonMetadata{1:{2: salt}, 3: synchronizer id, 4: uuid, 5: mediator group}. */
    private static byte[] commonMetadata(final String sync, final long group) {
        return concat(
                lenField(1, lenField(2, sha("cm-salt"))),
                lenField(3, sync.getBytes(StandardCharsets.UTF_8)),
                lenField(4, "uuid-0000".getBytes(StandardCharsets.UTF_8)),
                varintField(5, group));
    }

    private static byte[] step(final List<byte[]> before, final List<byte[]> after) {
        final List<byte[]> b = new ArrayList<>();
        before.forEach(x -> b.add(rlpString(x)));
        final List<byte[]> a = new ArrayList<>();
        after.forEach(x -> a.add(rlpString(x)));
        return rlpList(rlpList(b.toArray(new byte[0][])), rlpList(a.toArray(new byte[0][])));
    }

    private static byte[] lenField(final int number, final byte[] value) {
        return concat(varint(((long) number << 3) | 2), varint(value.length), value);
    }

    private static byte[] varintField(final int number, final long value) {
        return concat(varint((long) number << 3), varint(value));
    }

    private static byte[] varint(long v) {
        final var out = new ByteArrayOutputStream();
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    private static byte[] rlpString(final byte[] b) {
        if (b.length == 1 && (b[0] & 0xff) < 0x80) {
            return b.clone();
        }
        return concat(rlpHeader(0x80, b.length), b);
    }

    private static byte[] rlpList(final byte[]... items) {
        final byte[] payload = concat(items);
        return concat(rlpHeader(0xc0, payload.length), payload);
    }

    private static byte[] rlpHeader(final int base, final int len) {
        if (len <= 55) {
            return new byte[] {(byte) (base + len)};
        }
        final byte[] lenBytes = varintBigEndian(len);
        return concat(new byte[] {(byte) (base + 55 + lenBytes.length)}, lenBytes);
    }

    private static byte[] varintBigEndian(final int v) {
        int n = 4;
        while (n > 1 && (v >>> (8 * (n - 1))) == 0) {
            --n;
        }
        final byte[] out = new byte[n];
        for (int i = 0; i < n; ++i) {
            out[i] = (byte) (v >>> (8 * (n - 1 - i)));
        }
        return out;
    }

    private static byte[] concat(final byte[]... parts) {
        final var out = new ByteArrayOutputStream();
        for (final byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
