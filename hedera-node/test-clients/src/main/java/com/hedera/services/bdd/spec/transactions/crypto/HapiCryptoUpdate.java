// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.crypto;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.PropertySource.asAccountString;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.*;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.UInt64Value;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoUpdateMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.ExtantCryptoContext;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountInfo;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiCryptoUpdate extends HapiTxnOp<HapiCryptoUpdate> {
    static final Logger log = LogManager.getLogger(HapiCryptoUpdate.class);

    private boolean useContractKey = false;
    private boolean skipNewKeyRegistryUpdate = false;
    private boolean logUpdateDetailsToSysout = false;
    private String account;
    private String aliasKeySource = null;
    private OptionalLong sendThreshold = OptionalLong.empty();
    private Optional<Key> updKey = Optional.empty();
    private Optional<Key> protoKey = Optional.empty();
    private OptionalLong newExpiry = OptionalLong.empty();
    private OptionalLong newAutoRenewPeriod = OptionalLong.empty();
    private Optional<String> newProxy = Optional.empty();
    private Optional<String> entityMemo = Optional.empty();
    private Optional<String> updKeyName = Optional.empty();
    private Optional<Boolean> updSigRequired = Optional.empty();
    private Optional<Integer> newMaxAutomaticAssociations = Optional.empty();
    private Optional<String> newStakee = Optional.empty();
    private Optional<Long> newStakedNodeId = Optional.empty();
    private Optional<Boolean> isDeclinedReward = Optional.empty();

    private ReferenceType referenceType = ReferenceType.REGISTRY_NAME;

    public HapiCryptoUpdate(String account) {
        this.account = account;
    }

    public HapiCryptoUpdate(String reference, ReferenceType type) {
        this.referenceType = type;
        if (type == ReferenceType.ALIAS_KEY_NAME) {
            aliasKeySource = reference;
        } else {
            account = reference;
        }
    }

    public HapiCryptoUpdate withYahcliLogging() {
        logUpdateDetailsToSysout = true;
        return this;
    }

    public HapiCryptoUpdate notUpdatingRegistryWithNewKey() {
        skipNewKeyRegistryUpdate = true;
        return this;
    }

    public HapiCryptoUpdate sendThreshold(long v) {
        sendThreshold = OptionalLong.of(v);
        return this;
    }

    public HapiCryptoUpdate newProxy(String name) {
        newProxy = Optional.of(name);
        return this;
    }

    public HapiCryptoUpdate expiring(long at) {
        newExpiry = OptionalLong.of(at);
        return this;
    }

    public HapiCryptoUpdate autoRenewPeriod(long p) {
        newAutoRenewPeriod = OptionalLong.of(p);
        return this;
    }

    public HapiCryptoUpdate entityMemo(String memo) {
        entityMemo = Optional.of(memo);
        return this;
    }

    public HapiCryptoUpdate key(String name) {
        updKeyName = Optional.of(name);
        return this;
    }

    public HapiCryptoUpdate protoKey(Key key) {
        protoKey = Optional.of(key);
        return this;
    }

    public HapiCryptoUpdate receiverSigRequired(boolean isRequired) {
        updSigRequired = Optional.of(isRequired);
        return this;
    }

    public HapiCryptoUpdate usingContractKey() {
        useContractKey = true;
        return this;
    }

    public HapiCryptoUpdate maxAutomaticAssociations(int max) {
        newMaxAutomaticAssociations = Optional.of(max);
        return this;
    }

    public HapiCryptoUpdate newStakedAccountId(long stakee) {
        return newStakedAccountId(String.valueOf(stakee));
    }

    public HapiCryptoUpdate newStakedAccountId(String stakee) {
        newStakee = Optional.of(stakee);
        return this;
    }

    public HapiCryptoUpdate newStakedNodeId(long idLit) {
        newStakedNodeId = Optional.of(idLit);
        return this;
    }

    public HapiCryptoUpdate newDeclinedReward(boolean isDeclined) {
        isDeclinedReward = Optional.of(isDeclined);
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.CryptoUpdate;
    }

    @Override
    protected void updateStateOf(HapiSpec spec) {
        if (actualStatus != SUCCESS || skipNewKeyRegistryUpdate) {
            return;
        }
        updKey.ifPresent(k -> spec.registry().saveKey(account, k));
    }

    @Override
    @SuppressWarnings("java:S106")
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        try {
            updKey = updKeyName.map(spec.registry()::getKey);
        } catch (Exception missingKey) {
            log.warn("No such key {}", updKey);
        }
        AccountID id;

        if (referenceType == ReferenceType.REGISTRY_NAME) {
            final var maybeFqAcct =
                    (account.matches("\\d+")) ? asEntityString(spec.shard(), spec.realm(), account) : account;
            id = TxnUtils.asId(maybeFqAcct, spec);
        } else {
            id = asIdForKeyLookUp(aliasKeySource, spec);
            account = asAccountString(id);
        }

        CryptoUpdateTransactionBody opBody = spec.txns()
                .<CryptoUpdateTransactionBody, CryptoUpdateTransactionBody.Builder>body(
                        CryptoUpdateTransactionBody.class, builder -> {
                            builder.setAccountIDToUpdate(id);
                            newProxy.ifPresent(p -> {
                                var proxyId = TxnUtils.asId(p, spec);
                                builder.setProxyAccountID(proxyId);
                            });
                            updSigRequired.ifPresent(u -> builder.setReceiverSigRequiredWrapper(BoolValue.of(u)));
                            if (useContractKey) {
                                builder.setKey(
                                        Key.newBuilder().setContractID(HapiPropertySource.asContract("0.0.1234")));
                            } else {
                                updKey.ifPresent(builder::setKey);
                            }

                            protoKey.ifPresent(builder::setKey);

                            newAutoRenewPeriod.ifPresent(p -> builder.setAutoRenewPeriod(
                                    Duration.newBuilder().setSeconds(p)));
                            entityMemo.ifPresent(m -> builder.setMemo(
                                    StringValue.newBuilder().setValue(m).build()));
                            sendThreshold.ifPresent(v -> builder.setSendRecordThresholdWrapper(
                                    UInt64Value.newBuilder().setValue(v).build()));
                            newExpiry.ifPresent(l -> builder.setExpirationTime(
                                    Timestamp.newBuilder().setSeconds(l).build()));
                            newMaxAutomaticAssociations.ifPresent(
                                    p -> builder.setMaxAutomaticTokenAssociations(Int32Value.of(p)));

                            if (newStakee.isPresent()) {
                                var newStakeeId = newStakee.get();
                                if (!isIdLiteral(newStakeeId) && newStakeeId.matches("\\d+")) {
                                    newStakeeId = asEntityString(spec.shard(), spec.realm(), newStakeeId);
                                }
                                builder.setStakedAccountId(TxnUtils.asId(newStakeeId, spec));
                            } else if (newStakedNodeId.isPresent()) {
                                builder.setStakedNodeId(newStakedNodeId.get());
                            }
                            isDeclinedReward.ifPresent(b -> builder.setDeclineReward(BoolValue.of(b)));
                        });
        if (logUpdateDetailsToSysout) {
            System.out.println("\n---- Raw update ----\n");
            System.out.println(opBody);
            System.out.println("--------------------\n");
        }
        return builder -> builder.setCryptoUpdateAccount(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return defaultUpdateSigners(account, updKeyName, this::effectivePayer);
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        try {
            final CryptoGetInfoResponse.AccountInfo info = lookupInfo(spec);
            FeeCalculator.ActivityMetrics metricsCalc = (_txn, svo) -> {
                var ctx = ExtantCryptoContext.newBuilder()
                        .setCurrentNumTokenRels(info.getTokenRelationshipsCount())
                        .setCurrentExpiry(info.getExpirationTime().getSeconds())
                        .setCurrentMemo(info.getMemo())
                        .setCurrentKey(info.getKey())
                        .setCurrentlyHasProxy(info.hasProxyAccountID())
                        .setCurrentMaxAutomaticAssociations(info.getMaxAutomaticTokenAssociations())
                        // We can't get this information from the query, so leave it empty
                        .setCurrentCryptoAllowances(Collections.emptyList())
                        .setCurrentTokenAllowances(Collections.emptyMap())
                        .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                        .build();
                var baseMeta = new BaseTransactionMeta(_txn.getMemoBytes().size(), 0);
                var opMeta = new CryptoUpdateMeta(
                        _txn.getCryptoUpdateAccount(),
                        _txn.getTransactionID().getTransactionValidStart().getSeconds());
                var accumulator = new UsageAccumulator();
                // Once account auto-renew is enabled, we could instead leave the explicit auto-assoc lifetime at 0
                cryptoOpsUsage.cryptoUpdateUsage(suFrom(svo), baseMeta, opMeta, ctx, accumulator, 7776000L);
                return AdapterUtils.feeDataFrom(accumulator);
            };
            return spec.fees().forActivityBasedOp(HederaFunctionality.CryptoUpdate, metricsCalc, txn, numPayerKeys);
        } catch (Throwable ignore) {
            return HapiSuite.ONE_HBAR;
        }
    }

    @SuppressWarnings("java:S112")
    private CryptoGetInfoResponse.AccountInfo lookupInfo(HapiSpec spec) throws Throwable {
        HapiGetAccountInfo subOp = getAccountInfo(account).noLogging();
        Optional<Throwable> error = subOp.execFor(spec);
        if (error.isPresent()) {
            if (!loggingOff) {
                log.warn("Unable to look up current account info!", error.get());
            }
            throw error.get();
        }
        return subOp.getResponse().getCryptoGetInfo().getAccountInfo();
    }

    @Override
    protected HapiCryptoUpdate self() {
        return this;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("account", account);
    }
}
