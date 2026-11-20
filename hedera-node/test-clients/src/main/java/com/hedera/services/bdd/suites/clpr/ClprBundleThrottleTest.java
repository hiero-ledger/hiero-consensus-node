// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.junit.TestTags.ONLY_REPEATABLE;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.Target.APP_PROPERTIES;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toBundleProofBytes;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toConfigProofBytes;
import static com.hederahashgraph.api.proto.java.ClprChannelStatus.ACTIVE;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprSubmitBundle;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.SysFileOverrideOp;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ThrottleDefinitions;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CLPR)
@Tag(ONLY_REPEATABLE)
@TargetEmbeddedMode(REPEATABLE)
public class ClprBundleThrottleTest {
    private static final String VERIFIER = "ClprPassThroughVerifier";
    private static final String THROTTLE_SNAPSHOT = "clprThrottleSnapshot";
    private static final int MESSAGES_PER_BUNDLE = 1_000;
    private static final int GENESIS_BUNDLE_TPS = 10;
    private static final Duration ROUND_DURATION = Duration.ofMillis(1);
    private static final Duration OFFER_INTERVAL = Duration.ofMillis(100);
    private static final byte[] ZERO_HASH = new byte[32];

    @LeakyHapiTest(requirement = THROTTLE_OVERRIDES)
    Stream<DynamicTest> fullBundlesSaturateThrottleBeforeAndAfterUpdate() {
        final var crypto = new ClprChannelCrypto();
        // An unregistered connector lets every message exercise verification, hashing, and reply enqueueing.
        final var message = ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(crypto.connectorId()))
                        .setTargetApplication(ByteString.copyFrom(new byte[20]))
                        .setSender(ByteString.copyFrom(new byte[20]))
                        .setMessageData(ByteString.copyFromUtf8("x")))
                .build();
        final var messages = Collections.nCopies(MESSAGES_PER_BUNDLE, message);
        return hapiTest(
                new SysFileOverrideOp(APP_PROPERTIES, () -> null),
                // The pass-through verifier needs extra gas and trace space for a full bundle proof.
                // Disable the frontend throttle to observe consensus admission for every offered bundle.
                overridingAllOf(Map.of(
                        "clpr.enabled", "true",
                        "contracts.maxGasPerTransaction", "50000000",
                        "contracts.maxSerializedTraceDataBytes", "524288",
                        "hedera.ingestThrottle.enabled", "false",
                        "clpr.verifierGasLimit", "50000000")),
                getFileContents(THROTTLE_DEFS).payingWith(GENESIS).saveToRegistry(THROTTLE_SNAPSHOT),
                uploadInitCode(VERIFIER),
                contractCreate(VERIFIER),
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                withOpContext((spec, log) -> {
                    final var ledgerConfig = requireNonNull(spec.embeddedStateOrThrow()
                            .getReadableStates(ClprService.NAME)
                            .<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID)
                            .get());
                    assertEquals(
                            MESSAGES_PER_BUNDLE, ledgerConfig.throttlesOrThrow().maxMessagesPerBundle());
                    allRunFor(
                            spec,
                            clprCompleteChannel()
                                    .channelId(crypto.channelId())
                                    .publicKey(crypto.publicKey())
                                    .signature(crypto.signature())
                                    .signatureScheme(ClprSignatureScheme.ED25519)
                                    .verifierContract(VERIFIER)
                                    .configProofBytes(toConfigProofBytes(pbjToProto(
                                            ledgerConfig,
                                            ClprLedgerConfiguration.class,
                                            com.hederahashgraph.api.proto.java.ClprLedgerConfiguration.class)))
                                    .payingWith(GENESIS));
                }),
                withOpContext((spec, log) -> {
                    final var channelId = crypto.channelId();
                    final var oneTpsThrottles = oneTpsThrottles(spec.registry().getBytes(THROTTLE_SNAPSHOT));
                    final var embedded = spec.repeatableEmbeddedHederaOrThrow();
                    embedded.setRoundDuration(ROUND_DURATION);
                    try {
                        for (int i = 0; i < GENESIS_BUNDLE_TPS; i++) {
                            submit(spec, channelId, nextBundle(spec, channelId, messages), SUCCESS);
                        }
                        final var eleventhBundle = nextBundle(spec, channelId, messages);
                        submit(spec, channelId, eleventhBundle, THROTTLED_AT_CONSENSUS);

                        allRunFor(
                                spec,
                                fileUpdate(THROTTLE_DEFS).payingWith(GENESIS).contents(oneTpsThrottles));
                        embedded.tick(Duration.ofSeconds(1));
                        submit(spec, channelId, eleventhBundle, SUCCESS);

                        // Keep offering 10 full bundles per second after the live update.
                        final var twelfthBundle = nextBundle(spec, channelId, messages);
                        for (int i = 1; i < GENESIS_BUNDLE_TPS; i++) {
                            embedded.tick(OFFER_INTERVAL.minus(ROUND_DURATION));
                            submit(spec, channelId, twelfthBundle, THROTTLED_AT_CONSENSUS);
                        }
                        embedded.tick(OFFER_INTERVAL.minus(ROUND_DURATION));
                        submit(spec, channelId, twelfthBundle, SUCCESS);
                        assertEquals(
                                12L * MESSAGES_PER_BUNDLE,
                                channel(spec, channelId).receivedMessageId());
                    } finally {
                        embedded.resetRoundDuration();
                    }
                }));
    }

    private static byte[] oneTpsThrottles(final byte[] original) throws InvalidProtocolBufferException {
        final var definitions = ThrottleDefinitions.parseFrom(original).toBuilder();
        final var bucket = definitions.getThrottleBucketsList().stream()
                .filter(candidate -> candidate.getName().equals("ClprBundles"))
                .findFirst()
                .orElseThrow();
        assertEquals(1_000, bucket.getBurstPeriodMs());
        assertEquals(1, bucket.getThrottleGroupsCount());
        final var group = bucket.getThrottleGroups(0);
        assertEquals(List.of(ClprSubmitBundle), group.getOperationsList());
        assertEquals(GENESIS_BUNDLE_TPS * 1_000L, group.getMilliOpsPerSec());
        definitions.setThrottleBuckets(
                definitions.getThrottleBucketsList().indexOf(bucket),
                bucket.toBuilder().setThrottleGroups(0, group.toBuilder().setMilliOpsPerSec(1_000)));
        return definitions.build().toByteArray();
    }

    private static byte[] nextBundle(
            final HapiSpec spec, final byte[] channelId, final List<ClprMessagePayload> messages) {
        final var channel = channel(spec, channelId);
        // Acknowledge the previous batch of replies so each bundle can enqueue another 1,000.
        return toBundleProofBytes(
                ACTIVE,
                channel.receivedMessageId(),
                channel.nextMessageId() - 1,
                channel.sentRunningHash().length() == 0
                        ? ZERO_HASH
                        : channel.sentRunningHash().toByteArray(),
                messages,
                channel.receivedRunningHash().length() == 0
                        ? ZERO_HASH
                        : channel.receivedRunningHash().toByteArray());
    }

    private static void submit(
            final HapiSpec spec, final byte[] channelId, final byte[] proof, final ResponseCodeEnum expectedStatus) {
        final long before = channel(spec, channelId).receivedMessageId();
        allRunFor(
                spec,
                clprSubmitBundle()
                        .channelId(channelId)
                        .bundlePayload(proof)
                        .payingWith(GENESIS)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(expectedStatus)
                        .noLogging());
        final var after = channel(spec, channelId);
        assertEquals(com.hedera.hapi.node.state.clpr.ClprChannelStatus.ACTIVE, after.status());
        assertEquals(expectedStatus == SUCCESS ? before + MESSAGES_PER_BUNDLE : before, after.receivedMessageId());
    }

    private static ClprChannel channel(final HapiSpec spec, final byte[] channelId) {
        return requireNonNull(spec.embeddedStateOrThrow()
                .getReadableStates(ClprService.NAME)
                .<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID)
                .get(new ProtoBytes(Bytes.wrap(channelId))));
    }
}
