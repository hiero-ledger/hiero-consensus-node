// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.corrections;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ENTITY_COUNTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_TOPICS;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.stateErrata;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_STATE_ERRATA;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ERRATA_STATE_CHANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapDeleteChange;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.NewStateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.support.amendments.BlockWatcher;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests the {@code StateErrata} transaction, which corrects network state by applying state changes.
 *
 * <p>These tests correct account memos rather than balances, because the end-of-run balance reconciliation
 * reconciles balances against record stream transfer lists, which an errata deliberately bypasses.
 */
@DisplayName("State errata")
public class StateErrataSuite {
    private static final Duration BLOCK_TIMEOUT = Duration.ofMinutes(2);
    private static final String CIVILIAN = "civilian";
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final long NONEXISTENT_ENTITY_NUM = 999_999_999_999L;
    private static final int UNKNOWN_STATE_ID = 99_999;

    @HapiTest
    @DisplayName("treasury and system admin may submit errata")
    final Stream<DynamicTest> treasuryAndSystemAdminMaySubmitErrata() {
        return hapiTest(
                stateErrata()
                        .withStateChanges(spec -> List.of(deleteTopic(nonexistentTopic(spec))))
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS),
                stateErrata()
                        .withStateChanges(spec -> List.of(deleteTopic(nonexistentTopic(spec))))
                        .payingWith(SYSTEM_ADMIN)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("other payers may not submit errata")
    final Stream<DynamicTest> otherPayersMayNotSubmitErrata() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                stateErrata()
                        .withStateChanges(spec -> List.of(deleteTopic(nonexistentTopic(spec))))
                        .payingWith(CIVILIAN)
                        .hasPrecheck(NOT_SUPPORTED),
                // A system account outside the API permission range passes ingest but fails at consensus
                stateErrata()
                        .withStateChanges(spec -> List.of(deleteTopic(nonexistentTopic(spec))))
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .hasKnownStatus(UNAUTHORIZED));
    }

    @HapiTest
    @DisplayName("errata may not be inner transactions of an atomic batch")
    final Stream<DynamicTest> errataMayNotBeBatched() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HUNDRED_HBARS),
                atomicBatch(stateErrata()
                                .withStateChanges(spec -> List.of(deleteTopic(nonexistentTopic(spec))))
                                .payingWith(GENESIS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST));
    }

    @HapiTest
    @DisplayName("malformed or unsupported errata are rejected at ingest")
    final Stream<DynamicTest> malformedOrUnsupportedErrataAreRejectedAtIngest() {
        return hapiTest(
                stateErrata().payingWith(GENESIS).hasPrecheck(EMPTY_STATE_ERRATA),
                stateErrata()
                        .withSerializedStateChanges(Bytes.wrap(new byte[] {(byte) 0xFF, 0x01}))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_ERRATA_STATE_CHANGE),
                // States are only created by schema migrations
                stateErrata()
                        .withStateChanges(StateChange.newBuilder()
                                .stateId(STATE_ID_ACCOUNTS.protoOrdinal())
                                .stateAdd(NewStateChange.DEFAULT)
                                .build())
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_ERRATA_STATE_CHANGE),
                // A map update without a value
                stateErrata()
                        .withStateChanges(spec -> List.of(StateChange.newBuilder()
                                .stateId(STATE_ID_ACCOUNTS.protoOrdinal())
                                .mapUpdate(MapUpdateChange.newBuilder()
                                        .key(accountKey(nonexistentAccount(spec)))
                                        .build())
                                .build()))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_ERRATA_STATE_CHANGE));
    }

    @HapiTest
    @DisplayName("errata that do not match a state's definition are rejected")
    final Stream<DynamicTest> errataNotMatchingAStateDefinitionAreRejected() {
        return hapiTest(
                // An unknown state
                stateErrata()
                        .withStateChanges(spec -> List.of(StateChange.newBuilder()
                                .stateId(UNKNOWN_STATE_ID)
                                .mapDelete(new MapDeleteChange(accountKey(nonexistentAccount(spec))))
                                .build()))
                        .payingWith(GENESIS)
                        .hasKnownStatus(INVALID_ERRATA_STATE_CHANGE),
                // A map operation on a singleton state
                stateErrata()
                        .withStateChanges(spec -> List.of(StateChange.newBuilder()
                                .stateId(STATE_ID_ENTITY_COUNTS.protoOrdinal())
                                .mapDelete(new MapDeleteChange(accountKey(nonexistentAccount(spec))))
                                .build()))
                        .payingWith(GENESIS)
                        .hasKnownStatus(INVALID_ERRATA_STATE_CHANGE),
                // A key of the wrong type for the accounts state
                stateErrata()
                        .withStateChanges(StateChange.newBuilder()
                                .stateId(STATE_ID_ACCOUNTS.protoOrdinal())
                                .mapDelete(new MapDeleteChange(MapChangeKey.newBuilder()
                                        .tokenIdKey(TokenID.newBuilder().tokenNum(NONEXISTENT_ENTITY_NUM))
                                        .build()))
                                .build())
                        .payingWith(GENESIS)
                        .hasKnownStatus(INVALID_ERRATA_STATE_CHANGE));
    }

    /**
     * Corrects an account's memo with a state errata, after first showing that an errata with any invalid change
     * applies none of its changes; then verifies the correction is visible through queries and is externalized as
     * a state change in the block containing the errata.
     */
    @HapiTest
    @DisplayName("an errata corrects state atomically and externalizes the change")
    final Stream<DynamicTest> errataCorrectsStateAtomicallyAndExternalizesTheChange() {
        final var watcher = new AtomicReference<BlockWatcher>();
        final var created = new AtomicReference<Account>();
        final var correctedMemo = "corrected memo";
        return hapiTest(
                doingContextual(spec -> watcher.set(BlockWatcher.start(spec))),
                cryptoCreate("errataTarget").entityMemo("incorrect memo"),
                doingContextual(spec -> {
                    final var accountId = toPbj(spec.registry().getAccountID("errataTarget"));
                    final var location = watcher.get()
                            .awaitStateChange(
                                    change -> change.stateId() == STATE_ID_ACCOUNTS.protoOrdinal()
                                            && change.hasMapUpdate()
                                            && accountId.equals(change.mapUpdateOrThrow()
                                                    .keyOrThrow()
                                                    .accountIdKey()),
                                    BLOCK_TIMEOUT);
                    created.set(location.stateChange()
                            .mapUpdateOrThrow()
                            .valueOrThrow()
                            .accountValueOrThrow());
                }),
                // A valid change followed by an invalid one applies neither
                stateErrata()
                        .withStateChanges(spec -> List.of(
                                updateAccount(created.get()
                                        .copyBuilder()
                                        .memo(correctedMemo)
                                        .build()),
                                StateChange.newBuilder()
                                        .stateId(UNKNOWN_STATE_ID)
                                        .mapDelete(new MapDeleteChange(accountKey(nonexistentAccount(spec))))
                                        .build()))
                        .payingWith(GENESIS)
                        .hasKnownStatus(INVALID_ERRATA_STATE_CHANGE),
                getAccountInfo("errataTarget").has(accountWith().memo("incorrect memo")),
                stateErrata()
                        .withStateChanges(spec -> List.of(updateAccount(
                                created.get().copyBuilder().memo(correctedMemo).build())))
                        .payingWith(GENESIS)
                        .via("errata"),
                getAccountInfo("errataTarget").has(accountWith().memo(correctedMemo)),
                doingContextual(spec -> {
                    try (final var blocks = watcher.get()) {
                        final var errataTxnId = protoToPbj(spec.registry().getTxnId("errata"), TransactionID.class);
                        final var found = blocks.awaitTransaction(errataTxnId, BLOCK_TIMEOUT);
                        assertEquals(
                                com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS,
                                found.result().status());
                        final var accountId = created.get().accountIdOrThrow();
                        assertTrue(
                                found.stateChanges().stream()
                                        .filter(change -> change.stateId() == STATE_ID_ACCOUNTS.protoOrdinal()
                                                && change.hasMapUpdate())
                                        .map(change -> change.mapUpdateOrThrow())
                                        .anyMatch(update -> accountId.equals(
                                                        update.keyOrThrow().accountIdKey())
                                                && correctedMemo.equals(update.valueOrThrow()
                                                        .accountValueOrThrow()
                                                        .memo())),
                                "The errata's block should contain the corrected account as a state change");
                    }
                }));
    }

    private static AccountID nonexistentAccount(@NonNull final HapiSpec spec) {
        return AccountID.newBuilder()
                .shardNum(spec.shard())
                .realmNum(spec.realm())
                .accountNum(NONEXISTENT_ENTITY_NUM)
                .build();
    }

    private static TopicID nonexistentTopic(@NonNull final HapiSpec spec) {
        return TopicID.newBuilder()
                .shardNum(spec.shard())
                .realmNum(spec.realm())
                .topicNum(NONEXISTENT_ENTITY_NUM)
                .build();
    }

    private static MapChangeKey accountKey(@NonNull final AccountID accountId) {
        return MapChangeKey.newBuilder().accountIdKey(accountId).build();
    }

    /**
     * A harmless change: removing a topic that does not exist. (Removing accounts is not harmless, because record
     * finalization requires every modified account to still exist.)
     */
    private static StateChange deleteTopic(@NonNull final TopicID topicId) {
        return StateChange.newBuilder()
                .stateId(STATE_ID_TOPICS.protoOrdinal())
                .mapDelete(new MapDeleteChange(
                        MapChangeKey.newBuilder().topicIdKey(topicId).build()))
                .build();
    }

    private static StateChange updateAccount(@NonNull final Account account) {
        return StateChange.newBuilder()
                .stateId(STATE_ID_ACCOUNTS.protoOrdinal())
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(accountKey(account.accountIdOrThrow()))
                        .value(MapChangeValue.newBuilder().accountValue(account).build())
                        .build())
                .build();
    }
}
