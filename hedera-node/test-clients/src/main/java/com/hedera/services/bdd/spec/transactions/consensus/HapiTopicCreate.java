// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.consensus;

import static com.hedera.services.bdd.spec.HapiPropertySource.asTopicString;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asDuration;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bannerWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.netOf;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusCreateTopic;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.TOPIC_CREATE_WITH_CUSTOM_FEES;
import static java.util.stream.Collectors.toList;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.fee.ConsensusServiceFeeBuilder;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ConsensusCreateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.FixedCustomFee;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTopicCreate extends HapiTxnOp<HapiTopicCreate> {
    static final Logger log = LogManager.getLogger(HapiTopicCreate.class);

    private Key adminKey;
    private final String topic;
    private boolean advertiseCreation = false;
    private Optional<Key> submitKey = Optional.empty();
    private OptionalLong autoRenewPeriod = OptionalLong.empty();
    private Optional<String> topicMemo = Optional.empty();
    private Optional<String> adminKeyName = Optional.empty();
    private Optional<String> submitKeyName = Optional.empty();
    private Optional<String> autoRenewAccountId = Optional.empty();
    private Optional<KeyShape> adminKeyShape = Optional.empty();
    private Optional<KeyShape> submitKeyShape = Optional.empty();
    private Optional<Key> feeScheduleKey = Optional.empty();
    private Optional<String> feeScheduleKeyName = Optional.empty();
    private Optional<KeyShape> feeScheduleKeyShape = Optional.empty();
    private final List<Function<HapiSpec, FixedCustomFee>> feeScheduleSuppliers = new ArrayList<>();
    private Optional<List<Function<HapiSpec, Key>>> feeExemptKeyNamesList = Optional.empty();
    private Optional<List<Key>> feeExemptKeyList = Optional.empty();

    /** For some test we need the capability to build transaction has no autoRenewPeiord */
    private boolean clearAutoRenewPeriod = false;

    public HapiTopicCreate(final String topic) {
        this.topic = topic;
    }

    public HapiTopicCreate advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    public HapiTopicCreate topicMemo(final String s) {
        topicMemo = Optional.of(s);
        return this;
    }

    public HapiTopicCreate adminKeyName(final String s) {
        adminKeyName = Optional.of(s);
        return this;
    }

    public HapiTopicCreate submitKeyName(final String s) {
        submitKeyName = Optional.of(s);
        return this;
    }

    public HapiTopicCreate feeScheduleKeyName(final String s) {
        feeScheduleKeyName = Optional.of(s);
        return this;
    }

    public HapiTopicCreate feeExemptKeys(String... keys) {
        feeExemptKeyNamesList = Optional.of(Stream.of(keys)
                .<Function<HapiSpec, Key>>map(k -> spec -> spec.registry().getKey(k))
                .collect(toList()));
        return self();
    }

    public HapiTopicCreate feeExemptKeys(Key... keys) {
        feeExemptKeyNamesList = Optional.of(
                Stream.of(keys).<Function<HapiSpec, Key>>map(k -> spec -> k).collect(toList()));
        return self();
    }

    public HapiTopicCreate withConsensusCustomFee(final Function<HapiSpec, FixedCustomFee> supplier) {
        feeScheduleSuppliers.add(supplier);
        return this;
    }

    public HapiTopicCreate adminKeyShape(final KeyShape shape) {
        adminKeyShape = Optional.of(shape);
        return this;
    }

    public HapiTopicCreate submitKeyShape(final KeyShape shape) {
        submitKeyShape = Optional.of(shape);
        return this;
    }

    public HapiTopicCreate autoRenewAccountId(final String id) {
        autoRenewAccountId = Optional.of(id);
        return this;
    }

    public HapiTopicCreate autoRenewPeriod(final long secs) {
        autoRenewPeriod = OptionalLong.of(secs);
        return this;
    }

    public HapiTopicCreate clearAutoRenewPeriod() {
        this.clearAutoRenewPeriod = true;
        return this;
    }

    @Override
    protected Key lookupKey(final HapiSpec spec, final String name) {
        if (submitKey.isPresent() && (topic + "Submit").equals(name)) {
            return submitKey.get();
        } else {
            return name.equals(topic) ? adminKey : spec.registry().getKey(name);
        }
    }

    @Override
    public HederaFunctionality type() {
        return ConsensusCreateTopic;
    }

    @Override
    protected HapiTopicCreate self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        genKeysFor(spec);
        final ConsensusCreateTopicTransactionBody opBody = spec.txns()
                .<ConsensusCreateTopicTransactionBody, ConsensusCreateTopicTransactionBody.Builder>body(
                        ConsensusCreateTopicTransactionBody.class, b -> {
                            if (adminKey != null) {
                                b.setAdminKey(adminKey);
                            }
                            topicMemo.ifPresent(b::setMemo);
                            submitKey.ifPresent(b::setSubmitKey);
                            autoRenewAccountId.ifPresent(id -> b.setAutoRenewAccount(asId(id, spec)));
                            autoRenewPeriod.ifPresent(secs -> b.setAutoRenewPeriod(asDuration(secs)));
                            feeScheduleKey.ifPresent(b::setFeeScheduleKey);
                            feeExemptKeyList.ifPresent(keys -> keys.forEach(b::addFeeExemptKeyList));
                            if (!feeScheduleSuppliers.isEmpty()) {
                                for (final var supplier : feeScheduleSuppliers) {
                                    b.addCustomFees(supplier.apply(spec));
                                }
                            }
                            if (clearAutoRenewPeriod) {
                                b.clearAutoRenewPeriod();
                            }
                        });
        return b -> b.setConsensusCreateTopic(opBody);
    }

    private void genKeysFor(final HapiSpec spec) {
        if (adminKeyName.isPresent() || adminKeyShape.isPresent()) {
            adminKey = netOf(spec, adminKeyName, adminKeyShape);
        }

        if (submitKeyName.isPresent() || submitKeyShape.isPresent()) {
            submitKey = Optional.of(netOf(spec, submitKeyName, submitKeyShape));
        }

        if (feeScheduleKeyName.isPresent() || feeScheduleKeyShape.isPresent()) {
            feeScheduleKey = Optional.of(netOf(spec, feeScheduleKeyName, feeScheduleKeyShape));
        }

        feeExemptKeyNamesList.ifPresent(functions -> feeExemptKeyList = Optional.of(functions.stream()
                .map(f -> f.apply(spec))
                .filter(k -> k != null && k != Key.getDefaultInstance())
                .collect(toList())));
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final List<Function<HapiSpec, Key>> signers =
                new ArrayList<>(List.of(spec -> spec.registry().getKey(effectivePayer(spec))));
        Optional.ofNullable(adminKey).ifPresent(k -> signers.add(ignore -> k));
        autoRenewAccountId.ifPresent(id -> signers.add(spec -> spec.registry().getKey(id)));
        return signers;
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {
        if (actualStatus != SUCCESS) {
            return;
        }
        spec.registry().saveKey(topic, adminKey);
        submitKey.ifPresent(key -> spec.registry().saveKey(topic + "Submit", key));
        spec.registry().saveTopicId(topic, lastReceipt.getTopicID());
        spec.registry().saveBytes(topic, ByteString.copyFrom(new byte[48]));
        try {
            final TransactionBody txn = CommonUtils.extractTransactionBody(txnSubmitted);
            final long approxConsensusTime =
                    txn.getTransactionID().getTransactionValidStart().getSeconds() + 1;
            spec.registry().saveTopicMeta(topic, txn.getConsensusCreateTopic(), approxConsensusTime);
        } catch (final Exception impossible) {
            throw new IllegalStateException(impossible);
        }

        if (advertiseCreation) {
            final String banner = "\n\n"
                    + bannerWith(String.format(
                            "Created topic '%s' with id '%s'.", topic, asTopicString(lastReceipt.getTopicID())));
            log.info(banner);
        }
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        final var txnSubType = getTxnSubType(CommonUtils.extractTransactionBody(txn));
        return spec.fees()
                .forActivityBasedOp(
                        ConsensusCreateTopic,
                        txnSubType,
                        ConsensusServiceFeeBuilder::getConsensusCreateTopicFee,
                        txn,
                        numPayerKeys);
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper().add("topic", topic);
        autoRenewAccountId.ifPresent(id -> helper.add("autoRenewId", id));
        Optional.ofNullable(lastReceipt).ifPresent(receipt -> {
            if (receipt.getTopicID().getTopicNum() != 0) {
                helper.add("created", receipt.getTopicID().getTopicNum());
            }
        });
        return helper;
    }

    public long numOfCreatedTopic() {
        return Optional.ofNullable(lastReceipt)
                .map(receipt -> receipt.getTopicID().getTopicNum())
                .orElse(-1L);
    }

    private SubType getTxnSubType(final TransactionBody txn) {
        final var op = txn.getConsensusCreateTopic();
        final var usesCustomFees = !op.getCustomFeesList().isEmpty();
        if (usesCustomFees) {
            return TOPIC_CREATE_WITH_CUSTOM_FEES;
        } else {
            return DEFAULT;
        }
    }
}
