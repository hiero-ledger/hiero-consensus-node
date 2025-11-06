// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.consensus;

import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.FULL_PREFIXES;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asDuration;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTimestamp;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTopicId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.netOf;
import static com.hedera.services.bdd.suites.HapiSuite.EMPTY_KEY;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusUpdateTopic;

import com.google.common.base.MoreObjects;
import com.google.protobuf.StringValue;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.fee.ConsensusServiceFeeBuilder;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ConsensusCreateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusUpdateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.FeeExemptKeyList;
import com.hederahashgraph.api.proto.java.FixedCustomFee;
import com.hederahashgraph.api.proto.java.FixedCustomFeeList;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class HapiTopicUpdate extends HapiTxnOp<HapiTopicUpdate> {
    private final String topic;

    private Optional<String> topicMemo = Optional.empty();
    private OptionalLong newExpiry = OptionalLong.empty();
    private OptionalLong newAutoRenewPeriod = OptionalLong.empty();
    private Optional<String> newAdminKeyName = Optional.empty();
    private Optional<Key> newAdminKey = Optional.empty();
    private Optional<String> newSubmitKeyName = Optional.empty();
    private Optional<Key> newSubmitKey = Optional.empty();
    private Optional<String> newAutoRenewAccount = Optional.empty();
    private Optional<Key> feeScheduleKey = Optional.empty();
    private Optional<String> feeScheduleKeyName = Optional.empty();
    private final List<Function<HapiSpec, FixedCustomFee>> feeScheduleSuppliers = new ArrayList<>();
    private Optional<List<Function<HapiSpec, Key>>> feeExemptKeyNamesList = Optional.empty();
    private Optional<List<Key>> freeMessageKeyList = Optional.empty();
    private boolean emptyCustomFee = false;
    private boolean emptyFeeExemptKeyList = false;
    private boolean emptyFeeScheduleKey = false;

    public HapiTopicUpdate(final String topic) {
        this.topic = topic;
        sigMapPrefixes(TrieSigMapGenerator.withNature(FULL_PREFIXES));
    }

    public HapiTopicUpdate topicMemo(final String s) {
        topicMemo = Optional.of(s);
        return this;
    }

    public HapiTopicUpdate adminKey(final String name) {
        newAdminKeyName = Optional.of(name);
        return this;
    }

    public HapiTopicUpdate adminKey(final Key key) {
        newAdminKey = Optional.of(key);
        return this;
    }

    public HapiTopicUpdate submitKey(final String name) {
        newSubmitKeyName = Optional.of(name);
        return this;
    }

    public HapiTopicUpdate submitKey(final Key key) {
        newSubmitKey = Optional.of(key);
        return this;
    }

    public HapiTopicUpdate expiry(final long at) {
        newExpiry = OptionalLong.of(at);
        return this;
    }

    public HapiTopicUpdate autoRenewPeriod(final long secs) {
        newAutoRenewPeriod = OptionalLong.of(secs);
        return this;
    }

    public HapiTopicUpdate autoRenewAccountId(final String id) {
        newAutoRenewAccount = Optional.of(id);
        return this;
    }

    public HapiTopicUpdate feeScheduleKeyName(final String s) {
        feeScheduleKeyName = Optional.of(s);
        return this;
    }

    public HapiTopicUpdate feeExemptKeys(String... keys) {
        feeExemptKeyNamesList = Optional.of(Stream.of(keys)
                .<Function<HapiSpec, Key>>map(k -> spec -> spec.registry().getKey(k))
                .toList());
        return this;
    }

    public HapiTopicUpdate withConsensusCustomFee(final Function<HapiSpec, FixedCustomFee> supplier) {
        feeScheduleSuppliers.add(supplier);
        return this;
    }

    public HapiTopicUpdate withEmptyCustomFee() {
        emptyCustomFee = true;
        return this;
    }

    public HapiTopicUpdate withEmptyFeeExemptKeyList() {
        emptyFeeExemptKeyList = true;
        return this;
    }

    public HapiTopicUpdate withEmptyFeeScheduleKey() {
        emptyFeeScheduleKey = true;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ConsensusUpdateTopic;
    }

    @Override
    protected HapiTopicUpdate self() {
        return this;
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {
        if (actualStatus != ResponseCodeEnum.SUCCESS) {
            return;
        }
        newAdminKey.ifPresent(k -> {
            if (newAdminKey.get() == EMPTY_KEY) {
                spec.registry().removeKey(topic);
            } else {
                spec.registry().saveKey(topic, k);
            }
        });
        newSubmitKey.ifPresent(k -> {
            if (newSubmitKey.get() == EMPTY_KEY) {
                spec.registry().removeKey(submitKeyName());
            } else {
                spec.registry().saveKey(submitKeyName(), k);
            }
        });
        try {
            final TransactionBody txn = CommonUtils.extractTransactionBody(txnSubmitted);
            spec.registry().saveTopicMeta(topic, txn.getConsensusUpdateTopic());
        } catch (final Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        genKeysFor(spec);
        final ConsensusUpdateTopicTransactionBody opBody = spec.txns()
                .<ConsensusUpdateTopicTransactionBody, ConsensusUpdateTopicTransactionBody.Builder>body(
                        ConsensusUpdateTopicTransactionBody.class, b -> {
                            b.setTopicID(asTopicId(topic, spec));
                            topicMemo.ifPresent(memo -> b.setMemo(StringValue.of(memo)));
                            newAdminKey.ifPresent(b::setAdminKey);
                            newSubmitKey.ifPresent(b::setSubmitKey);
                            newExpiry.ifPresent(s -> b.setExpirationTime(asTimestamp(s)));
                            newAutoRenewPeriod.ifPresent(s -> b.setAutoRenewPeriod(asDuration(s)));
                            newAutoRenewAccount.ifPresent(id -> b.setAutoRenewAccount(asId(id, spec)));
                            if (emptyFeeScheduleKey) {
                                b.setFeeScheduleKey(EMPTY_KEY);
                            } else {
                                feeScheduleKey.ifPresent(b::setFeeScheduleKey);
                            }
                            freeMessageKeyList.ifPresent(keys -> b.setFeeExemptKeyList(
                                    FeeExemptKeyList.newBuilder().addAllKeys(keys)));
                            if (emptyFeeExemptKeyList) {
                                b.setFeeExemptKeyList(FeeExemptKeyList.newBuilder());
                            }
                            if (!feeScheduleSuppliers.isEmpty()) {
                                var consensusCustomFeeList = FixedCustomFeeList.newBuilder();
                                for (final var supplier : feeScheduleSuppliers) {
                                    consensusCustomFeeList.addFees(supplier.apply(spec));
                                }
                                b.setCustomFees(consensusCustomFeeList.build());
                            } else if (emptyCustomFee) {
                                b.setCustomFees(FixedCustomFeeList.newBuilder().build());
                            }
                        });
        return b -> b.setConsensusUpdateTopic(opBody);
    }

    private void genKeysFor(HapiSpec spec) {
        newAdminKeyName.ifPresent(
                name -> newAdminKey = Optional.of(spec.registry().getKey(name)));
        newSubmitKeyName.ifPresent(
                name -> newSubmitKey = Optional.of(spec.registry().getKey(name)));

        if (feeScheduleKeyName.isPresent()) {
            feeScheduleKey = Optional.of(netOf(spec, feeScheduleKeyName, Optional.empty()));
        }

        feeExemptKeyNamesList.ifPresent(functions -> freeMessageKeyList = Optional.of(functions.stream()
                .map(f -> f.apply(spec))
                .filter(k -> k != null && k != Key.getDefaultInstance())
                .toList()));
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final List<Function<HapiSpec, Key>> signers = new ArrayList<>();
        signers.add(spec -> spec.registry().getKey(effectivePayer(spec)));
        signers.add(
                spec -> spec.registry().hasKey(topic)
                        ? spec.registry().getKey(topic)
                        : Key.getDefaultInstance() // same as no key
                );
        newAdminKey.ifPresent(key -> {
            if (key != EMPTY_KEY) {
                signers.add(ignored -> key);
            }
        });
        newAutoRenewAccount.ifPresent(id -> {
            var accountNum = id.split("\\.").length == 3 ? id.split("\\.")[2] : null;
            if (!"0".equals(accountNum)) {
                signers.add(spec -> spec.registry().getKey(id));
            }
        });
        return signers;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        if (!spec.registry().hasTopicMeta(topic)) {
            return spec.fees().maxFeeTinyBars();
        } else {
            /* Lookup topic metadata saved during creation. */
            final long oldExpiry = spec.registry().getTopicExpiry(topic);
            final ConsensusCreateTopicTransactionBody oldMeta = spec.registry().getTopicMeta(topic);

            /* Computed the increase in RBS due to this update. */
            long tentativeRbsIncrease = 0;
            try {
                final TransactionBody updateTxn = CommonUtils.extractTransactionBody(txn);
                tentativeRbsIncrease = ConsensusServiceFeeBuilder.getUpdateTopicRbsIncrease(
                        updateTxn.getTransactionID().getTransactionValidStart(),
                        oldMeta.getAdminKey(),
                        oldMeta.getSubmitKey(),
                        oldMeta.getMemo(),
                        oldMeta.hasAutoRenewAccount(),
                        Timestamp.newBuilder().setSeconds(oldExpiry).build(),
                        updateTxn.getConsensusUpdateTopic());
            } catch (final Exception impossible) {
                throw new IllegalStateException(impossible);
            }

            /* Create a custom activity metrics calculator based on the rbsIncrease. */
            final long rbsIncrease = tentativeRbsIncrease;
            final FeeCalculator.ActivityMetrics metricsCalc = (txBody, sigUsage) ->
                    ConsensusServiceFeeBuilder.getConsensusUpdateTopicFee(txBody, rbsIncrease, sigUsage);

            /* Return the net fee. */
            return spec.fees().forActivityBasedOp(ConsensusUpdateTopic, metricsCalc, txn, numPayerKeys);
        }
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper().add("topic", topic);
        newAutoRenewAccount.ifPresent(id -> helper.add("autoRenewId", id));
        return helper;
    }

    private String submitKeyName() {
        return topic + "Submit";
    }
}
