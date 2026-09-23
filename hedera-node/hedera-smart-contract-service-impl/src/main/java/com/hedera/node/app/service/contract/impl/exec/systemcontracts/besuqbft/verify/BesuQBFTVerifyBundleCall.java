// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.absentMetadataTuple;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.manifestStructTuple;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
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
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code verifyBundle(bytes bundlePayload, bytes trustAnchor) returns (bytes)} for
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
 * <p>Decoding and Merkle-proof verification are delegated to
 * {@link BesuQbftVerifier#verifyBundle(byte[], byte[])}; the verifier returns the verbatim
 * {@code ClprBundleContent} bytes from the proof's final RLP item, which this call ABI-encodes
 * and returns as the system contract's {@code (bytes)} result.
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

    private final byte[] bundlePayload;
    private final byte[] trustAnchor;

    @Nullable
    private final byte[] channelContext;

    public BesuQBFTVerifyBundleCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor) {
        super(gasCalculator, enhancement, true);
        this.bundlePayload = requireNonNull(bundlePayload);
        this.trustAnchor = requireNonNull(trustAnchor);
        this.channelContext = null;
    }

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
        final var trustAnchorBytes = Bytes.wrap(trustAnchor);

        log.info(
                "verifyBundle (QBFT) ENTER: bundlePayload={} bytes, trustAnchor={}, gasRequirement={}",
                bundlePayload.length,
                trustAnchorBytes,
                GAS_REQUIREMENT);

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
            log.warn("verifyBundle (QBFT): malformed trustAnchor envelope {} ({})", trustAnchorBytes, e.getMessage());
            return fail();
        }

        log.info(
                "verifyBundle (QBFT) trustAnchor decoded: trustedValidatorSet={}, trustedClprService={}, trustedClprServiceCodeHash={}",
                Bytes.wrap(trustedValidatorSetEncoded),
                Bytes.wrap(trustedClprServiceAddress20),
                Bytes.wrap(trustedClprServiceCodeHash32));

        final var verifier = new BesuQbftVerifier(
                new BesuQbftVerifier.Config(trustedClprServiceAddress20, trustedClprServiceCodeHash32, EPOCH_LENGTH));

        final BesuQbftVerifier.VerifiedBundle verified;
        try {
            verified = verifier.verifyBundle(bundlePayload, trustedValidatorSetEncoded);
        } catch (final ProofException e) {
            log.warn("verifyBundle (QBFT): proof rejected for trustAnchor {}: {}", trustAnchorBytes, e.getMessage());
            return fail();
        } catch (final Exception e) {
            log.warn("verifyBundle (QBFT): unexpected error for trustAnchor {}", trustAnchorBytes, e);
            return fail();
        }

        final var bundleContentBytes = verified.bundleContentBytes();

        // Manifest-only recovery bundle (spec §8.1.4): the verifier proved an endpoint manifest with no
        // bundle content. Accepted only on the V3 (endpoint-manifest-enabled) path so the CLPR Service
        // can apply the manifest update out-of-band; with the feature off this stays a hard rejection.
        if (bundleContentBytes.length == 0) {
            final boolean manifestEnabled =
                    configOf(frame).getConfigData(ClprConfig.class).endpointManifestEnabled();
            if (manifestEnabled && verified.newEndpointManifestBytes().length > 0) {
                return manifestOnlySuccess(verified.newEndpointManifestBytes());
            }
            return fail();
        }

        final ClprBundleContent parsedContent;
        try {
            parsedContent = ClprBundleContent.PROTOBUF.parse(
                    Bytes.wrap(bundleContentBytes).toReadableSequentialData());
            requireNonNull(parsedContent);
        } catch (final Exception e) {
            log.warn(
                    "verifyBundle (QBFT): inner bytes are not a valid ClprBundleContent for trustAnchor {}",
                    trustAnchorBytes,
                    e);
            return fail();
        }

        // When epoch headers advanced the trust anchor, augment the ClprBundleContent with the new
        // full trust anchor so ClprSubmitBundleHandler can persist the update.
        final byte[] finalBundleContentBytes;
        if (verified.newTrustAnchor().length > 0) {
            // QBFT epoch rotation replaces only the validator address; the service contract address
            // and code hash are stable across epochs and carry forward from the current trust anchor.
            final byte[] newFullTrustAnchor = encodeFullTrustAnchor(
                    verified.newTrustAnchor(), trustedClprServiceAddress20, trustedClprServiceCodeHash32);
            finalBundleContentBytes =
                    augmentWithNewTrustAnchor(parsedContent, newFullTrustAnchor, verified.newTrustAnchorId());
            log.info(
                    "verifyBundle (QBFT): trust anchor advanced to {} epochId=0x{}",
                    Bytes.wrap(verified.newTrustAnchor()),
                    HexFormat.of().formatHex(verified.newTrustAnchorId()));
        } else {
            finalBundleContentBytes = bundleContentBytes;
        }

        log.info(
                "verifyBundle (QBFT) EXIT: SUCCESS trustAnchor={} blockHash={} content={} bytes",
                trustAnchorBytes,
                Bytes.wrap(verified.blockHash32()),
                finalBundleContentBytes.length);
        if (channelContext != null) {
            final boolean manifestEnabled =
                    configOf(frame).getConfigData(ClprConfig.class).endpointManifestEnabled();
            return v2Success(finalBundleContentBytes, verified.newEndpointManifestBytes(), manifestEnabled);
        }
        return gasOnly(
                successResult(
                        BesuQBFTVerifyBundleTranslator.VERIFY_BUNDLE
                                .getOutputs()
                                .encode(Tuple.singleton(finalBundleContentBytes)),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
    }

    @NonNull
    private PricedResult v2Success(
            @NonNull final byte[] finalBundleContentBytes,
            @NonNull final byte[] newEndpointManifestBytes,
            final boolean manifestEnabled) {
        final ClprBundleContent outContent;
        try {
            outContent = ClprBundleContent.PROTOBUF.parse(
                    Bytes.wrap(finalBundleContentBytes).toReadableSequentialData());
        } catch (final Exception e) {
            log.warn("verifyBundle V2 (QBFT): failed to parse final bundle content", e);
            return fail();
        }
        final ClprQueueMetadata meta = outContent.metadataOrElse(ClprQueueMetadata.DEFAULT);
        final Tuple metaTuple = Tuple.of(
                BigInteger.valueOf(meta.nextMessageId()),
                meta.sentRunningHash().toByteArray(),
                BigInteger.valueOf(meta.receivedMessageId()),
                meta.receivedRunningHash().toByteArray(),
                meta.status().protoOrdinal());
        final byte[][] messageBytes = outContent.messages().stream()
                .map(msg -> ClprMessagePayload.PROTOBUF.toBytes(msg).toByteArray())
                .toArray(byte[][]::new);
        final byte[] newTrustAnchor = outContent.newTrustAnchor().toByteArray();
        final byte[] newTrustAnchorId = outContent.newTrustAnchorId().toByteArray();
        if (manifestEnabled) {
            // §4.2 Step 1b: the QBFT verifier extracts the manifest into newEndpointManifestBytes;
            // append it as the 5th member (empty bytes → version 0 = absent).
            ClprEndpointManifest manifest = ClprEndpointManifest.DEFAULT;
            if (newEndpointManifestBytes.length > 0) {
                try {
                    manifest = ClprEndpointManifest.PROTOBUF.parse(
                            Bytes.wrap(newEndpointManifestBytes).toReadableSequentialData());
                } catch (final Exception e) {
                    log.warn("verifyBundle V3 (QBFT): newEndpointManifestBytes are not a ClprEndpointManifest", e);
                    return fail();
                }
            }
            return gasOnly(
                    successResult(
                            VERIFY_BUNDLE_V3_RETURN.encode(Tuple.of(
                                    metaTuple,
                                    messageBytes,
                                    newTrustAnchor,
                                    newTrustAnchorId,
                                    manifestStructTuple(manifest))),
                            GAS_REQUIREMENT),
                    SUCCESS,
                    true);
        }
        return gasOnly(
                successResult(
                        BesuQBFTVerifyBundleTranslator.VERIFY_BUNDLE_V2
                                .getOutputs()
                                .encode(Tuple.of(metaTuple, messageBytes, newTrustAnchor, newTrustAnchorId)),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
    }

    /**
     * V3 success return for a manifest-only recovery bundle (spec §8.1.4): the endpoint manifest with an
     * empty message set and no trust-anchor rotation. Metadata is signalled absent via a zero
     * {@code nextMessageId} sentinel (a normal bundle's is always {@code >= 1}), which
     * {@link com.hedera.node.app.service.clpr.impl.verifier.EvmClprVerifier} decodes to a null metadata so
     * {@code ClprSubmitBundleHandler} takes its state-update-only path.
     */
    @NonNull
    private PricedResult manifestOnlySuccess(@NonNull final byte[] newEndpointManifestBytes) {
        final ClprEndpointManifest manifest;
        try {
            manifest = ClprEndpointManifest.PROTOBUF.parse(
                    Bytes.wrap(newEndpointManifestBytes).toReadableSequentialData());
        } catch (final Exception e) {
            log.warn("verifyBundle (QBFT): manifest-only recovery bytes are not a ClprEndpointManifest", e);
            return fail();
        }
        final Tuple absentMetadata = absentMetadataTuple();
        return gasOnly(
                successResult(
                        VERIFY_BUNDLE_V3_RETURN.encode(Tuple.of(
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

    /**
     * Augments a parsed {@link ClprBundleContent} with the new trust anchor fields from an epoch
     * transition. Returns the re-serialized bytes.
     *
     * <p>Package-private for testing.</p>
     */
    @NonNull
    static byte[] augmentWithNewTrustAnchor(
            @NonNull final ClprBundleContent base,
            @NonNull final byte[] newFullTrustAnchorRlp,
            @NonNull final byte[] newTrustAnchorIdBytes) {
        final ClprBundleContent augmented = base.copyBuilder()
                .newTrustAnchor(Bytes.wrap(newFullTrustAnchorRlp))
                .newTrustAnchorId(Bytes.wrap(newTrustAnchorIdBytes))
                .build();
        return ClprBundleContent.PROTOBUF.toBytes(augmented).toByteArray();
    }

    @NonNull
    private PricedResult fail() {
        return gasOnly(
                ordinalRevertResult(CLPR_BUNDLE_VERIFICATION_FAILED, GAS_REQUIREMENT),
                CLPR_BUNDLE_VERIFICATION_FAILED,
                true);
    }
}
