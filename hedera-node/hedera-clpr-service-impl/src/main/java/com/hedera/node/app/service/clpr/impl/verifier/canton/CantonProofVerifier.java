// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiedConfig;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.Range;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonRlp.Slice;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Canton proof verification. This class currently holds the Merkle opening ({@code _openTree} of the Solidity
 * reference implementation.
 *
 * <p><b>Opening.</b> {@code opening = RLP[submitterMetadataHash, commonMetadata, participantMetadataHash, view,
 * path]}, where a leaf is RLP {@code [salt, bytes]}, the view RLP {@code [viewCommonData, viewParticipantData]}
 * with the hash of its subviews as an optional third item, and {@code path} RLP {@code [step, ...]} from the view
 * up to the root views, each step {@code [[hash before, ...], [hash after, ...]]}. Leaves are hashed with their
 * {@code UntypedVersionedMessage} wrapper and parsed without it.
 */
public final class CantonProofVerifier implements ClprVerifier {
    private static final Logger log = LogManager.getLogger(CantonProofVerifier.class);

    /** Bundle proof: {@code [verdict, opening, createdIndex, mediatorSet, rotations, manifest, payloads]}. */
    private static final int BUNDLE_FIELDS = 7;

    /** Maximum number of path steps in an opening. */
    public static final int MAX_PATH_DEPTH = 64;

    private static final int OPENING_FIELDS = 5;
    private static final long LEAF_VERSION_V30 = 30;
    private static final long VPD_VERSION_V31 = 31;
    private static final long VPD_VERSION_V32 = 32;

    /** The fields of {@code Channel} that the verifier reads, by position. */
    private static final int CHANNEL_FIELDS = 26;

    private static final int CHANNEL_APP = 1;
    private static final int CHANNEL_ID = 2;
    private static final int CHANNEL_NEXT_MESSAGE_ID = 5;
    private static final int CHANNEL_SENT_RUNNING_HASH = 6;
    private static final int CHANNEL_MANIFEST_VERSION = 10;
    private static final int CHANNEL_BUNDLED_RECEIVED_ID = 14;
    private static final int CHANNEL_BUNDLED_STATUS = 17;
    private static final int CHANNEL_BUNDLED_RECEIVED_HASH = 24;
    private static final int CHANNEL_BUNDLED_MANIFEST_HASH = 25;

    private static final byte[] MODULE_A = "Clpr".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MODULE_B = "Service".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENTITY_CHANNEL = "Channel".getBytes(StandardCharsets.UTF_8);

    /** The opened view's {@code ViewCommonData} and {@code ViewParticipantData} protobufs, and whether the latter is v32. */
    public record OpenedView(@NonNull byte[] vcd, @NonNull byte[] vpd, boolean vpdIsV32) {}

    /**
     * What the verifier needs of the channel's service: the 32-byte channel id and its lowercase-hex text, the
     * pinned package id as hex text, the operator party id, and the whole service address
     * ({@code packageId(32) || UTF-8(operatorPartyId)}).
     */
    public record Service(
            @NonNull byte[] channelId,
            @NonNull byte[] channelIdHex,
            @NonNull byte[] packageIdHex,
            @NonNull byte[] operator,
            @NonNull byte[] serviceAddress) {}

    /** The queue metadata after a write, and the manifest hash that the written channel version carried (empty if none). */
    public record WriteResult(
            @NonNull ClprQueueMetadata metadata, @NonNull byte[] manifestHash) {}

    /**
     * The calling service's live outbound message {@code messageId} on a channel, as the reference reads it with
     * {@code getMessage}. Returns {@code null} when the service holds none; throws
     * {@link CantonProofException#serviceCallFailed()} when the lookup itself fails.
     */
    @FunctionalInterface
    public interface OutboundLookup {
        @Nullable
        ClprMessagePayload find(@NonNull byte[] channelId, long messageId);
    }

    private record ReadView(byte[] vcd, byte[] vpd, boolean v32, byte[] hash) {}

    private record Leaf(byte[] salt, byte[] data) {}

    /**
     * The mediator-verdict checker ({@link CantonVerdictVerifier}) is stateless with static methods, so there is
     * nothing to bind; the reference's {@code VERDICTS} immutable has no instance equivalent.
     */
    public CantonProofVerifier() {}

    // ── ClprVerifier ───────────────────────────────────────────────────────

