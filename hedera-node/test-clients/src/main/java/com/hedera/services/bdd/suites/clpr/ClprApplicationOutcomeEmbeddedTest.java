// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.VERIFIABLE_SYNC_BYTES;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.VERIFIER_CALL_OVERHEAD_BYTES;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.clpr.ClprQueueGovernanceEmbeddedTest.readConfig;
import static com.hedera.services.bdd.suites.clpr.ClprQueueGovernanceEmbeddedTest.writeConfig;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.runningHashAfter;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toBundleProofBytes;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hederahashgraph.api.proto.java.ClprChannelStatus.ACTIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CLPR_CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.protobuf.ByteString;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprSubmitBundle;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprUpdateLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprMessageReply;
import com.hederahashgraph.api.proto.java.ClprMessageReplyStatus;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** Exercises application storage, nested sends, gas charges, and callbacks through real HAPI dispatch. */
@Tag(CLPR)
public class ClprApplicationOutcomeEmbeddedTest {
    private static final String APPLICATION = "ClprOutcomeApplication";
    private static final String CONNECTOR = "PassThroughAuth";
    private static final String VERIFIER = "ClprPassThroughVerifier";
    private static final byte[] ZERO_HASH = new byte[32];
    private static final Bytes CONNECTOR_ID = Bytes.wrap(new byte[32]);
    private static final TupleType<Tuple> MESSAGE_ARGS = TupleType.parse("(uint256,uint8,bool)");

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "clpr.messageExecutionCost", "clpr.endpointMarginPercent"})
    final Stream<DynamicTest> outgoingReplyOutcomeMatchesCommittedApplicationEffects() {
        return hapiTest(
                setup(),
                // Each callback writes storage and sends nested DATA before returning or reverting.
                outgoingCase(512, 0, false, 0),
                outgoingCase(32, 1, false, 0),
                outgoingCase(32, 2, false, 0),
                outgoingCase(32, 0, true, 1));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "clpr.messageExecutionCost", "clpr.endpointMarginPercent"})
    final Stream<DynamicTest> oversizedIncomingSuccessUsesUnavailableHookWithoutCompensation() {
        final var channelId = Bytes.wrap(new ClprChannelCrypto().channelId());
        final var largeReply = reply(1, new byte[512]);
        final var emptyReply = reply(2, new byte[0]);
        return hapiTest(
                setup(),
                withOpContext((spec, log) -> {
                    final var original = data(spec, new byte[0]);
                    seedChannel(spec, channelId, 1024, List.of(original, original));
                    assertTrue(largeReply.getSerializedSize() <= 1024);
                }),
                withOpContext((spec, log) -> {
                    // Model a channel with a legacy or external peer returning an oversized reply.
                    final var config = readConfig(spec);
                    writeConfig(
                            spec,
                            config.copyBuilder()
                                    .throttles(config.throttlesOrThrow()
                                            .copyBuilder()
                                            .maxMessagePayloadBytes(256))
                                    .build());
                    try {
                        final var original = data(spec, new byte[0]);
                        allRunFor(
                                spec,
                                submit(
                                        channelId,
                                        0,
                                        1,
                                        runningHashAfter(ZERO_HASH, List.of(original)),
                                        largeReply,
                                        ZERO_HASH),
                                snapshot(0, 0, 1, 0, 0, 0));
                        assertReceived(spec, channelId, 1, runningHashAfter(ZERO_HASH, List.of(largeReply)));
                        assertNull(queueValue(spec, channelId, 1));
                        assertNotNull(queueValue(spec, channelId, 2));
                        allRunFor(
                                spec,
                                submit(
                                        channelId,
                                        1,
                                        2,
                                        runningHashAfter(ZERO_HASH, List.of(original, original)),
                                        emptyReply,
                                        runningHashAfter(ZERO_HASH, List.of(largeReply))),
                                // A valid empty result uses the ordinary callback, distinct from unavailable data.
                                snapshot(0, 1, 1, 0, 0, 0));
                        assertReceived(
                                spec, channelId, 2, runningHashAfter(ZERO_HASH, List.of(largeReply, emptyReply)));
                        assertNull(queueValue(spec, channelId, 2));
                    } finally {
                        writeConfig(spec, config);
                    }
                }));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "clpr.messageExecutionCost", "clpr.endpointMarginPercent"})
    final Stream<DynamicTest> queuedDataAndItsSuccessorAreDeliveredAfterRejectedLimitReductions() {
        return hapiTest(setup(), withOpContext((spec, log) -> {
            final var channelId = Bytes.wrap(new ClprChannelCrypto().channelId());
            seedChannel(spec, channelId, 1024, List.of());
            // Both DATA payloads encode a successful call with an empty return. The head also carries trailing bytes.
            final var messages = List.of(data(spec, new byte[512]), data(spec, new byte[96]));
            final var queuedBundle = toBundleProofBytes(ACTIVE, 0, 0, ZERO_HASH, messages, ZERO_HASH);
            final var before = readConfig(spec);
            final var limits = pbjToProto(
                    before.throttlesOrThrow(),
                    com.hedera.hapi.node.state.clpr.ClprThrottles.class,
                    ClprThrottles.class);
            assertTrue(messages.getFirst().getSerializedSize() > 256);
            assertTrue(messages.getFirst().getSerializedSize() <= limits.getMaxMessagePayloadBytes());
            assertTrue(messages.getLast().getSerializedSize() <= 256);
            for (final var reduced : List.of(
                    limits.toBuilder().setMaxMessagePayloadBytes(256).build(),
                    limits.toBuilder()
                            .setMaxSyncBytes(limits.getMaxSyncBytes() - 1)
                            .build(),
                    limits.toBuilder().setMaxMessagesPerBundle(1).build())) {
                allRunFor(
                        spec,
                        clprUpdateLedgerConfiguration()
                                .throttles(reduced)
                                .payingWith(GENESIS)
                                .hasKnownStatus(INVALID_CLPR_CONFIGURATION));
                assertEquals(before, readConfig(spec));
            }
            allRunFor(
                    spec,
                    clprSubmitBundle()
                            .channelId(channelId.toByteArray())
                            .bundlePayload(queuedBundle)
                            .endpointNodeId(0L)
                            .payingWith(GENESIS),
                    snapshot(2, 0, 0, 0, 0, 0));
            assertReceived(spec, channelId, 2, runningHashAfter(ZERO_HASH, messages));
            for (long id = 1; id <= 2; id++) {
                final var reply =
                        queueValue(spec, channelId, id).payloadOrThrow().messageReplyOrThrow();
                assertEquals(id, reply.messageId());
                assertEquals(com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus.SUCCESS, reply.status());
            }
        }));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {
                "clpr.enabled",
                "clpr.messageExecutionCost",
                "clpr.endpointMarginPercent",
                "jumboTransactions.ethereumMaxCallDataSize"
            })
    final Stream<DynamicTest> exactSenderProofBudgetFitsTheConsensusVerifierCall() {
        return hapiTest(setup(), withOpContext((spec, log) -> {
            final var channelId = Bytes.wrap(new ClprChannelCrypto().channelId());
            seedChannel(spec, channelId, 1024, List.of());
            final byte[] anchor = new byte[64];
            final byte[] channelContext = new byte[52];
            spec.mutateEmbeddedState(state -> {
                final var channels =
                        state.getWritableStates(ClprService.NAME).<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID);
                final var key = new ProtoBytes(channelId);
                channels.put(
                        key,
                        channels.get(key)
                                .copyBuilder()
                                .trustAnchor(Bytes.wrap(anchor))
                                .channelContext(Bytes.wrap(channelContext))
                                .build());
            });
            final var messages = List.of(data(spec, new byte[96]));
            final var proof =
                    StateProof.PROTOBUF.parse(Bytes.wrap(toBundleProofBytes(ACTIVE, 0, 0, ZERO_HASH, messages)));
            // Vary the opaque signature in this pass-through verifier fixture to hit the exact packing boundary.
            final int budget = VERIFIABLE_SYNC_BYTES - VERIFIER_CALL_OVERHEAD_BYTES;
            final var signature = TssSignedBlockProof.newBuilder().blockSignature(Bytes.wrap(new byte[budget]));
            final int excess =
                    proof.copyBuilder().signedBlockProof(signature).build().protobufSize() - budget;
            final var exactProof = proof.copyBuilder()
                    .signedBlockProof(signature.blockSignature(Bytes.wrap(new byte[budget - excess])))
                    .build();
            assertEquals(budget, exactProof.protobufSize());
            final byte[] proofBytes = StateProof.PROTOBUF.toBytes(exactProof).toByteArray();
            // Size the complete verifier call using normal Hiero anchor and context lengths.
            final int encodedLength = new Function("verifyBundle(bytes,bytes,bytes)", "()")
                    .encodeCall(Tuple.of(proofBytes, anchor, channelContext))
                    .remaining();
            assertTrue(encodedLength <= VERIFIABLE_SYNC_BYTES);
            allRunFor(
                    spec,
                    overriding("jumboTransactions.ethereumMaxCallDataSize", Integer.toString(encodedLength - 1)),
                    clprSubmitBundle()
                            .channelId(channelId.toByteArray())
                            .bundlePayload(proofBytes)
                            .endpointNodeId(0L)
                            .payingWith(GENESIS)
                            .hasKnownStatus(
                                    com.hederahashgraph.api.proto.java.ResponseCodeEnum
                                            .CLPR_BUNDLE_VERIFICATION_FAILED),
                    overriding("jumboTransactions.ethereumMaxCallDataSize", Integer.toString(encodedLength)));
            allRunFor(
                    spec,
                    clprSubmitBundle()
                            .channelId(channelId.toByteArray())
                            .bundlePayload(proofBytes)
                            .endpointNodeId(0L)
                            .payingWith(GENESIS),
                    snapshot(1, 0, 0, 0, 0, 0));
            assertReceived(spec, channelId, 1, runningHashAfter(ZERO_HASH, messages));
        }));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {
                "clpr.enabled",
                "clpr.messageExecutionCost",
                "clpr.endpointMarginPercent",
                "contracts.throttle.throttleByGas",
                "contracts.maxGasPerSecBackend"
            })
    final Stream<DynamicTest> successfulVerifierReleasesExecutionGasToTheApplication() {
        return hapiTest(
                // The CLPR task supplies the 15M transaction cap and the default 8M verifier grant.
                overriding("contracts.maxGasPerSecBackend", "15000000"),
                overriding("contracts.throttle.throttleByGas", "true"),
                withOpContext((spec, log) -> {
                    final var originalConfig = readConfig(spec);
                    try {
                        allRunFor(spec, setup(15_000_000L));
                        final var channelId = Bytes.wrap(new ClprChannelCrypto().channelId());
                        seedChannel(spec, channelId, 1024, List.of());
                        final var message = MESSAGE_ARGS.encode(Tuple.of(BigInteger.ZERO, 3, false));
                        final var incoming = data(spec, message.array());
                        allRunFor(
                                spec,
                                submit(channelId, 0, 0, ZERO_HASH, incoming, ZERO_HASH),
                                snapshot(1, 0, 0, 0, 0, 0));
                        final var response =
                                queueValue(spec, channelId, 1).payloadOrThrow().messageReplyOrThrow();
                        assertEquals(com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus.SUCCESS, response.status());
                        final long observedGas =
                                new BigInteger(1, response.messageReplyData().toByteArray()).longValueExact();
                        // Without the verifier release, this callback could start with at most 7M gas.
                        assertTrue(
                                observedGas > 7_000_000L && observedGas < 15_000_000L,
                                () -> "gas left in application callback: " + observedGas);
                    } finally {
                        writeConfig(spec, originalConfig);
                    }
                }));
    }

    private static SpecOperation setup() {
        return setup(3_000_000L);
    }

    private static SpecOperation setup(final long maxGasPerMessage) {
        return withOpContext((spec, log) -> {
            allRunFor(
                    spec,
                    overriding("clpr.enabled", "true"),
                    // No flat compensation: a connector balance debit proves execution gas was charged.
                    overriding("clpr.messageExecutionCost", "0"),
                    overriding("clpr.endpointMarginPercent", "0"));
            allRunFor(
                    spec,
                    clprUpdateLedgerConfiguration()
                            .throttles(pbjToProto(
                                            readConfig(spec).throttlesOrThrow(),
                                            com.hedera.hapi.node.state.clpr.ClprThrottles.class,
                                            ClprThrottles.class)
                                    .toBuilder()
                                    .setMaxGasPerMessage(maxGasPerMessage)
                                    .build())
                            .payingWith(GENESIS),
                    uploadInitCode(APPLICATION, CONNECTOR, VERIFIER),
                    contractCreate(APPLICATION, CONNECTOR_ID.toByteArray()).gas(2_000_000L),
                    contractCreate(CONNECTOR).balance(ONE_HUNDRED_HBARS),
                    contractCreate(VERIFIER).gas(2_000_000L));
        });
    }

    private static SpecOperation outgoingCase(
            final int resultBytes, final int mode, final boolean commits, final long executions) {
        return withOpContext((spec, log) -> {
            final var channelId = Bytes.wrap(new ClprChannelCrypto().channelId());
            seedChannel(spec, channelId, 256, List.of());
            final long balanceBefore = connectorBalance(spec);
            final var args = MESSAGE_ARGS.encode(Tuple.of(BigInteger.valueOf(resultBytes), mode, true));
            final var payload = new byte[args.remaining()];
            args.get(payload);
            final var incoming = data(spec, payload);
            final var transaction = "outgoing-" + resultBytes + "-" + mode;
            allRunFor(
                    spec,
                    submit(channelId, 0, 0, ZERO_HASH, incoming, ZERO_HASH).via(transaction),
                    snapshot(executions, 0, 0, 0, 0, 0),
                    getTxnRecord(transaction).exposingAllTo(records -> {
                        final var applicationId = spec.registry().getContractId(APPLICATION);
                        final var applicationAddress = ByteString.copyFrom(asSolidityAddress(applicationId));
                        final var callbacks = records.stream()
                                .filter(record -> record.hasContractCallResult()
                                        && (record.getContractCallResult()
                                                        .getContractID()
                                                        .equals(applicationId)
                                                || record.getContractCallResult()
                                                        .getContractID()
                                                        .getEvmAddress()
                                                        .equals(applicationAddress)))
                                .toList();
                        assertEquals(commits ? 1 : 0, callbacks.size(), "rolled-back callback records are removable");
                        final var creations = records.stream()
                                .filter(record -> record.hasContractCreateResult())
                                .toList();
                        assertEquals(commits ? 1 : 0, creations.size(), "rolled-back nested creations are removable");
                        if (commits) {
                            assertEquals(
                                    com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS,
                                    creations.getFirst().getReceipt().getStatus());
                        }
                    }));

            final long replyId = commits ? 2 : 1;
            final var response =
                    queueValue(spec, channelId, replyId).payloadOrThrow().messageReplyOrThrow();
            assertEquals(1, response.messageId());
            assertEquals(
                    commits
                            ? com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus.SUCCESS
                            : com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus.APPLICATION_ERROR,
                    response.status());
            assertEquals(commits ? resultBytes : 0, response.messageReplyData().length());
            final var states = spec.embeddedStateOrThrow().getReadableStates(ClprService.NAME);
            final var channel =
                    states.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID).get(new ProtoBytes(channelId));
            assertEquals(replyId + 1, channel.nextMessageId());
            final var survivingPayloads = new ArrayList<ClprMessagePayload>();
            for (long id = 1; id <= replyId; id++) {
                final var queued = queueValue(spec, channelId, id);
                survivingPayloads.add(pbjToProto(
                        queued.payloadOrThrow(),
                        com.hedera.hapi.node.state.clpr.ClprMessagePayload.class,
                        ClprMessagePayload.class));
                assertEquals(
                        Bytes.wrap(runningHashAfter(ZERO_HASH, survivingPayloads)),
                        queued.runningHashAfterProcessing(),
                        "queue running hashes must exclude rolled-back nested sends");
            }
            assertEquals(Bytes.wrap(runningHashAfter(ZERO_HASH, survivingPayloads)), channel.sentRunningHash());
            assertEquals(
                    commits ? 1 : 0,
                    states.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID)
                            .get(new ClprConnectorKey(channelId, CONNECTOR_ID))
                            .inFlightMessageCount());
            if (commits) {
                assertTrue(queueValue(spec, channelId, 1).payloadOrThrow().hasMessage());
            } else {
                assertNull(queueValue(spec, channelId, 2), "rolled-back nested send must leave no extra queue slot");
            }
            assertTrue(connectorBalance(spec) < balanceBefore, "executed application gas must remain charged");
            assertReceived(spec, channelId, 1, runningHashAfter(ZERO_HASH, List.of(incoming)));
        });
    }

    private static void seedChannel(
            final HapiSpec spec,
            final Bytes channelId,
            final int peerPayloadLimit,
            final List<ClprMessagePayload> sent) {
        spec.mutateEmbeddedState(state -> {
            final var states = state.getWritableStates(ClprService.NAME);
            final var config = states.<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID)
                    .get();
            states.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID)
                    .put(
                            new ProtoBytes(channelId),
                            ClprChannel.newBuilder()
                                    .channelId(channelId)
                                    .status(com.hedera.hapi.node.state.clpr.ClprChannelStatus.ACTIVE)
                                    .verifierContract(
                                            protoToPbj(spec.registry().getContractId(VERIFIER), ContractID.class))
                                    .nextMessageId(sent.size() + 1L)
                                    .sentRunningHash(Bytes.wrap(runningHashAfter(ZERO_HASH, sent)))
                                    .lastConfigTimestamp(config.timestamp())
                                    .peerThrottles(protoToPbj(
                                            throttles(peerPayloadLimit),
                                            com.hedera.hapi.node.state.clpr.ClprThrottles.class))
                                    .build());
            states.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID)
                    .put(
                            new ClprConnectorKey(channelId, CONNECTOR_ID),
                            ClprConnector.newBuilder()
                                    .channelId(channelId)
                                    .connectorId(CONNECTOR_ID)
                                    .connectorContract(
                                            protoToPbj(spec.registry().getContractId(CONNECTOR), ContractID.class))
                                    .inFlightMessageCount(sent.size())
                                    .build());
            for (int i = 0; i < sent.size(); i++) {
                states.<ClprMessageKey, ClprMessageValue>get(MESSAGE_QUEUE_STATE_ID)
                        .put(
                                new ClprMessageKey(channelId, i + 1L),
                                ClprMessageValue.newBuilder()
                                        .payload(protoToPbj(
                                                sent.get(i), com.hedera.hapi.node.state.clpr.ClprMessagePayload.class))
                                        .runningHashAfterProcessing(
                                                Bytes.wrap(runningHashAfter(ZERO_HASH, sent.subList(0, i + 1))))
                                        .build());
            }
        });
    }

    private static HapiClprSubmitBundle submit(
            final Bytes channelId,
            final long peerAck,
            final long peerReceived,
            final byte[] receivedHash,
            final ClprMessagePayload payload,
            final byte[] initialHash) {
        return clprSubmitBundle()
                .channelId(channelId.toByteArray())
                .bundlePayload(
                        toBundleProofBytes(ACTIVE, peerAck, peerReceived, receivedHash, List.of(payload), initialHash))
                .endpointNodeId(0L)
                .payingWith(GENESIS);
    }

    private static SpecOperation snapshot(final long... expected) {
        return contractCallLocal(APPLICATION, "snapshot").exposingTypedResultsTo(actual -> {
            assertEquals(expected.length, actual.length);
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], ((Number) actual[i]).longValue(), "snapshot field " + i);
            }
        });
    }

    private static ClprMessagePayload data(final HapiSpec spec, final byte[] payload) {
        final var address =
                ByteString.copyFrom(asSolidityAddress(spec.registry().getContractId(APPLICATION)));
        return ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(CONNECTOR_ID.toByteArray()))
                        .setTargetApplication(address)
                        .setSender(address)
                        .setMessageData(ByteString.copyFrom(payload)))
                .build();
    }

    private static ClprMessagePayload reply(final long id, final byte[] payload) {
        return ClprMessagePayload.newBuilder()
                .setMessageReply(ClprMessageReply.newBuilder()
                        .setMessageId(id)
                        .setStatus(ClprMessageReplyStatus.SUCCESS)
                        .setMessageReplyData(ByteString.copyFrom(payload)))
                .build();
    }

    private static ClprThrottles throttles(final int payloadLimit) {
        return HapiClprUpdateLedgerConfiguration.defaultThrottles().toBuilder()
                .setMaxMessagePayloadBytes(payloadLimit)
                .setMaxQueueDepth(10)
                .setMaxGasPerMessage(3_000_000L)
                .build();
    }

    private static ClprMessageValue queueValue(final HapiSpec spec, final Bytes channelId, final long id) {
        return spec.embeddedStateOrThrow()
                .getReadableStates(ClprService.NAME)
                .<ClprMessageKey, ClprMessageValue>get(MESSAGE_QUEUE_STATE_ID)
                .get(new ClprMessageKey(channelId, id));
    }

    private static long connectorBalance(final HapiSpec spec) {
        return spec.embeddedStateOrThrow()
                .getReadableStates(TokenService.NAME)
                .<AccountID, Account>get(ACCOUNTS_STATE_ID)
                .get(protoToPbj(spec.registry().getAccountID(CONNECTOR), AccountID.class))
                .tinybarBalance();
    }

    private static void assertReceived(final HapiSpec spec, final Bytes channelId, final long id, final byte[] hash) {
        final var channel = spec.embeddedStateOrThrow()
                .getReadableStates(ClprService.NAME)
                .<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID)
                .get(new ProtoBytes(channelId));
        assertEquals(id, channel.receivedMessageId());
        assertEquals(Bytes.wrap(hash), channel.receivedRunningHash());
    }
}
