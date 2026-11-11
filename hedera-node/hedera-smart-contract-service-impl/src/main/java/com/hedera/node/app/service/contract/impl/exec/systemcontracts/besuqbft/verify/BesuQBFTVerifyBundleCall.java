// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.VERIFY_BUNDLE_RETURN;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.absentMetadataTuple;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.manifestStructTuple;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.metadataTuple;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.BesuQbftVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code verifyBundle(bytes bundlePayload, bytes trustAnchor, bytes channelContext)} for
 * the Besu QBFT verifier system contract (EVM address {@code 0x16f}).
 *
 * <p>The {@code bundlePayload} is the RLP-encoded structure produced by
 * {@code QbftBundleConstructor.PayloadRlpEncoder} in the {@code clpr-evm-endpoint} repo
 * (5-element top-level list: {@code [blockHeader, evmAccount, accountProof[],
 * storageProof[{key,value,proof[]}], bundleContent]}).
 *
 * <p>The {@code trustAnchor} is an RLP list of exactly three byte-string items:
 * <pre>
 *   RLP([
 *     encodedValidatorSet,             // RLP([addr1, ..., addrN]) embedded as bytes
 *     trustedClprServiceAddress,       // 20 bytes
 *     trustedClprServiceCodeHash       // 32 bytes
 *   ])
 * </pre>
 * <ul>
 *   <li>{@code encodedValidatorSet} — the canonically-sorted, RLP-encoded list of QBFT validator
 *       addresses whose committed seals must reach quorum on the block header.</li>
 *   <li>{@code trustedClprServiceAddress} — the 20-byte address of the CLPR service contract on
 *       the peer ledger; pins which account the state proof must resolve to.</li>
 *   <li>{@code trustedClprServiceCodeHash} — the expected {@code codeHash} of the proven contract
 *       account; defends against a state-trie spoof where attacker-controlled storage has been
 *       wired at the canonical address.</li>
 * </ul>
 *
 * <p>The {@code channelContext} is {@code channelId(32) || serviceAddress(20)}, as returned by
 * {@code verifyConfig}. Its service address must equal the trust anchor's, and its channel id binds the
 * proven storage slots to the channel.
 *
 * <p>Decoding and Merkle-proof verification are delegated to
 * {@link BesuQbftVerifier#verifyBundle(byte[], byte[], byte[])}. Everything this call returns comes from
 * the proof: the relayed queue metadata must equal the proven Channel slots, and a new trust anchor is
 * returned only when the verifier proved a rotation. The proven manifest is appended as the fifth tuple
 * member.
 */
public class BesuQBFTVerifyBundleCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(BesuQBFTVerifyBundleCall.class);
    private static final long GAS_REQUIREMENT = 100_000L;
    private static final long EPOCH_LENGTH =
            30_000L; // matches relay config default; configure per-channel in a follow-up

    private static final int ADDRESS_LENGTH = 20;
    private static final int CODE_HASH_LENGTH = 32;
    private static final int TRUST_ANCHOR_ITEM_COUNT = 3;
    private static final int TA_VALIDATOR_SET_INDEX = 0;
    private static final int TA_SERVICE_ADDRESS_INDEX = 1;
    private static final int TA_SERVICE_CODE_HASH_INDEX = 2;
    private static final int CHANNEL_ID_LENGTH = 32;
    private static final int CHANNEL_CONTEXT_LENGTH = CHANNEL_ID_LENGTH + ADDRESS_LENGTH;

    private final byte[] bundlePayload;
    private final byte[] trustAnchor;
    private final byte[] channelContext;

    public BesuQBFTVerifyBundleCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor,
            @NonNull final byte[] channelContext) {
        super(gasCalculator, enhancement, true);
        this.bundlePayload = requireNonNull(bundlePayload);
        this.trustAnchor = requireNonNull(trustAnchor);
        this.channelContext = requireNonNull(channelContext);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        log.debug(
                "verifyBundle (QBFT) ENTER: bundlePayload={} bytes, trustAnchor={} bytes, channelContext={} bytes",
                bundlePayload.length,
                trustAnchor.length,
                channelContext.length);

        // Decode the trust-anchor envelope into the encoded validator set (used to authenticate
        // QBFT committed seals) and the CLPR-service-address / code-hash pair (used to pin the
        // account proof to the correct contract on the peer ledger).
        final byte[] trustedValidatorSetEncoded;
        final byte[] trustedClprServiceAddress20;
        final byte[] trustedClprServiceCodeHash32;
        try {
            final byte[][] items = decodeRlpListOfByteStrings(trustAnchor, TRUST_ANCHOR_ITEM_COUNT);
            trustedValidatorSetEncoded = items[TA_VALIDATOR_SET_INDEX];
            trustedClprServiceAddress20 =
                    requireFixedLength(items[TA_SERVICE_ADDRESS_INDEX], ADDRESS_LENGTH, "trustedClprServiceAddress");
            trustedClprServiceCodeHash32 = requireFixedLength(
                    items[TA_SERVICE_CODE_HASH_INDEX], CODE_HASH_LENGTH, "trustedClprServiceCodeHash");
        } catch (final Exception e) {
            log.warn("verifyBundle (QBFT): malformed trustAnchor envelope ({})", e.getMessage());
            return fail();
        }

        // The channel context must name the same peer service as the trust anchor; its channel id binds
        // the proven Channel storage slots to this channel.
        if (channelContext.length != CHANNEL_CONTEXT_LENGTH
                || !Arrays.equals(
                        channelContext,
                        CHANNEL_ID_LENGTH,
                        CHANNEL_CONTEXT_LENGTH,
                        trustedClprServiceAddress20,
                        0,
                        ADDRESS_LENGTH)) {
            log.warn(
                    "verifyBundle (QBFT): channelContext ({} bytes) does not match the trusted service address",
                    channelContext.length);
            return fail();
        }
        final byte[] channelId32 = Arrays.copyOfRange(channelContext, 0, CHANNEL_ID_LENGTH);

        final var verifier = new BesuQbftVerifier(
                new BesuQbftVerifier.Config(trustedClprServiceAddress20, trustedClprServiceCodeHash32, EPOCH_LENGTH));

        final BesuQbftVerifier.VerifiedBundle verified;
        try {
            verified = verifier.verifyBundle(bundlePayload, trustedValidatorSetEncoded, channelId32);
        } catch (final ProofException e) {
            log.warn("verifyBundle (QBFT): proof rejected: {}", e.getMessage());
            return fail();
        } catch (final Exception e) {
            log.warn("verifyBundle (QBFT): unexpected error", e);
            return fail();
        }

        final var bundleContentBytes = verified.bundleContentBytes();

        // Manifest-only recovery bundle (spec §8.1.4): the verifier proved an endpoint manifest with no
        // bundle content. Accepted so the CLPR Service can apply the manifest update out-of-band.
        if (bundleContentBytes.length == 0) {
            if (verified.newEndpointManifestBytes().length > 0) {
                return manifestOnlySuccess(verified.newEndpointManifestBytes());
            }
            return fail();
        }

        final ClprBundleContent parsedContent;
        try {
            parsedContent = ClprBundleContent.PROTOBUF.parseStrict(
                    Bytes.wrap(bundleContentBytes).toReadableSequentialData());
            requireNonNull(parsedContent);
        } catch (final Exception e) {
            log.warn("verifyBundle (QBFT): inner bytes are not a valid ClprBundleContent", e);
            return fail();
        }

        if (!relayedMetadataMatchesProven(parsedContent, verified.queueMetadata())) {
            return fail();
        }

        // A trust anchor comes only from a rotation the verifier proved. The service contract address and
        // code hash are stable across rotations and carry forward from the current trust anchor.
        final byte[] provenTrustAnchor = verified.newTrustAnchor().length > 0
                ? encodeFullTrustAnchor(
                        verified.newTrustAnchor(), trustedClprServiceAddress20, trustedClprServiceCodeHash32)
                : null;
        final ClprBundleContent reconciledContent =
                reconcileTrustAnchor(parsedContent, provenTrustAnchor, verified.newTrustAnchorId());
        if (reconciledContent == null) {
            return fail();
        }
        final byte[] finalBundleContentBytes =
                ClprBundleContent.PROTOBUF.toBytes(reconciledContent).toByteArray();

        log.debug(
                "verifyBundle (QBFT) EXIT: SUCCESS blockHash={} content={} bytes trustAnchorAdvanced={}",
                Bytes.wrap(verified.blockHash32()),
                finalBundleContentBytes.length,
                provenTrustAnchor != null);
        // Source endpointManifestVersion from the PROVEN queue metadata (SC-189 Channel offset 16), never from
        // the relayed content: a relay could otherwise inflate it to make the peer look up-to-date and suppress
        // our manifest re-pushes (spec §4.5, liveness).
        return bundleSuccess(
                finalBundleContentBytes,
                verified.newEndpointManifestBytes(),
                verified.queueMetadata().endpointManifestVersion());
    }

    /**
     * Returns {@code true} when the relayed queue metadata equals the queue state proven from the peer's
     * Channel storage slots. The relay carries the metadata in the bundle content, but only the proven
     * values may reach the CLPR Service: they drive the running-hash check and the ack, so a relayed value
     * that differs would let a relay inject messages.
     */
    private static boolean relayedMetadataMatchesProven(
            @NonNull final ClprBundleContent content, @NonNull final BesuQbftVerifier.QueueMetadata proven) {
        final ClprQueueMetadata metadata = content.metadata();
        if (metadata == null) {
            log.warn("verifyBundle (QBFT): bundle content metadata is missing");
            return false;
        }
        if (metadata.nextMessageId() != proven.nextMessageId()
                || metadata.receivedMessageId() != proven.receivedMessageId()
                || metadata.status().protoOrdinal() != proven.status()
                || !metadata.sentRunningHash().equals(Bytes.wrap(proven.sentRunningHash()))
                || !metadata.receivedRunningHash().equals(Bytes.wrap(proven.receivedRunningHash()))) {
            log.warn(
                    "verifyBundle (QBFT): bundle content metadata does not match proven queue state:"
                            + " content(nextMessageId={}, receivedMessageId={}, status={}),"
                            + " proven(nextMessageId={}, receivedMessageId={}, status={})",
                    metadata.nextMessageId(),
                    metadata.receivedMessageId(),
                    metadata.status().protoOrdinal(),
                    proven.nextMessageId(),
                    proven.receivedMessageId(),
                    proven.status());
            return false;
        }
        return true;
    }

    /**
     * Reconciles the trust anchor claimed in the bundle content with the one the verifier proved.
     *
     * <p>When the verifier proved a rotation, the proven anchor and id replace any claim; a claimed anchor
     * that differs from the proven one fails the bundle. When there is no proven rotation, the content must
     * not claim one.
     *
     * @param content the parsed bundle content
     * @param provenTrustAnchor the full proven successor anchor, or {@code null} when no rotation was proven
     * @param provenTrustAnchorId the proven successor anchor id (ignored when no rotation was proven)
     * @return the content carrying only the proven anchor, or {@code null} when the claim is not proven
     */
    @Nullable
    private static ClprBundleContent reconcileTrustAnchor(
            @NonNull final ClprBundleContent content,
            @Nullable final byte[] provenTrustAnchor,
            @NonNull final byte[] provenTrustAnchorId) {
        if (provenTrustAnchor == null) {
            if (content.newTrustAnchor().length() != 0
                    || content.newTrustAnchorId().length() != 0) {
                log.warn("verifyBundle (QBFT): content claims a trust anchor rotation that was not proven");
                return null;
            }
            return content;
        }
        final var provenAnchor = Bytes.wrap(provenTrustAnchor);
        if (content.newTrustAnchor().length() != 0 && !content.newTrustAnchor().equals(provenAnchor)) {
            log.warn("verifyBundle (QBFT): content new_trust_anchor differs from the proven successor");
            return null;
        }
        return content.copyBuilder()
                .newTrustAnchor(provenAnchor)
                .newTrustAnchorId(Bytes.wrap(provenTrustAnchorId))
                .build();
    }

    @NonNull
    private PricedResult bundleSuccess(
            @NonNull final byte[] finalBundleContentBytes,
            @NonNull final byte[] newEndpointManifestBytes,
            final long provenEndpointManifestVersion) {
        final ClprBundleContent outContent;
        try {
            outContent = ClprBundleContent.PROTOBUF.parseStrict(
                    Bytes.wrap(finalBundleContentBytes).toReadableSequentialData());
        } catch (final Exception e) {
            log.warn("verifyBundle (QBFT): failed to parse final bundle content", e);
            return fail();
        }
        final ClprQueueMetadata meta = outContent.metadataOrElse(ClprQueueMetadata.DEFAULT);
        final Tuple metaTuple = metadataTuple(meta, provenEndpointManifestVersion);
        if (metaTuple == null) {
            log.warn("verifyBundle (QBFT): queue metadata is not ABI-encodable");
            return fail();
        }
        final byte[][] messageBytes = outContent.messages().stream()
                .map(msg -> ClprMessagePayload.PROTOBUF.toBytes(msg).toByteArray())
                .toArray(byte[][]::new);
        final byte[] newTrustAnchor = outContent.newTrustAnchor().toByteArray();
        final byte[] newTrustAnchorId = outContent.newTrustAnchorId().toByteArray();
        // §4.2 Step 1b: the QBFT verifier extracts the manifest into newEndpointManifestBytes;
        // append it as the 5th member (empty bytes → version 0 = absent).
        ClprEndpointManifest manifest = ClprEndpointManifest.DEFAULT;
        if (newEndpointManifestBytes.length > 0) {
            try {
                manifest = ClprEndpointManifest.PROTOBUF.parseStrict(
                        Bytes.wrap(newEndpointManifestBytes).toReadableSequentialData());
            } catch (final Exception e) {
                log.warn("verifyBundleWithManifest (QBFT): newEndpointManifestBytes are not a ClprEndpointManifest", e);
                return fail();
            }
        }
        return gasOnly(
                successResult(
                        VERIFY_BUNDLE_RETURN.encode(Tuple.of(
                                metaTuple,
                                messageBytes,
                                newTrustAnchor,
                                newTrustAnchorId,
                                manifestStructTuple(manifest))),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
    }

    /**
     * Manifest-aware success return for a manifest-only recovery bundle (spec §8.1.4): the endpoint manifest with an
     * empty message set and no trust-anchor rotation. Metadata is signalled absent via a zero
     * {@code nextMessageId} sentinel (a normal bundle's is always {@code >= 1}), which
     * {@link com.hedera.node.app.service.clpr.impl.verifier.EvmClprVerifier} decodes to a null metadata so
     * {@code ClprSubmitBundleHandler} takes its state-update-only path.
     */
    @NonNull
    private PricedResult manifestOnlySuccess(@NonNull final byte[] newEndpointManifestBytes) {
        final ClprEndpointManifest manifest;
        try {
            manifest = ClprEndpointManifest.PROTOBUF.parseStrict(
                    Bytes.wrap(newEndpointManifestBytes).toReadableSequentialData());
        } catch (final Exception e) {
            log.warn("verifyBundle (QBFT): manifest-only recovery bytes are not a ClprEndpointManifest", e);
            return fail();
        }
        final Tuple absentMetadata = absentMetadataTuple();
        return gasOnly(
                successResult(
                        VERIFY_BUNDLE_RETURN.encode(Tuple.of(
                                absentMetadata,
                                new byte[0][],
                                new byte[0],
                                new byte[0],
                                manifestStructTuple(manifest))),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
    }

    /**
     * Decode an RLP list of {@code expectedItemCount} byte-string items into a {@code byte[][]}.
     * Rejects nested lists, trailing bytes, and any input that does not exactly match the shape.
     */
    @NonNull
    private static byte[][] decodeRlpListOfByteStrings(@NonNull final byte[] input, final int expectedItemCount) {
        if (input.length == 0) {
            throw new IllegalArgumentException("empty RLP input");
        }
        int cursor = 0;
        final int listPrefix = input[cursor++] & 0xff;
        final int payloadLen;
        if (listPrefix >= 0xc0 && listPrefix <= 0xf7) {
            payloadLen = listPrefix - 0xc0;
        } else if (listPrefix >= 0xf8 && listPrefix <= 0xff) {
            final int lenOfLen = listPrefix - 0xf7;
            if (cursor + lenOfLen > input.length) {
                throw new IllegalArgumentException("truncated RLP list length");
            }
            int len = 0;
            for (int i = 0; i < lenOfLen; i++) {
                len = (len << 8) | (input[cursor++] & 0xff);
            }
            payloadLen = len;
        } else {
            throw new IllegalArgumentException(
                    "not an RLP list (first byte 0x" + Integer.toHexString(listPrefix) + ")");
        }
        final int listEnd = cursor + payloadLen;
        if (listEnd != input.length) {
            throw new IllegalArgumentException("trailing bytes after RLP list");
        }

        final byte[][] items = new byte[expectedItemCount][];
        for (int i = 0; i < expectedItemCount; i++) {
            if (cursor >= listEnd) {
                throw new IllegalArgumentException("RLP list has fewer than " + expectedItemCount + " items");
            }
            final int prefix = input[cursor++] & 0xff;
            final byte[] item;
            if (prefix < 0x80) {
                item = new byte[] {(byte) prefix};
            } else if (prefix <= 0xb7) {
                final int len = prefix - 0x80;
                if (cursor + len > listEnd) {
                    throw new IllegalArgumentException("truncated RLP byte-string item");
                }
                item = new byte[len];
                System.arraycopy(input, cursor, item, 0, len);
                cursor += len;
            } else if (prefix <= 0xbf) {
                final int lenOfLen = prefix - 0xb7;
                if (cursor + lenOfLen > listEnd) {
                    throw new IllegalArgumentException("truncated RLP byte-string length");
                }
                int len = 0;
                for (int j = 0; j < lenOfLen; j++) {
                    len = (len << 8) | (input[cursor++] & 0xff);
                }
                if (cursor + len > listEnd) {
                    throw new IllegalArgumentException("truncated RLP byte-string item");
                }
                item = new byte[len];
                System.arraycopy(input, cursor, item, 0, len);
                cursor += len;
            } else {
                throw new IllegalArgumentException(
                        "nested RLP list at item " + i + " (trustAnchor expects byte strings only)");
            }
            items[i] = item;
        }
        if (cursor != listEnd) {
            throw new IllegalArgumentException("RLP list has more than " + expectedItemCount + " items");
        }
        return items;
    }

    @NonNull
    private static byte[] requireFixedLength(@NonNull final byte[] item, final int expectedLength, final String name) {
        if (item.length != expectedLength) {
            throw new IllegalArgumentException(name + " must be " + expectedLength + " bytes, got " + item.length);
        }
        return item;
    }

    /**
     * Re-encodes a full QBFT trust anchor as
     * {@code RLP([encodedValidatorSet, serviceAddr20, codeHash32])}.
     *
     * <p>The first item is the canonically-sorted, RLP-encoded validator set produced by
     * {@link BesuQbftVerifier#encodeValidatorSet}; the last two are fixed-length
     * byte strings. All three are embedded as opaque byte strings in the outer RLP list.
     */
    @NonNull
    private static byte[] encodeFullTrustAnchor(
            @NonNull final byte[] encodedValidatorSet,
            @NonNull final byte[] serviceAddr20,
            @NonNull final byte[] codeHash32) {
        return Rlp.encodeList(List.of(
                Rlp.encodeBytes(encodedValidatorSet), Rlp.encodeBytes(serviceAddr20), Rlp.encodeBytes(codeHash32)));
    }

    @NonNull
    private PricedResult fail() {
        return gasOnly(
                ordinalRevertResult(CLPR_BUNDLE_VERIFICATION_FAILED, GAS_REQUIREMENT),
                CLPR_BUNDLE_VERIFICATION_FAILED,
                true);
    }
}
