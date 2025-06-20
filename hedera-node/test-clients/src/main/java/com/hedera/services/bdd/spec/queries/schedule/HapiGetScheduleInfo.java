// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.schedule;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleCreate.correspondingScheduledTxnId;
import static com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleCreate.getRelativeExpiry;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.ScheduleGetInfoQuery;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetScheduleInfo extends HapiQueryOp<HapiGetScheduleInfo> {
    private static final Logger LOG = LogManager.getLogger(HapiGetScheduleInfo.class);

    private static final Comparator<Key> KEY_COMPARATOR =
            (a, b) -> ByteString.unsignedLexicographicalComparator().compare(a.toByteString(), b.toByteString());

    String schedule;

    public HapiGetScheduleInfo(String schedule) {
        this.schedule = schedule;
    }

    boolean shouldBeExecuted = false;
    boolean shouldNotBeExecuted = false;
    boolean shouldNotBeDeleted = false;
    boolean checkForRecordedScheduledTxn = false;
    Optional<String> deletionTxn = Optional.empty();
    Optional<String> executionTxn = Optional.empty();
    Optional<String> expectedScheduleId = Optional.empty();
    Optional<Boolean> expectedWaitForExpiry = Optional.empty();
    Optional<Pair<String, Long>> expectedExpirationTimeRelativeTo = Optional.empty();
    Optional<String> expectedCreatorAccountID = Optional.empty();
    Optional<String> expectedPayerAccountID = Optional.empty();
    Optional<String> expectedScheduledTxnId = Optional.empty();
    Optional<String> expectedAdminKey = Optional.empty();
    Optional<String> expectedEntityMemo = Optional.empty();
    Optional<List<String>> expectedSignatories = Optional.empty();
    private long expectedExpiry = -1;

    public HapiGetScheduleInfo hasScheduledTxnIdSavedBy(String creation) {
        expectedScheduledTxnId = Optional.of(creation);
        return this;
    }

    public HapiGetScheduleInfo isExecuted() {
        shouldBeExecuted = true;
        return this;
    }

    public HapiGetScheduleInfo isNotExecuted() {
        shouldNotBeExecuted = true;
        return this;
    }

    public HapiGetScheduleInfo isNotDeleted() {
        shouldNotBeDeleted = true;
        return this;
    }

    public HapiGetScheduleInfo wasDeletedAtConsensusTimeOf(String txn) {
        deletionTxn = Optional.of(txn);
        return this;
    }

    public HapiGetScheduleInfo wasExecutedBy(String txn) {
        executionTxn = Optional.of(txn);
        return this;
    }

    public HapiGetScheduleInfo hasScheduleId(String s) {
        expectedScheduleId = Optional.of(s);
        return this;
    }

    public HapiGetScheduleInfo hasWaitForExpiry() {
        expectedWaitForExpiry = Optional.of(true);
        return this;
    }

    public HapiGetScheduleInfo hasWaitForExpiry(boolean value) {
        expectedWaitForExpiry = Optional.of(value);
        return this;
    }

    public HapiGetScheduleInfo hasExpiry(final long seconds) {
        this.expectedExpiry = seconds;
        return this;
    }

    public HapiGetScheduleInfo hasRelativeExpiry(String txnId, long offsetSeconds) {
        this.expectedExpirationTimeRelativeTo = Optional.of(Pair.of(txnId, offsetSeconds));
        return this;
    }

    public HapiGetScheduleInfo hasCreatorAccountID(String s) {
        expectedCreatorAccountID = Optional.of(s);
        return this;
    }

    public HapiGetScheduleInfo hasPayerAccountID(String s) {
        expectedPayerAccountID = Optional.of(s);
        return this;
    }

    public HapiGetScheduleInfo hasRecordedScheduledTxn() {
        checkForRecordedScheduledTxn = true;
        return this;
    }

    public HapiGetScheduleInfo hasAdminKey(String s) {
        expectedAdminKey = Optional.of(s);
        return this;
    }

    public HapiGetScheduleInfo hasEntityMemo(String s) {
        expectedEntityMemo = Optional.of(s);
        return this;
    }

    public HapiGetScheduleInfo hasSignatories(String... s) {
        expectedSignatories = Optional.of(List.of(s));
        return this;
    }

    @Override
    @SuppressWarnings("java:S5960")
    protected void assertExpectationsGiven(HapiSpec spec) {
        var actualInfo = response.getScheduleGetInfo().getScheduleInfo();

        expectedScheduledTxnId.ifPresent(n -> assertEquals(
                spec.registry().getTxnId(correspondingScheduledTxnId(n)),
                actualInfo.getScheduledTransactionID(),
                "Wrong scheduled transaction id!"));

        expectedCreatorAccountID.ifPresent(s -> assertEquals(
                TxnUtils.asId(s, spec), actualInfo.getCreatorAccountID(), "Wrong schedule creator account ID!"));

        expectedPayerAccountID.ifPresent(s -> assertEquals(
                TxnUtils.asId(s, spec), actualInfo.getPayerAccountID(), "Wrong schedule payer account ID!"));

        expectedEntityMemo.ifPresent(s -> assertEquals(s, actualInfo.getMemo(), "Wrong memo!"));

        if (checkForRecordedScheduledTxn) {
            assertEquals(
                    spec.registry().getScheduledTxn(schedule),
                    actualInfo.getScheduledTransactionBody(),
                    "Wrong scheduled txn!");
        }

        if (shouldBeExecuted) {
            Assertions.assertTrue(actualInfo.hasExecutionTime(), "Wasn't already executed!");
        }

        if (shouldNotBeExecuted) {
            Assertions.assertFalse(actualInfo.hasExecutionTime(), "Was already executed!");
        }

        if (shouldNotBeDeleted) {
            Assertions.assertFalse(actualInfo.hasDeletionTime(), "Was already deleted!");
        }

        if (deletionTxn.isPresent()) {
            assertTimestampMatches(
                    deletionTxn.get(), 0, actualInfo.getDeletionTime(), "Wrong consensus deletion time!", spec);
        }

        var registry = spec.registry();

        expectedSignatories.ifPresent(signatories -> {
            final var expect = KeyList.newBuilder();
            for (final var signatory : signatories) {
                accumulateSimple(registry.getKey(signatory), expect);
            }
            final List<Key> expectedKeys = new ArrayList<>(expect.getKeysList());
            expectedKeys.sort(KEY_COMPARATOR);
            final var actualKeys = new ArrayList<>(actualInfo.getSigners().getKeysList());
            actualKeys.sort(KEY_COMPARATOR);
            assertEquals(expectedKeys, actualKeys, "Wrong signatories");
        });

        if (expectedExpiry != -1) {
            assertEquals(expectedExpiry, actualInfo.getExpirationTime().getSeconds(), "Wrong expiration time");
        } else {
            expectedExpirationTimeRelativeTo.ifPresent(stringLongPair -> assertEquals(
                    getRelativeExpiry(spec, stringLongPair.getKey(), stringLongPair.getValue())
                            .getSeconds(),
                    actualInfo.getExpirationTime().getSeconds(),
                    "Wrong Expiration Time!"));
        }

        expectedWaitForExpiry.ifPresent(
                aBoolean -> assertEquals(aBoolean, actualInfo.getWaitForExpiry(), "waitForExpiry was wrong!"));

        assertFor(
                actualInfo.getAdminKey(),
                expectedAdminKey,
                (n, r) -> r.getRoleKey(schedule, KeyRole.ADMIN),
                "Wrong schedule admin key!",
                registry);

        expectedLedgerId.ifPresent(id -> assertEquals(id, actualInfo.getLedgerId()));
    }

    private static void accumulateSimple(@NonNull final Key key, @NonNull final KeyList.Builder builder) {
        if (key.hasEd25519() || key.hasECDSASecp256K1()) {
            builder.addKeys(key);
        } else if (key.hasKeyList()) {
            key.getKeyList().getKeysList().forEach(k -> accumulateSimple(k, builder));
        } else if (key.hasThresholdKey()) {
            key.getThresholdKey().getKeys().getKeysList().forEach(k -> accumulateSimple(k, builder));
        } else if (key.hasContractID()) {
            builder.addKeys(key);
        }
    }

    private void assertTimestampMatches(String txn, int nanoOffset, Timestamp actual, String errMsg, HapiSpec spec) {
        var subOp = getTxnRecord(txn);
        allRunFor(spec, subOp);
        var consensusTime = subOp.getResponseRecord().getConsensusTimestamp();
        var expected = Timestamp.newBuilder()
                .setSeconds(actual.getSeconds())
                .setNanos(consensusTime.getNanos() + nanoOffset)
                .build();
        assertEquals(expected, actual, errMsg);
    }

    private <T, R> void assertFor(
            R actual,
            Optional<T> possible,
            BiFunction<T, HapiSpecRegistry, R> expectedFn,
            String error,
            HapiSpecRegistry registry) {
        if (possible.isPresent()) {
            var expected = expectedFn.apply(possible.get(), registry);
            assertEquals(expected, actual, error);
        }
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        if (verboseLoggingOn) {
            LOG.info("Info for '{}': {}", () -> schedule, response.getScheduleGetInfo()::getScheduleInfo);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getScheduleInfoQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getScheduleInfoQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        var id = TxnUtils.asScheduleId(schedule, spec);
        ScheduleGetInfoQuery getScheduleQuery = ScheduleGetInfoQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setScheduleID(id)
                .build();
        return Query.newBuilder().setScheduleGetInfo(getScheduleQuery).build();
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ScheduleGetInfo;
    }

    @Override
    protected HapiGetScheduleInfo self() {
        return this;
    }
}
