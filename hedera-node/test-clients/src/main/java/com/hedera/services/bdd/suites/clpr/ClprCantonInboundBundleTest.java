// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCreateChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCreateConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toBundleProofBytes;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprBundleResponse;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.EventualBlockStreamAssertion;
import com.hedera.services.bdd.suites.clpr.canton.CantonProofs;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Single-node embedded tests that a bundle received from a peer over the CLPR sync stream is submitted to consensus
 * as a {@code ClprSubmitBundle} and handled with {@code SUCCESS}, checked in the block stream.
 *
 * <p>Like {@link ClprOrchestratorSubmitTest}, each test opens a {@code ClprSyncWorkflow#openStreamingSync} session
 * and feeds it the peer's {@code ClprStreamingSyncPayload}, the production receive path: the session validates the
 * bundle locally ({@code ClprVerifier#verifyBundleLocally}) and, if it passes, gossips a node-paid
 * {@code ClprSubmitBundle}. One test uses a Canton channel (built-in verifier {@code 0x173}); the other, a control,
 * uses a deployed {@code ClprPassThroughVerifier}.
 *
 * <p>Two more Canton tests deliver a REPLY to a DATA message this ledger sent on the channel. The Canton verifier
 * accepts a REPLY only if the outbound DATA message it answers was sent to the channel's app, which it reads from this
 * ledger's outbound message queue, in local prevalidation (a {@code ContractCallLocal}) as at consensus. A REPLY to a
 * message sent to the app must be submitted and succeed; a REPLY to a message sent to another target must be dropped
 * before submission.
 */
@Tag(CLPR)
public class ClprCantonInboundBundleTest {
    private static final Logger log = LogManager.getLogger(ClprCantonInboundBundleTest.class);

    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(30);
    private static final String VERIFIER_CONTRACT = "ClprPassThroughVerifier";
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    private static final long MIN_LOCKED_STAKE = 100L;
    private static final long NODE_FUNDING = 100_000_000_000L;
    private static final String CLPR_CONTRACT = "ClprSystemContract";
    private static final long SEND_GAS = 500_000L;
    /** Blocks that must close after a dropped bundle is fed, with no ClprSubmitBundle for its channel. */
    private static final int BLOCKS_WITHOUT_SUBMISSION = 5;

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS, requirement = PROPERTY_OVERRIDES)
    @DisplayName("Inbound Canton bundle is submitted to consensus and succeeds")
    final Stream<DynamicTest> inboundCantonBundleIsSubmittedToConsensus() {
        final AtomicReference<ClprChannelCrypto> channel = new AtomicReference<>();
        return hapiTest(
                sourcingContextual(spec -> {
                    final var localChain = localChainId(spec);
                    final var crypto = new ClprChannelCrypto(
                            localChain,
                            asSolidityAddress(CantonProofs.CANTON_VERIFIER),
                            CantonProofs.PEER_CHAIN,
                            CantonProofs.serviceAddress());
                    channel.set(crypto);
                    return blockingOrder(setUpCantonChannel(crypto, localChain));
                }),
                submitBundleMustSucceedInBlockStream(channel),
                sourcingContextual(spec -> feedInboundBundle(
                        channel.get(),
                        Bytes.wrap(CantonProofs.singleMessageBundleProof(
                                channel.get().channelId(), CantonProofs.probePayload())))));
    }

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS, requirement = PROPERTY_OVERRIDES)
    @DisplayName("Inbound Canton REPLY to a message sent to the channel's app is submitted to consensus and succeeds")
    final Stream<DynamicTest> inboundCantonReplyToTheAppIsSubmittedToConsensus() {
        final AtomicReference<ClprChannelCrypto> channel = new AtomicReference<>();
        final byte[] app = CantonProofs.APP.getBytes(StandardCharsets.UTF_8);
        return hapiTest(
                sourcingContextual(spec -> {
                    final var crypto = cantonChannelCrypto(spec);
                    channel.set(crypto);
                    return blockingOrder(setUpCantonChannelWithOutboundMessage(crypto, localChainId(spec), app));
                }),
                submitBundleMustSucceedInBlockStream(channel),
                sourcingContextual(spec -> feedInboundBundle(channel.get(), cantonReplyToFirstMessage(channel.get()))));
    }

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS, requirement = PROPERTY_OVERRIDES)
    @DisplayName("Inbound Canton REPLY to a message sent to another target is dropped before submission")
    final Stream<DynamicTest> inboundCantonReplyToAnotherTargetIsDroppedBeforeSubmission() {
        final AtomicReference<ClprChannelCrypto> channel = new AtomicReference<>();
        final AtomicBoolean fed = new AtomicBoolean();
        // A valid Canton party, but not the channel's app: the app could answer it only by impersonating it
        final byte[] otherTarget = ("other::1220" + "ef".repeat(32)).getBytes(StandardCharsets.UTF_8);
        return hapiTest(
                sourcingContextual(spec -> {
                    final var crypto = cantonChannelCrypto(spec);
                    channel.set(crypto);
                    return blockingOrder(
                            setUpCantonChannelWithOutboundMessage(crypto, localChainId(spec), otherTarget));
                }),
                noSubmitBundleInBlockStreamAfter(channel, fed),
                sourcingContextual(spec -> blockingOrder(
                        feedInboundBundle(channel.get(), cantonReplyToFirstMessage(channel.get())),
                        withOpContext((ignore, opLog) -> fed.set(true)))));
    }

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS, requirement = PROPERTY_OVERRIDES)
    @DisplayName("Inbound pass-through (EVM verifier) bundle is submitted to consensus and succeeds")
    final Stream<DynamicTest> inboundPassThroughBundleIsSubmittedToConsensus() {
        final AtomicReference<ClprChannelCrypto> channel = new AtomicReference<>();
        return hapiTest(
                ClprChannelCrypto.withVerifier(VERIFIER_CONTRACT, "hiero:embedded", CLPR_SERVICE_ADDRESS_20, crypto -> {
                    channel.set(crypto);
                    return blockingOrder(
                            overriding("clpr.minLockedStake", String.valueOf(MIN_LOCKED_STAKE)),
                            cryptoTransfer(tinyBarsFromTo(GENESIS, "3", NODE_FUNDING)),
                            clprUpdateLedgerConfiguration()
                                    .configuration(passThroughLedgerConfig())
                                    .payingWith(GENESIS),
                            uploadInitCode(CONNECTOR_CONTRACT),
                            contractCreate(CONNECTOR_CONTRACT),
                            clprCreateChannel()
                                    .channelId(crypto.channelId())
                                    .salt(crypto.salt())
                                    .peerVerifier(crypto.peerVerifier())
                                    .verifierContract(VERIFIER_CONTRACT)
                                    .configProofBytes(passThroughConfigProof())
                                    .payingWith(GENESIS),
                            clprCreateConnector()
                                    .connectorId(crypto.connectorId())
                                    .ed25519Identity(crypto.seed())
                                    .salt(crypto.connectorSalt())
                                    .channelId(crypto.channelId())
                                    .connectorContract(CONNECTOR_CONTRACT)
                                    .adminKeyName(GENESIS)
                                    .lockedStake(MIN_LOCKED_STAKE)
                                    .payingWith(GENESIS),
                            assertChannelActive(crypto));
                }),
                submitBundleMustSucceedInBlockStream(channel),
                sourcingContextual(spec -> {
                    final var probe = ClprMessagePayload.newBuilder()
                            .setMessage(ClprMessage.newBuilder()
                                    .setConnectorId(ByteString.copyFrom(new byte[32]))
                                    .setTargetApplication(ByteString.copyFrom(new byte[20]))
                                    .setSender(ByteString.copyFrom(new byte[20]))
                                    .setMessageData(ByteString.copyFromUtf8("probe")))
                            .build();
                    final var bundleBytes = Bytes.wrap(toBundleProofBytes(
                            com.hederahashgraph.api.proto.java.ClprChannelStatus.ACTIVE,
                            0L,
                            0L,
                            new byte[32],
                            List.of(probe)));
                    return feedInboundBundle(channel.get(), bundleBytes);
                }));
    }

    /** Funds the node account, configures this ledger, opens an ACTIVE Canton channel and checks it. */
    private static SpecOperation[] setUpCantonChannel(final ClprChannelCrypto crypto, final String localChain) {
        return new SpecOperation[] {
            // The inbound ClprSubmitBundle is node-paid, from the self node's account (account 3 in embedded).
            cryptoTransfer(tinyBarsFromTo(GENESIS, "3", NODE_FUNDING)),
            clprUpdateLedgerConfiguration().configuration(localLedgerConfig()).payingWith(GENESIS),
            clprCreateChannel()
                    .channelId(crypto.channelId())
                    .salt(crypto.salt())
                    .peerVerifier(crypto.peerVerifier())
                    .verifierContractId(CantonProofs.CANTON_VERIFIER)
                    .configProofBytes(CantonProofs.configProof(crypto.channelId(), localChain))
                    .payingWith(GENESIS),
            assertChannelActive(crypto)
        };
    }

    private static ClprChannelCrypto cantonChannelCrypto(final HapiSpec spec) {
        return new ClprChannelCrypto(
                localChainId(spec),
                asSolidityAddress(CantonProofs.CANTON_VERIFIER),
                CantonProofs.PEER_CHAIN,
                CantonProofs.serviceAddress());
    }

    /**
     * Opens an ACTIVE Canton channel, registers a pass-through connector on it, and sends one DATA message (id 1) to
     * {@code target} through the CLPR system contract, then checks that the outbound queue holds it.
     */
    private static SpecOperation[] setUpCantonChannelWithOutboundMessage(
            final ClprChannelCrypto crypto, final String localChain, final byte[] target) {
        final var ops = new ArrayList<>(List.of(setUpCantonChannel(crypto, localChain)));
        ops.addAll(List.of(
                overriding("clpr.minLockedStake", String.valueOf(MIN_LOCKED_STAKE)),
                uploadInitCode(CONNECTOR_CONTRACT),
                contractCreate(CONNECTOR_CONTRACT),
                uploadInitCode(CLPR_CONTRACT),
                contractCreate(CLPR_CONTRACT),
                clprCreateConnector()
                        .connectorId(crypto.connectorId())
                        .ed25519Identity(crypto.seed())
                        .salt(crypto.connectorSalt())
                        .channelId(crypto.channelId())
                        .connectorContract(CONNECTOR_CONTRACT)
                        .adminKeyName(GENESIS)
                        .lockedStake(MIN_LOCKED_STAKE)
                        .payingWith(GENESIS),
                // sendMessage(channelId, connectorId, targetApplication, data)
                contractCall(
                                CLPR_CONTRACT,
                                "sendMessage",
                                crypto.channelId(),
                                crypto.connectorId(),
                                target,
                                "ping".getBytes(StandardCharsets.UTF_8))
                        .gas(SEND_GAS)
                        .payingWith(GENESIS),
                assertFirstOutboundMessageTargets(crypto, target)));
        return ops.toArray(SpecOperation[]::new);
    }

    /** Checks that outbound message 1 on the channel is a DATA message to {@code target}. */
    private static SpecOperation assertFirstOutboundMessageTargets(
            final ClprChannelCrypto crypto, final byte[] target) {
        return withOpContext((spec, opLog) -> {
            final var stored = spec.embeddedStateOrThrow()
                    .getReadableStates(ClprService.NAME)
                    .<ClprMessageKey, ClprMessageValue>get(MESSAGE_QUEUE_STATE_ID)
                    .get(ClprMessageKey.newBuilder()
                            .channelId(Bytes.wrap(crypto.channelId()))
                            .messageId(1L)
                            .build());
            requireNonNull(stored, "outbound message 1 not queued");
            assertArrayEquals(
                    target,
                    stored.payloadOrThrow().messageOrThrow().targetApplication().toByteArray(),
                    "outbound message 1 should target " + new String(target, StandardCharsets.UTF_8));
        });
    }

    /** The Canton app's SUCCESS REPLY, with data, to outbound message 1, after receiving it. */
    private static Bytes cantonReplyToFirstMessage(final ClprChannelCrypto crypto) {
        final var reply =
                CantonProofs.replyPayload(1L, ClprMessageReplyStatus.SUCCESS, "pong".getBytes(StandardCharsets.UTF_8));
        return Bytes.wrap(CantonProofs.replyBundleProof(crypto.channelId(), reply, 1L));
    }

    /** Proves the channel setup succeeded, so a later stream-assertion failure cannot come from the setup. */
    private static SpecOperation assertChannelActive(final ClprChannelCrypto crypto) {
        return withOpContext((spec, opLog) -> {
            final var conn = readChannel(spec, crypto);
            assertEquals(ClprChannelStatus.ACTIVE, conn.status(), "channel should be ACTIVE");
            assertEquals(0L, conn.receivedMessageId(), "no inbound messages yet");
        });
    }

    /** Mimics a peer's gRPC sync call: its one-shot request plus its bundle, fed to a new streaming session. */
    private static SpecOperation feedInboundBundle(final ClprChannelCrypto crypto, final Bytes bundlePayload) {
        return withOpContext((spec, opLog) -> {
            final var workflow = requireNonNull(
                    spec.embeddedHederaOrThrow().hedera().clprSyncWorkflow(),
                    "ClprSyncWorkflow not available — Hedera not started?");
            final var syncPayload = ClprStreamingSyncPayload.newBuilder()
                    .channelId(Bytes.wrap(crypto.channelId()))
                    .bundleRequest(ClprBundleRequest.DEFAULT)
                    .bundleResponse(ClprBundleResponse.newBuilder()
                            .bundlePayload(bundlePayload)
                            .build())
                    .build();
            workflow.openStreamingSync().onMessage(ClprStreamingSyncPayload.PROTOBUF.toBytes(syncPayload));
        });
    }

    /**
     * Passes once a block holds a {@code ClprSubmitBundle} for the channel whose result is {@code SUCCESS}; times out
     * after {@link #STREAM_TIMEOUT}, with background traffic so blocks keep closing meanwhile.
     */
    private static EventualBlockStreamAssertion submitBundleMustSucceedInBlockStream(
            final AtomicReference<ClprChannelCrypto> channel) {
        return EventualBlockStreamAssertion.eventuallyAssertingExplicitPass(
                        spec -> new SuccessfulSubmitBundle(
                                Bytes.wrap(channel.get().channelId())),
                        STREAM_TIMEOUT)
                .withBackgroundTraffic();
    }

    /**
     * Passes once {@link #BLOCKS_WITHOUT_SUBMISSION} blocks have closed after {@code fed} is set with no
     * {@code ClprSubmitBundle} for the channel, and fails as soon as one appears.
     */
    private static EventualBlockStreamAssertion noSubmitBundleInBlockStreamAfter(
            final AtomicReference<ClprChannelCrypto> channel, final AtomicBoolean fed) {
        return EventualBlockStreamAssertion.eventuallyAssertingExplicitPass(
                        spec -> new NoSubmitBundle(Bytes.wrap(channel.get().channelId()), fed), STREAM_TIMEOUT)
                .withBackgroundTraffic();
    }

    /** Fails on any {@code ClprSubmitBundle} for one channel; passes after enough blocks once the bundle is fed. */
    private static final class NoSubmitBundle implements BlockStreamAssertion {
        private final Bytes channelId;
        private final AtomicBoolean fed;
        private int blocksAfterFeed;

        private NoSubmitBundle(final Bytes channelId, final AtomicBoolean fed) {
            this.channelId = channelId;
            this.fed = fed;
        }

        @Override
        public synchronized boolean test(@NonNull final Block block) throws AssertionError {
            final boolean counts = fed.get();
            for (final var item : block.items()) {
                if (item.hasSignedTransaction()) {
                    final var body = bodyOf(item.signedTransactionOrThrow());
                    if (body != null
                            && body.hasClprSubmitBundle()
                            && channelId.equals(body.clprSubmitBundleOrThrow().channelId())) {
                        throw new AssertionError("a ClprSubmitBundle was submitted for channel " + channelId.toHex()
                                + ", but local prevalidation should have dropped the bundle");
                    }
                }
            }
            if (counts && ++blocksAfterFeed >= BLOCKS_WITHOUT_SUBMISSION) {
                log.info(
                        "Block stream check passed: {} blocks after the feed, no ClprSubmitBundle for channel {}",
                        blocksAfterFeed,
                        channelId.toHex());
                return true;
            }
            return false;
        }

        @Override
        public synchronized String toString() {
            return "NoClprSubmitBundle[channel " + channelId.toHex() + ", fed=" + fed.get() + ", blocksAfterFeed="
                    + blocksAfterFeed + "]";
        }
    }

    private static com.hedera.hapi.node.transaction.TransactionBody bodyOf(final Bytes signedTransaction) {
        try {
            return TransactionParts.from(signedTransaction).body();
        } catch (final RuntimeException ignore) {
            return null;
        }
    }

    /**
     * Scans each block for a {@code ClprSubmitBundle} on one channel followed by a {@code SUCCESS} result, and keeps
     * counts of what it scanned, which its {@link #toString()} (the timeout failure message) reports.
     */
    private static final class SuccessfulSubmitBundle implements BlockStreamAssertion {
        private final Bytes channelId;
        private int blocksSeen;
        private long firstBlock = -1;
        private long lastBlock = -1;
        private int submitBundlesAnyChannel;
        private int submitBundlesForChannel;
        private final List<String> resultsForChannel = new ArrayList<>();

        private SuccessfulSubmitBundle(final Bytes channelId) {
            this.channelId = channelId;
        }

        @Override
        public synchronized boolean test(@NonNull final Block block) {
            ++blocksSeen;
            boolean nextResultIsForChannel = false;
            for (final var item : block.items()) {
                switch (item.item().kind()) {
                    case BLOCK_HEADER -> {
                        final long number = item.blockHeaderOrThrow().number();
                        if (firstBlock < 0) {
                            firstBlock = number;
                        }
                        lastBlock = number;
                    }
                    case SIGNED_TRANSACTION ->
                        nextResultIsForChannel = isSubmitBundleForChannel(item.signedTransactionOrThrow());
                    case TRANSACTION_RESULT -> {
                        if (nextResultIsForChannel) {
                            nextResultIsForChannel = false;
                            final var status = item.transactionResultOrThrow().status();
                            resultsForChannel.add(status.name());
                            if (status == ResponseCodeEnum.SUCCESS) {
                                log.info("Block stream check passed: {}", summary());
                                return true;
                            }
                        }
                    }
                    default -> {
                        // Only transaction inputs and their results matter here
                    }
                }
            }
            return false;
        }

        private boolean isSubmitBundleForChannel(final Bytes signedTransaction) {
            try {
                final var body = TransactionParts.from(signedTransaction).body();
                if (!body.hasClprSubmitBundle()) {
                    return false;
                }
                ++submitBundlesAnyChannel;
                if (!channelId.equals(body.clprSubmitBundleOrThrow().channelId())) {
                    return false;
                }
                ++submitBundlesForChannel;
                return true;
            } catch (final RuntimeException ignore) {
                return false;
            }
        }

        private synchronized String summary() {
            return "scanned " + blocksSeen + " blocks (#" + firstBlock + "..#" + lastBlock + "), found "
                    + submitBundlesForChannel + " ClprSubmitBundle bodies for channel " + channelId.toHex()
                    + " (results "
                    + resultsForChannel + ") and " + submitBundlesAnyChannel
                    + " ClprSubmitBundle bodies on any channel";
        }

        @Override
        public String toString() {
            return "SuccessfulClprSubmitBundle[" + summary() + "]";
        }
    }

    private static ClprChannel readChannel(final HapiSpec spec, final ClprChannelCrypto crypto) {
        final var conn = spec.embeddedStateOrThrow()
                .getReadableStates(ClprService.NAME)
                .<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID)
                .get(new ProtoBytes(Bytes.wrap(crypto.channelId())));
        return requireNonNull(conn, "Channel not found in embedded state");
    }

    /** This ledger's chain id, as genesis sets it (ClprUpdateLedgerConfiguration preserves it). */
    private static String localChainId(final HapiSpec spec) {
        return "eip155:" + spec.startupProperties().getLong("contracts.chainId");
    }

    /**
     * This ledger's own configuration. Its chain id is ignored (the handler keeps the genesis one); the service
     * address must be the CLPR system contract's.
     */
    private static ClprLedgerConfiguration localLedgerConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .setServiceAddress(ByteString.copyFrom(CLPR_SERVICE_ADDRESS_20))
                .setThrottles(throttles())
                .build();
    }

    /** The pass-through verifier's peer configuration, as {@link ClprOrchestratorSubmitTest} declares it. */
    private static ClprLedgerConfiguration passThroughLedgerConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId("hiero:embedded")
                .setServiceAddress(ByteString.copyFrom(CLPR_SERVICE_ADDRESS_20))
                .setThrottles(throttles())
                .build();
    }

    private static ClprThrottles throttles() {
        return ClprThrottles.newBuilder()
                .setMaxMessagesPerBundle(100)
                .setMaxMessagePayloadBytes(65536)
                .setMaxGasPerMessage(1_000_000L)
                .setMaxQueueDepth(1000)
                .setMaxSyncBytes(1_048_576L)
                .build();
    }

    /** The pass-through verifier's config proof: a state proof whose leaf is the peer's ledger configuration. */
    private static byte[] passThroughConfigProof() {
        final var stateValue = StateValue.newBuilder()
                .clprServiceILedgerConfiguration(protoToPbj(
                        passThroughLedgerConfig(), com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration.class))
                .build();
        final var stateItem = StateItem.newBuilder().value(stateValue).build();
        final var path = MerklePath.newBuilder()
                .stateItemLeaf(StateItem.PROTOBUF.toBytes(stateItem))
                .build();
        return StateProof.PROTOBUF
                .toBytes(StateProof.newBuilder().paths(path).build())
                .toByteArray();
    }
}
