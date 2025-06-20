// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.consensus;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.ConsensusGetTopicInfoQuery;
import com.hederahashgraph.api.proto.java.ConsensusTopicInfo;
import com.hederahashgraph.api.proto.java.FixedCustomFee;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetTopicInfo extends HapiQueryOp<HapiGetTopicInfo> {
    private static final Logger log = LogManager.getLogger(HapiGetTopicInfo.class);

    private final String topic;
    private Optional<String> topicMemo = Optional.empty();
    private OptionalLong seqNo = OptionalLong.empty();
    private Optional<LongSupplier> seqNoFn = Optional.empty();
    private Optional<byte[]> runningHash = Optional.empty();
    private Optional<String> runningHashEntry = Optional.empty();
    private OptionalLong expiry = OptionalLong.empty();
    private OptionalLong autoRenewPeriod = OptionalLong.empty();
    private boolean hasNoAdminKey = false;
    private boolean hasNoSubmitKey = false;
    private boolean hasNoFeeScheduleKey = false;
    private Optional<String> adminKey = Optional.empty();
    private Optional<String> submitKey = Optional.empty();
    private Optional<String> autoRenewAccount = Optional.empty();
    private Optional<String> feeScheduleKey = Optional.empty();
    private final List<String> expectedFeeExemptKeyList = new ArrayList<>();
    private boolean expectFeeExemptKeyListEmpty = false;
    private final List<BiConsumer<HapiSpec, List<FixedCustomFee>>> expectedFees = new ArrayList<>();
    private boolean expectNoFees = false;
    private Optional<Integer> expectCustomFeeSize = Optional.empty();
    private boolean saveRunningHash = false;
    private Optional<LongConsumer> seqNoInfoObserver = Optional.empty();

    @Nullable
    private LongConsumer expiryObserver = null;

    public HapiGetTopicInfo(String topic) {
        this.topic = topic;
    }

    public HapiGetTopicInfo exposingExpiryTo(final LongConsumer observer) {
        expiryObserver = observer;
        return this;
    }

    public HapiGetTopicInfo hasMemo(String memo) {
        topicMemo = Optional.of(memo);
        return this;
    }

    public HapiGetTopicInfo hasSeqNo(long exp) {
        seqNo = OptionalLong.of(exp);
        return this;
    }

    public HapiGetTopicInfo hasSeqNo(LongSupplier supplier) {
        seqNoFn = Optional.of(supplier);
        return this;
    }

    public HapiGetTopicInfo hasRunningHash(byte[] exp) {
        runningHash = Optional.of(exp);
        return this;
    }

    public HapiGetTopicInfo hasRunningHash(String registryEntry) {
        runningHashEntry = Optional.of(registryEntry);
        return this;
    }

    public HapiGetTopicInfo hasExpiry(long exp) {
        expiry = OptionalLong.of(exp);
        return this;
    }

    public HapiGetTopicInfo hasAutoRenewPeriod(long exp) {
        autoRenewPeriod = OptionalLong.of(exp);
        return this;
    }

    public HapiGetTopicInfo hasAdminKey(String exp) {
        adminKey = Optional.of(exp);
        return this;
    }

    public HapiGetTopicInfo hasNoAdminKey() {
        hasNoAdminKey = true;
        return this;
    }

    public HapiGetTopicInfo hasNoSubmitKey() {
        hasNoSubmitKey = true;
        return this;
    }

    public HapiGetTopicInfo hasSubmitKey(String exp) {
        submitKey = Optional.of(exp);
        return this;
    }

    public HapiGetTopicInfo hasFeeScheduleKey(final String exp) {
        feeScheduleKey = Optional.of(exp);
        return this;
    }

    public HapiGetTopicInfo hasNoFeeScheduleKey() {
        hasNoFeeScheduleKey = true;
        return this;
    }

    public HapiGetTopicInfo hasAutoRenewAccount(String exp) {
        autoRenewAccount = Optional.of(exp);
        return this;
    }

    public HapiGetTopicInfo saveRunningHash() {
        saveRunningHash = true;
        return this;
    }

    public HapiGetTopicInfo savingSeqNoTo(LongConsumer consumer) {
        seqNoInfoObserver = Optional.of(consumer);
        return this;
    }

    public HapiGetTopicInfo hasFeeExemptKeys(List<String> feeExemptKeyAssertion) {
        expectedFeeExemptKeyList.addAll(feeExemptKeyAssertion);
        return this;
    }

    public HapiGetTopicInfo hasCustomFee(BiConsumer<HapiSpec, List<FixedCustomFee>> feeAssertion) {
        expectedFees.add(feeAssertion);
        return this;
    }

    public HapiGetTopicInfo hasNoCustomFee() {
        expectNoFees = true;
        return this;
    }

    public HapiGetTopicInfo hasCustomFeeSize(int size) {
        expectCustomFeeSize = Optional.of(size);
        return this;
    }

    public HapiGetTopicInfo hasEmptyFeeExemptKeyList() {
        expectFeeExemptKeyListEmpty = true;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ConsensusGetTopicInfo;
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        if (verboseLoggingOn) {
            String message = String.format(
                    "Info: %s", response.getConsensusGetTopicInfo().getTopicInfo());
            log.info(message);
        }
        if (saveRunningHash) {
            spec.registry()
                    .saveBytes(
                            topic,
                            response.getConsensusGetTopicInfo().getTopicInfo().getRunningHash());
        }
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) {
        ConsensusTopicInfo info = response.getConsensusGetTopicInfo().getTopicInfo();
        if (expiryObserver != null) {
            expiryObserver.accept(info.getExpirationTime().getSeconds());
        }
        topicMemo.ifPresent(exp -> assertEquals(exp, info.getMemo(), "Bad memo!"));
        seqNoFn.ifPresent(longSupplier -> seqNo = OptionalLong.of(longSupplier.getAsLong()));
        seqNo.ifPresent(exp -> assertEquals(exp, info.getSequenceNumber(), "Bad sequence number!"));
        seqNoInfoObserver.ifPresent(obs -> obs.accept(info.getSequenceNumber()));
        runningHashEntry.ifPresent(
                entry -> runningHash = Optional.of(spec.registry().getBytes(entry)));
        runningHash.ifPresent(
                exp -> assertArrayEquals(exp, info.getRunningHash().toByteArray(), "Bad running hash!"));
        expiry.ifPresent(exp -> assertEquals(exp, info.getExpirationTime().getSeconds(), "Bad expiry!"));
        autoRenewPeriod.ifPresent(
                exp -> assertEquals(exp, info.getAutoRenewPeriod().getSeconds(), "Bad auto-renew period!"));
        adminKey.ifPresent(exp -> assertEquals(spec.registry().getKey(exp), info.getAdminKey(), "Bad admin key!"));
        submitKey.ifPresent(exp -> assertEquals(spec.registry().getKey(exp), info.getSubmitKey(), "Bad submit key!"));
        feeScheduleKey.ifPresent(
                exp -> assertEquals(spec.registry().getKey(exp), info.getFeeScheduleKey(), "Bad fee schedule key!"));
        autoRenewAccount.ifPresent(
                exp -> assertEquals(asId(exp, spec), info.getAutoRenewAccount(), "Bad auto-renew account!"));
        if (hasNoAdminKey) {
            assertFalse(info.hasAdminKey(), "Should have no admin key!");
        }

        if (hasNoSubmitKey) {
            assertFalse(info.hasSubmitKey(), "Should have no submit key!");
        }
        if (hasNoFeeScheduleKey) {
            assertFalse(
                    info.hasFeeScheduleKey()
                            && info.getFeeScheduleKey().getKeyList().getKeysCount() > 0,
                    "Should have no fee schedule key!");
        }
        final var actualFees = info.getCustomFeesList();
        if (expectNoFees) {
            assertTrue(actualFees.isEmpty(), "Should have no custom fees");
        }
        expectCustomFeeSize.ifPresent(
                integer -> assertEquals((int) integer, actualFees.size(), "Custom fee size should be " + integer));
        for (var expectedFee : expectedFees) {
            expectedFee.accept(spec, actualFees);
        }
        var actualFeeExemptKeys = info.getFeeExemptKeyListList();
        for (var expectedKey : expectedFeeExemptKeyList) {
            assertTrue(
                    actualFeeExemptKeys.contains(spec.registry().getKey(expectedKey)),
                    "Doesn't contain free messages key!");
        }
        if (expectFeeExemptKeyListEmpty) {
            assertTrue(actualFeeExemptKeys.isEmpty(), "Should have no keys in fee except key list");
        }
        expectedLedgerId.ifPresent(id -> Assertions.assertEquals(id, info.getLedgerId()));
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getTopicInfoQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getTopicInfoQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        ConsensusGetTopicInfoQuery topicGetInfo = ConsensusGetTopicInfoQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setTopicID(TxnUtils.asTopicId(topic, spec))
                .build();
        return Query.newBuilder().setConsensusGetTopicInfo(topicGetInfo).build();
    }

    @Override
    protected HapiGetTopicInfo self() {
        return this;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("topic", topic);
    }
}
