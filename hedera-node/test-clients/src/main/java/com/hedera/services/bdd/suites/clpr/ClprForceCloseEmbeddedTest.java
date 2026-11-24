// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.hapi.node.state.clpr.ClprChannelStatus.ACTIVE;
import static com.hedera.hapi.node.state.clpr.ClprChannelStatus.CLOSED;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprDeleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** Exercises force-close authorization and sparse cleanup through the HAPI transaction pipeline. */
@Tag(CLPR)
public class ClprForceCloseEmbeddedTest {
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "accounts.clprAdmin"})
    final Stream<DynamicTest> adminForceClosePurgesSparseQueueAcrossBatches() {
        final var channelId = Bytes.wrap(new ClprChannelCrypto().channelId());
        final var otherChannelId = Bytes.wrap(new ClprChannelCrypto().channelId());
        final var connectorId = Bytes.fromHex("01");
        final var connectorKey = new ClprConnectorKey(channelId, connectorId);
        final var initial = ClprChannel.newBuilder()
                .channelId(channelId)
                .status(ACTIVE)
                .nextMessageId(19)
                .sentRunningHash(Bytes.wrap(new byte[32]))
                .build();
        final var otherChannel = initial.copyBuilder().channelId(otherChannelId).build();
        final var data = ClprMessageValue.newBuilder()
                .payload(ClprMessagePayload.newBuilder()
                        .message(ClprMessage.newBuilder().connectorId(connectorId)))
                .build();
        final var reply = ClprMessageValue.newBuilder()
                .payload(ClprMessagePayload.newBuilder()
                        .messageReply(ClprMessageReply.newBuilder()
                                .messageId(1)
                                .messageReplyData(Bytes.wrap(new byte[65536]))))
                .build();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                cryptoCreate("forceCloseCivilian").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("forceCloseAdmin").balance(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        overriding(
                                "accounts.clprAdmin",
                                Long.toString(spec.registry()
                                        .getAccountID("forceCloseAdmin")
                                        .getAccountNum())))),
                withOpContext((spec, opLog) -> spec.mutateEmbeddedState(state -> {
                    final var states = state.getWritableStates(ClprService.NAME);
                    final var channels = states.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID);
                    channels.put(new ProtoBytes(channelId), initial);
                    channels.put(new ProtoBytes(otherChannelId), otherChannel);
                    states.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID)
                            .put(
                                    connectorKey,
                                    ClprConnector.newBuilder()
                                            .channelId(channelId)
                                            .connectorId(connectorId)
                                            .inFlightMessageCount(2)
                                            .build());
                    final var queue = states.<ClprMessageKey, ClprMessageValue>get(MESSAGE_QUEUE_STATE_ID);
                    queue.put(new ClprMessageKey(channelId, 1), data);
                    queue.put(new ClprMessageKey(channelId, 2), reply);
                    queue.put(new ClprMessageKey(channelId, 18), data);
                    queue.put(new ClprMessageKey(otherChannelId, 1), reply);
                })),
                clprDeleteChannel()
                        .channelId(channelId.toByteArray())
                        .forceClose(true)
                        .payingWith("forceCloseCivilian")
                        .hasKnownStatusFrom(AUTHORIZATION_FAILED, NOT_SUPPORTED),
                withOpContext((spec, opLog) -> assertEquals(
                        initial,
                        spec.embeddedStateOrThrow()
                                .getReadableStates(ClprService.NAME)
                                .<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID)
                                .get(new ProtoBytes(channelId)))),
                clprDeleteChannel()
                        .channelId(channelId.toByteArray())
                        .forceClose(true)
                        .payingWith("forceCloseAdmin"),
                assertCleanup(initial, otherChannel, connectorKey, reply, 17, 1),
                clprSubmitBundle()
                        .channelId(channelId.toByteArray())
                        .bundlePayload(new byte[] {1})
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_INVALID_CHANNEL_STATUS),
                clprDeleteChannel()
                        .channelId(channelId.toByteArray())
                        .forceClose(true)
                        .payingWith(GENESIS),
                assertCleanup(initial, otherChannel, connectorKey, reply, 19, 0),
                // A completed cleanup is an idempotent SUCCESS, with no further state changes.
                clprDeleteChannel()
                        .channelId(channelId.toByteArray())
                        .forceClose(true)
                        .payingWith("forceCloseAdmin"),
                assertCleanup(initial, otherChannel, connectorKey, reply, 19, 0));
    }

    private static SpecOperation assertCleanup(
            final ClprChannel initial,
            final ClprChannel otherChannel,
            final ClprConnectorKey connectorKey,
            final ClprMessageValue otherMessage,
            final long cursor,
            final long inFlightCount) {
        return withOpContext((spec, opLog) -> {
            final var states = spec.embeddedStateOrThrow().getReadableStates(ClprService.NAME);
            final var channels = states.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID);
            assertEquals(
                    initial.copyBuilder()
                            .status(CLOSED)
                            .queuePurgeCursor(cursor)
                            .build(),
                    channels.get(new ProtoBytes(initial.channelId())));
            assertEquals(otherChannel, channels.get(new ProtoBytes(otherChannel.channelId())));
            assertEquals(
                    inFlightCount,
                    states.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID)
                            .get(connectorKey)
                            .inFlightMessageCount());
            final var queue = states.<ClprMessageKey, ClprMessageValue>get(MESSAGE_QUEUE_STATE_ID);
            assertNull(queue.get(new ClprMessageKey(initial.channelId(), 1)));
            assertNull(queue.get(new ClprMessageKey(initial.channelId(), 2)));
            if (cursor == 17) {
                assertNotNull(queue.get(new ClprMessageKey(initial.channelId(), 18)));
            } else {
                assertNull(queue.get(new ClprMessageKey(initial.channelId(), 18)));
            }
            assertEquals(otherMessage, queue.get(new ClprMessageKey(otherChannel.channelId(), 1)));
        });
    }
}