    /**
     * Not yet implemented.
     *
     * @throws HandleException always, with {@code CLPR_VERIFIER_CONFIG_FAILED}
     */
    @Override
    @NonNull
    public VerifiedConfig verifyConfig(
            @NonNull final Bytes configProofBytes,
            @NonNull final Bytes channelId,
            @NonNull final Bytes endpointManifestProofBytes,
            @NonNull final HandleContext context)
            throws HandleException {
        throw new HandleException(CLPR_VERIFIER_CONFIG_FAILED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs {@link #verifyBundle(byte[], byte[], byte[], OutboundLookup)}; any verification failure is a
     * {@link HandleException} with {@code CLPR_BUNDLE_VERIFICATION_FAILED}. The sender's live outbound message that
     * a REPLY answers is read from the CLPR message queue in state, as the reference reads it from the calling
     * {@code ClprService}.
     */
    @Override
    @NonNull
    public ClprBundleContent verifyBundle(
            @NonNull final Bytes bundlePayload,
            @NonNull final Bytes trustAnchor,
            @NonNull final Bytes channelContext,
            @NonNull final HandleContext context)
            throws HandleException {
        Objects.requireNonNull(bundlePayload, "bundlePayload");
        Objects.requireNonNull(trustAnchor, "trustAnchor");
        Objects.requireNonNull(channelContext, "channelContext");
        Objects.requireNonNull(context, "context");
        final OutboundLookup outbound = (channelId, messageId) -> {
            final ReadableMessageQueueStore queue;
            try {
                queue = context.storeFactory().readableStore(ReadableMessageQueueStore.class);
            } catch (final RuntimeException e) {
                throw CantonProofException.serviceCallFailed("message queue unavailable: " + e.getMessage());
            }
            final var stored = queue.getMessage(Bytes.wrap(channelId), messageId);
            return stored == null || !stored.hasPayload() ? null : stored.payload();
        };
        try {
            return verifyBundle(
                    bundlePayload.toByteArray(), trustAnchor.toByteArray(), channelContext.toByteArray(), outbound);
        } catch (final CantonProofException e) {
            throw new HandleException(e.responseCode());
        } catch (final RuntimeException e) {
            log.error("Unexpected error verifying bundle", e);
            // The reference reverts on any malformed input; so does this.
            throw new HandleException(ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED);
        }
    }

    // ── verifyBundle ───────────────────────────────────────────────────────

    /**
     * Port of {@code CantonVerifier.verifyBundle}. {@code proofBytes} = RLP {@code [verdict, opening, createdIndex,
     * mediatorSet, rotations, manifest, payloads]}.
     *
     * <p><b>Rotations (PR A).</b> {@code rotations} must be empty ({@code 0x80} or {@code 0xc0}, as the reference's
     * {@code isEmpty}); anything else throws {@code RotationsNotSupported}, as applying topology rotations is a future
     * task. With empty rotations the reference's {@code verifyBundleVerdict} still completes a pending Logical
     * Synchronizer Upgrade when the verdict's request id is above the anchor's upgrade time (the verdict is then
     * checked against the successor synchronizer and a new anchor is returned), and that is ported here.
     *
     * @param proofBytes the bundle proof
     * @param trustAnchor the channel's anchor, {@link CantonAnchor#decode}
     * @param channelContext {@code channelId(32) || serviceAddress}
     * @param outbound the calling service's live outbound messages, for REPLY target checks
     * @return metadata of the proven write, the payloads, the new anchor and its id if the bundle changed it, and
     *     the endpoint manifest if the write committed to one
     * @throws CantonProofException on any verification failure
     */
    @NonNull
    static ClprBundleContent verifyBundle(
            @NonNull final byte[] proofBytes,
            @NonNull final byte[] trustAnchor,
            @NonNull final byte[] channelContext,
            @NonNull final OutboundLookup outbound) {
        final List<Slice> fields = CantonRlp.readList(CantonRlp.of(proofBytes));
        if (fields.size() != BUNDLE_FIELDS) {
            throw CantonProofException.invalidPayloadShape("bundle has " + fields.size() + " items");
        }
        final CantonAnchor.Anchor anchor = CantonAnchor.decode(trustAnchor);
        final Service service = decodeService(channelContext);

        final byte[] mediatorSet = CantonRlp.readBytes(fields.get(3));
        if (!Arrays.equals(MiscCryptoUtils.keccak256DigestOf(mediatorSet), anchor.mediatorsHash())) {
            throw CantonProofException.trustSetMismatch();
        }

        final List<Slice> verdict = CantonRlp.readList(fields.get(0));
        if (verdict.size() != 2) {
            throw CantonProofException.invalidPayloadShape("verdict has " + verdict.size() + " items");
        }
        final BundleVerdict bv = verifyBundleVerdict(
                CantonRlp.readBytes(verdict.get(0)), bytesOf(verdict.get(1)), mediatorSet, anchor, fields.get(4));
        final CantonAnchor.Anchor current = bv.anchor();

        final OpenedView opened = openTree(bytesOf(fields.get(1)), current, bv.rootHash());
        final byte[] app = current.app().getBytes(StandardCharsets.UTF_8);

        final List<byte[]> payloads = payloads(fields.get(6));
        final WriteResult write = readWrite(opened.vpd(), opened.vpdIsV32(), createdIndex(fields.get(2)), service, app);
        // A required confirmer re-runs the whole view with the pinned package, so the app's approval covers the
        // running hash of the write, which binds every payload.
        if (!CantonProto.isRequiredConfirmer(opened.vcd(), app)) {
            throw CantonProofException.approverMissing();
        }
        final List<ClprMessagePayload> messages = new ArrayList<>(payloads.size());
        for (final byte[] payload : payloads) {
            checkPayload(payload, service.channelId(), app, outbound);
            messages.add(parsePayload(payload));
        }

        final byte[] manifest = CantonRlp.readBytes(fields.get(5));
        if ((write.manifestHash().length == 0) != (manifest.length == 0)) {
            throw CantonProofException.manifestMismatch("manifest presence differs from the write's commitment");
        }

        final ClprBundleContent.Builder content =
                ClprBundleContent.newBuilder().metadata(write.metadata()).messages(messages);
        if (bv.newTrustAnchor().length != 0) {
            content.newTrustAnchor(Bytes.wrap(bv.newTrustAnchor()))
                    .newTrustAnchorId(Bytes.wrap(uint64Bytes(bv.requestId())));
        }
        if (manifest.length != 0) {
            content.newEndpointManifest(decodeManifest(manifest, write.manifestHash(), service.serviceAddress()));
        }
        return content.build();
    }

    /** The verdict's outcome: request id, root hash, the anchor to open under, and the new anchor if it changed. */
    private record BundleVerdict(long requestId, byte[] rootHash, CantonAnchor.Anchor anchor, byte[] newTrustAnchor) {}

    /**
     * {@code CantonVerdictVerifier.verifyBundleVerdict} for empty {@code rotations}: asserts they are empty,
     * completes a pending upgrade when the verdict is past its upgrade time, then checks the verdict.
     */
    private static BundleVerdict verifyBundleVerdict(
            final byte[] typedContent,
            final byte[] signatures,
            final byte[] mediatorSet,
            final CantonAnchor.Anchor anchor,
            final Slice rotations) {
        if (!isEmpty(rotations)) {
            throw CantonProofException.rotationsNotSupported();
        }
        final CantonProto.Verdict v = CantonProto.parseVerdict(typedContent);
        final boolean upgraded = !anchor.successorSynchronizerId().isEmpty()
                && Long.compareUnsigned(v.requestId(), anchor.upgradeTime()) > 0;
        final CantonAnchor.Anchor effective = upgraded
                ? new CantonAnchor.Anchor(
                        anchor.successorSynchronizerId(),
                        anchor.lastRequestId(),
                        anchor.mediatorGroup(),
                        anchor.synchronizerNamespace(),
                        anchor.ownersHash(),
                        anchor.mediatorsHash(),
                        "",
                        0L,
                        0,
                        anchor.app())
                : anchor;
        final CantonVerdictVerifier.VerdictResult r = CantonVerdictVerifier.verifyVerdict(
                typedContent,
                signatures,
                mediatorSet,
                effective.synchronizerId().getBytes(StandardCharsets.UTF_8),
                effective.lastRequestId());
        if (!upgraded) {
            return new BundleVerdict(r.requestId(), r.rootHash(), effective, new byte[0]);
        }
        final CantonAnchor.Anchor next = new CantonAnchor.Anchor(
                effective.synchronizerId(),
                r.requestId(),
                effective.mediatorGroup(),
                effective.synchronizerNamespace(),
                effective.ownersHash(),
                effective.mediatorsHash(),
                "",
                0L,
                0,
                effective.app());
        return new BundleVerdict(r.requestId(), r.rootHash(), effective, CantonAnchor.encode(next));
    }

    /** {@code ClprCantonRlp.isEmpty}: an empty RLP string or list. */
    private static boolean isEmpty(final Slice s) {
        if (s.len() != 1) {
            return s.len() == 0;
        }
        final int head = s.buf()[s.off()] & 0xff;
        return head == 0x80 || head == 0xc0;
    }

    /** {@code RLP.readUint256} as an index: a value beyond {@code int} is out of range of any created list. */
    private static int createdIndex(final Slice item) {
        final BigInteger v = new BigInteger(1, CantonRlp.readBytes32(item));
        return v.bitLength() > 30 ? Integer.MAX_VALUE : v.intValue();
    }

    private static byte[] bytesOf(final Slice s) {
        return Arrays.copyOfRange(s.buf(), s.off(), s.off() + s.len());
    }

    private static byte[] uint64Bytes(final long v) {
        final byte[] out = new byte[8];
        for (int i = 0; i < 8; ++i) {
            out[i] = (byte) (v >>> (8 * (7 - i)));
        }
        return out;
    }

    /** The bundle's payload list: RLP {@code [bytes, ...]}. */
    private static List<byte[]> payloads(final Slice item) {
        final List<Slice> l = CantonRlp.readList(item);
        final List<byte[]> out = new ArrayList<>(l.size());
        for (final Slice s : l) {
            out.add(CantonRlp.readBytes(s));
        }
        return out;
    }

    private static ClprMessagePayload parsePayload(final byte[] payload) {
        try {
            return ClprMessagePayload.PROTOBUF.parseStrict(Bytes.wrap(payload).toReadableSequentialData());
        } catch (final ParseException e) {
            throw CantonProofException.cantonProtoMalformed("payload does not parse: " + e.getMessage());
        }
    }

    /** The endpoint manifest that the write committed to by {@code manifestHash}. */
    private static ClprEndpointManifest decodeManifest(
            final byte[] preimage, final byte[] manifestHash, final byte[] serviceAddress) {
        if (manifestHash.length != 32 || !Arrays.equals(MiscCryptoUtils.keccak256DigestOf(preimage), manifestHash)) {
            throw CantonProofException.manifestMismatch("manifest does not match the committed hash");
        }
        final ClprEndpointManifest manifest;
        try {
            manifest = ClprEndpointManifest.PROTOBUF.parseStrict(
                    Bytes.wrap(preimage).toReadableSequentialData());
        } catch (final ParseException e) {
            throw CantonProofException.manifestMismatch("manifest does not parse: " + e.getMessage());
        }
        if (manifest.version() == 0 || !manifest.serviceAddress().equals(Bytes.wrap(serviceAddress))) {
            throw CantonProofException.manifestMismatch("manifest version or service address");
        }
        return manifest;
    }

    // ── Service ────────────────────────────────────────────────────────────

    private static final byte[] HEX_ALPHABET = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    /**
     * {@code _decodeService} of the reference for {@code channelContext = channelId(32) || serviceAddress}, where
     * {@code serviceAddress = packageId(32) || UTF-8(operatorPartyId)} and the operator ends with
     * {@code ::1220<64 hex>}.
     */
    @NonNull
    static Service decodeService(@NonNull final byte[] channelContext) {
        if (channelContext.length < 32) {
            throw CantonProofException.invalidServiceAddress("channel context shorter than a channel id");
        }
        final byte[] channelId = Arrays.copyOfRange(channelContext, 0, 32);
        final byte[] addr = Arrays.copyOfRange(channelContext, 32, channelContext.length);
        if (addr.length < 33) {
            throw CantonProofException.invalidServiceAddress("service address shorter than 33 bytes");
        }
        final byte[] operator = Arrays.copyOfRange(addr, 32, addr.length);
        final int namespaceAt = findNamespace(operator, 0);
        if (namespaceAt < 0 || namespaceAt + 68 != operator.length) {
            throw CantonProofException.invalidServiceAddress("operator is not <name>::1220<64 hex>");
        }
        return new Service(channelId, toHex(channelId), toHex(Arrays.copyOfRange(addr, 0, 32)), operator, addr);
    }

    /**
     * The offset of {@code 1220<64 lowercase hex>} that follows the first {@code ::} at or after {@code from}, where
     * the part before it is non-empty; {@code -1} when there is none or it is malformed.
     */
    private static int findNamespace(final byte[] s, final int from) {
        for (int i = from + 1; i + 1 < s.length; ++i) {
            if (s[i] == ':' && s[i + 1] == ':') {
                final int at = i + 2;
                if (at + 68 > s.length) {
                    return -1;
                }
                if (s[at] != '1' || s[at + 1] != '2' || s[at + 2] != '2' || s[at + 3] != '0') {
                    return -1;
                }
                for (int j = at + 4; j < at + 68; ++j) {
                    final int c = s[j];
                    if (!((c >= 0x30 && c <= 0x39) || (c >= 0x61 && c <= 0x66))) {
                        return -1;
                    }
                }
                return at;
            }
        }
        return -1;
    }

    private static byte[] toHex(final byte[] data) {
        final byte[] out = new byte[data.length * 2];
        for (int i = 0; i < data.length; ++i) {
            out[2 * i] = HEX_ALPHABET[(data[i] & 0xff) >>> 4];
            out[2 * i + 1] = HEX_ALPHABET[data[i] & 0x0f];
        }
        return out;
    }

    /**
     * Rebuilds the transaction's Merkle root from {@code openingRlp} and checks it equals {@code rootHash}, and that
     * the common metadata names the anchor's synchronizer and mediator group.
     *
     * <p>RLP is read by the strict {@link CantonRlp} (the reference's OpenZeppelin {@code RLP} semantics), not the
     * lenient module {@code Rlp}.
     *
     * @throws CantonProofException {@code InvalidPayloadShape}, {@code UnsupportedLeafVersion},
     *     {@code MetadataMismatch} or {@code RootHashMismatch}
     */
    @NonNull
    public static OpenedView openTree(
            @NonNull final byte[] openingRlp,
            @NonNull final CantonAnchor.Anchor anchor,
            @NonNull final byte[] rootHash) {
        Objects.requireNonNull(openingRlp, "openingRlp");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(rootHash, "rootHash");

        final List<Slice> o = CantonRlp.readList(CantonRlp.of(openingRlp));
        if (o.size() != OPENING_FIELDS) {
            throw CantonProofException.invalidPayloadShape("opening has " + o.size() + " items");
        }

        final Leaf cmLeaf = readLeaf(o.get(1));
        final byte[] cm = unwrapV30(cmLeaf.data());
        final CantonProto.SyncAndGroup sg = CantonProto.parseCommonMetadata(cm);
        // mediatorGroup is uint32 in the anchor; the proto group is uint64.
        if (!Arrays.equals(sg.synchronizerId(), anchor.synchronizerId().getBytes(StandardCharsets.UTF_8))
                || sg.group() != Integer.toUnsignedLong(anchor.mediatorGroup())) {
            throw CantonProofException.metadataMismatch();
        }

        final ReadView view = readView(o.get(3));
        final List<Slice> path = CantonRlp.readList(o.get(4));
        if (path.isEmpty() || path.size() > MAX_PATH_DEPTH) {
            throw CantonProofException.invalidPayloadShape("path has " + path.size() + " steps");
        }
        byte[] node = view.hash();
        for (final Slice step : path) {
            node = parent(step, node);
        }

        final byte[][] children = new byte[][] {
            CantonRlp.readBytes32(o.get(0)),
            CantonMerkle.leaf(CantonMerkle.PURPOSE_COMMON_METADATA, cmLeaf.salt(), cmLeaf.data()),
            CantonRlp.readBytes32(o.get(2)),
            node
        };
        if (!Arrays.equals(CantonMerkle.inner(children), rootHash)) {
            throw CantonProofException.rootHashMismatch();
        }
        return new OpenedView(view.vcd(), view.vpd(), view.v32());
    }

    /** The inner node over {@code child} and the siblings of one path step, RLP {@code [[before...], [after...]]}. */
    private static byte[] parent(final Slice stepItem, final byte[] child) {
        final List<Slice> step = CantonRlp.readList(stepItem);
        if (step.size() != 2) {
            throw CantonProofException.invalidPayloadShape("path step has " + step.size() + " items");
        }
        final List<Slice> before = CantonRlp.readList(step.get(0));
        final List<Slice> after = CantonRlp.readList(step.get(1));
        final byte[][] children = new byte[before.size() + 1 + after.size()][];
        for (int i = 0; i < before.size(); ++i) {
            children[i] = CantonRlp.readBytes32(before.get(i));
        }
        children[before.size()] = child;
        for (int i = 0; i < after.size(); ++i) {
            children[before.size() + 1 + i] = CantonRlp.readBytes32(after.get(i));
        }
        return CantonMerkle.inner(children);
    }

    /** RLP {@code [vcd, vpd]} or {@code [vcd, vpd, subviewsHash]}: a view's two leaves, then its subviews' root. */
    private static ReadView readView(final Slice viewItem) {
        final List<Slice> v = CantonRlp.readList(viewItem);
        if (v.size() != 2 && v.size() != 3) {
            throw CantonProofException.invalidPayloadShape("view has " + v.size() + " items");
        }
        final Leaf vcdLeaf = readLeaf(v.getFirst());
        final byte[] vcdHash = CantonMerkle.leaf(CantonMerkle.PURPOSE_VIEW_COMMON_DATA, vcdLeaf.salt(), vcdLeaf.data());
        final byte[] vcd = unwrapV30(vcdLeaf.data());

        final Leaf vpdLeaf = readLeaf(v.get(1));
        final byte[] vpdHash =
                CantonMerkle.leaf(CantonMerkle.PURPOSE_VIEW_PARTICIPANT_DATA, vpdLeaf.salt(), vpdLeaf.data());
        final CantonProto.Versioned vpd = CantonProto.unwrapVersioned(vpdLeaf.data());
        if (vpd.version() != VPD_VERSION_V31 && vpd.version() != VPD_VERSION_V32) {
            throw CantonProofException.unsupportedLeafVersion();
        }
        final boolean v32 = vpd.version() == VPD_VERSION_V32;
        if (v.size() == 2) {
            return new ReadView(vcd, vpd.data(), v32, CantonMerkle.inner2(vcdHash, vpdHash));
        }
        final byte[] subviews = CantonRlp.readBytes32(v.get(2));
        return new ReadView(vcd, vpd.data(), v32, CantonMerkle.inner(new byte[][] {vcdHash, vpdHash, subviews}));
    }

    private static byte[] unwrapV30(final byte[] leaf) {
        final CantonProto.Versioned u = CantonProto.unwrapVersioned(leaf);
        if (u.version() != LEAF_VERSION_V30) {
            throw CantonProofException.unsupportedLeafVersion();
        }
        return u.data();
    }

    private static Leaf readLeaf(final Slice item) {
        final List<Slice> l = CantonRlp.readList(item);
        if (l.size() != 2) {
            throw CantonProofException.invalidPayloadShape("leaf has " + l.size() + " items");
        }
        return new Leaf(CantonRlp.readBytes(l.get(0)), CantonRlp.readBytes(l.get(1)));
    }

    // ── The write ──────────────────────────────────────────────────────────

    /**
     * The write's view: an exercise whose view created the channel's next version, {@code created_core[createdIndex]},
     * which holds the queue metadata after the write and the manifest hash that it carried. Any choice is accepted
     * (the app alone signs its channel, {@code ClprService} checks the ids and running hash, and
     * {@link #checkPayload} binds every message to the app).
     */
    @NonNull
    public static WriteResult readWrite(
            @NonNull final byte[] vpd,
            final boolean v32,
            final int createdIndex,
            @NonNull final Service service,
            @NonNull final byte[] app) {
        final CantonProto.ParticipantData data = CantonProto.parseParticipantData(vpd, v32);
        if (!data.isExercise()) {
            throw CantonProofException.wrongAction();
        }
        if (data.rolledBack()) {
            throw CantonProofException.rolledBack();
        }
        final List<Range> channel =
                created(vpd, data, createdIndex, service, ENTITY_CHANNEL, CHANNEL_FIELDS, CHANNEL_ID, CHANNEL_APP, app);
        final ClprQueueMetadata metadata =
                metadata(vpd, channel, CantonProto.uintValue(vpd, channel.get(CHANNEL_NEXT_MESSAGE_ID)));
        final byte[] manifestHash = CantonProto.hexValue(
                vpd, CantonProto.stringValue(vpd, channel.get(CHANNEL_BUNDLED_MANIFEST_HASH), CantonProto.VALUE_TEXT));
        if (manifestHash.length != 0 && manifestHash.length != 32) {
            throw CantonProofException.wrongContract("manifest hash is not 0 or 32 bytes");
        }
        return new WriteResult(metadata, manifestHash);
    }

    /** The queue metadata after a write, from the channel version that it created. */
    private static ClprQueueMetadata metadata(final byte[] vpd, final List<Range> channel, final long next) {
        return ClprQueueMetadata.newBuilder()
                .nextMessageId(next)
                .sentRunningHash(Bytes.wrap(hash32(vpd, channel.get(CHANNEL_SENT_RUNNING_HASH))))
                .receivedMessageId(CantonProto.uintValue(vpd, channel.get(CHANNEL_BUNDLED_RECEIVED_ID)))
                .receivedRunningHash(Bytes.wrap(hash32(vpd, channel.get(CHANNEL_BUNDLED_RECEIVED_HASH))))
                .status(status(vpd, channel.get(CHANNEL_BUNDLED_STATUS)))
                .endpointManifestVersion(CantonProto.uintValue(vpd, channel.get(CHANNEL_MANIFEST_VERSION)))
                .build();
    }

    /**
     * The fields of {@code created_core[index]}, which must be {@code entity} of the pinned package for this
     * channel, signed by the anchor's app and naming it in field {@code appField}.
     */
    private static List<Range> created(
            final byte[] vpd,
            final CantonProto.ParticipantData data,
            final int index,
            final Service service,
            final byte[] entity,
            final int minFields,
            final int channelField,
            final int appField,
            final byte[] app) {
        if (index < 0 || index >= data.createdCore().size()) {
            throw CantonProofException.wrongContract("created index out of range");
        }
        final List<Range> fields =
                openContract(vpd, data.createdCore().get(index), service, entity, minFields, channelField, appField);
        if (!CantonProto.equals(
                vpd, CantonProto.stringValue(vpd, fields.get(appField), CantonProto.VALUE_PARTY), app)) {
            throw CantonProofException.wrongContract("contract's app is not the anchor's app");
        }
        return fields;
    }

    /**
     * The create-argument fields of a contract that is {@code entity} of the pinned package, with at least
     * {@code minFields} fields, whose field 0 is the operator, field {@code channelField} this channel, and field
     * {@code appField} the app, which must sign it.
     */
    private static List<Range> openContract(
            final byte[] vpd,
            final CantonProto.ContractRange contractRange,
            final Service service,
            final byte[] entity,
            final int minFields,
            final int channelField,
            final int appField) {
        final CantonProto.Contract contract = CantonProto.parseContract(vpd, contractRange);
        if (!CantonProto.equals(
                vpd,
                contract.templateId(),
                CantonProto.encodeIdentifier(service.packageIdHex(), MODULE_A, MODULE_B, entity))) {
            throw CantonProofException.wrongContract("not the pinned package's template");
        }
        final List<Range> fields = CantonProto.recordFields(vpd, contract.createArg());
        if (fields.size() < minFields
                || !CantonProto.equals(
                        vpd,
                        CantonProto.stringValue(vpd, fields.getFirst(), CantonProto.VALUE_PARTY),
                        service.operator())
                || !CantonProto.equals(
                        vpd,
                        CantonProto.stringValue(vpd, fields.get(channelField), CantonProto.VALUE_TEXT),
                        service.channelIdHex())) {
            throw CantonProofException.wrongContract("operator or channel id mismatch");
        }
        final Range app = CantonProto.stringValue(vpd, fields.get(appField), CantonProto.VALUE_PARTY);
        boolean signed = false;
        for (int i = 0; i < contract.signatories().size() && !signed; ++i) {
            signed = rangesEqual(vpd, contract.signatories().get(i), app);
        }
        if (!signed) {
            throw CantonProofException.wrongContract("app does not sign the contract");
        }
        return fields;
    }

    private static boolean rangesEqual(final byte[] b, final Range x, final Range y) {
        return Arrays.equals(b, x.start(), x.end(), b, y.start(), y.end());
    }

    /** A 32-byte value written as hex text. */
    private static byte[] hash32(final byte[] vpd, final Range value) {
        final byte[] h = CantonProto.hexValue(vpd, CantonProto.stringValue(vpd, value, CantonProto.VALUE_TEXT));
        if (h.length != 32) {
            throw CantonProofException.wrongContract("hash is not 32 bytes");
        }
        return h;
    }

    /** {@code ChannelStatus} is a Daml enum with the constructors of {@link ClprChannelStatus}. */
    private static ClprChannelStatus status(final byte[] vpd, final Range value) {
        final Range name = CantonProto.enumValue(vpd, value);
        if (CantonProto.equals(vpd, name, utf8("Active"))) {
            return ClprChannelStatus.ACTIVE;
        }
        if (CantonProto.equals(vpd, name, utf8("Closing"))) {
            return ClprChannelStatus.CLOSING;
        }
        if (CantonProto.equals(vpd, name, utf8("Drained"))) {
            return ClprChannelStatus.DRAINED;
        }
        if (CantonProto.equals(vpd, name, utf8("Closed"))) {
            return ClprChannelStatus.CLOSED;
        }
        if (CantonProto.equals(vpd, name, utf8("Paused"))) {
            return ClprChannelStatus.PAUSED;
        }
        if (CantonProto.equals(vpd, name, utf8("Pending"))) {
            return ClprChannelStatus.PENDING;
        }
        throw CantonProofException.wrongContract("unknown channel status");
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ── Payloads ───────────────────────────────────────────────────────────

    /**
     * A DATA message must name the app as its sender, and a REPLY must answer a DATA message sent to the app,
     * unless it is an APPLICATION_ERROR without data. Payloads are decoded with PBJ's strict parser, as
     * {@code ClprService} decodes them, so a repeated field cannot show this check one sender and the service
     * another.
     *
     * @param outbound the calling service's live outbound messages, to find the DATA message that a REPLY answers
     */
    public static void checkPayload(
            @NonNull final byte[] payload,
            @NonNull final byte[] channelId,
            @NonNull final byte[] app,
            @NonNull final OutboundLookup outbound) {
        final boolean isData = messageIsData(payload);
        final ClprMessagePayload decoded;
        try {
            decoded =
                    ClprMessagePayload.PROTOBUF.parseStrict(Bytes.wrap(payload).toReadableSequentialData());
        } catch (final ParseException e) {
            throw CantonProofException.cantonProtoMalformed("payload does not parse: " + e.getMessage());
        }
        if (isData) {
            if (!decoded.hasMessage()
                    || !Bytes.wrap(app).equals(decoded.message().sender())) {
                throw CantonProofException.senderMismatch();
            }
            return;
        }
        if (!decoded.hasMessageReply()) {
            throw CantonProofException.wrongMessageType();
        }
        final var reply = decoded.messageReply();
        if (reply.status() == ClprMessageReplyStatus.APPLICATION_ERROR
                && reply.messageReplyData().length() == 0) {
            return;
        }
        checkReplyTarget(channelId, reply.messageId(), app, outbound);
    }

    /**
     * A REPLY must come from the app that the outbound DATA message {@code messageId} was sent to. When the
     * service holds no live DATA message under that id, {@code ClprService}'s reply ordering rejects the REPLY, so
     * there is nothing to bind here.
     */
    private static void checkReplyTarget(
            final byte[] channelId, final long messageId, final byte[] app, final OutboundLookup outbound) {
        final ClprMessagePayload stored = outbound.find(channelId, messageId);
        if (stored == null || !stored.hasMessage()) {
            return;
        }
        if (!Bytes.wrap(app).equals(stored.message().targetApplication())) {
            throw CantonProofException.replyTargetMismatch();
        }
    }

    /**
     * Whether the payload is a DATA ({@code true}) or REPLY ({@code false}) message: exactly one LEN field numbered
     * 1 or 2 that spans the payload, as trailing bytes would be hashed into the running hash but ignored by a
     * oneof decode.
     */
    private static boolean messageIsData(final byte[] payload) {
        int i = 0;
        long tag = 0;
        for (int shift = 0; ; shift += 7) {
            if (i >= payload.length || shift > 63) {
                throw CantonProofException.cantonProtoMalformed("payload tag runs past range");
            }
            final int c = payload[i++] & 0xff;
            tag |= (long) (c & 0x7f) << shift;
            if ((c & 0x80) == 0) {
                break;
            }
        }
        long len = 0;
        for (int shift = 0; ; shift += 7) {
            if (tag >>> 3 > 2 || (tag & 7) != CantonProto.WIRE_LEN || i >= payload.length || shift > 63) {
                throw CantonProofException.wrongMessageType();
            }
            final int c = payload[i++] & 0xff;
            len |= (long) (c & 0x7f) << shift;
            if ((c & 0x80) == 0) {
                break;
            }
        }
        final long field = tag >>> 3;
        if (field != 1 && field != 2 || len < 0 || len != payload.length - i) {
            throw CantonProofException.wrongMessageType();
        }
        return field == 1;
    }
}
