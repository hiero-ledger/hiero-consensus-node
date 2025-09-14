// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.contract;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.FULL_PREFIXES;
import static com.hedera.services.bdd.spec.transactions.TxnFactory.expiryNowFor;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.contract.HapiContractCall.HEXED_EVM_ADDRESS_LEN;
import static com.hedera.services.bdd.spec.transactions.contract.HapiContractCreate.DEPRECATED_CID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ContractUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;

public class HapiContractUpdate extends HapiTxnOp<HapiContractUpdate> {
    static final Logger log = LogManager.getLogger(HapiContractUpdate.class);

    private final String contract;
    private boolean useDeprecatedAdminKey = false;
    private Optional<Long> newExpirySecs = Optional.empty();
    private OptionalLong newExpiryTime = OptionalLong.empty();
    private Optional<String> newKey = Optional.empty();
    private Optional<String> newMemo = Optional.empty();
    private Optional<Long> newAutoRenew = Optional.empty();
    private boolean wipeToThresholdKey = false;
    private boolean useEmptyAdminKeyList = false;
    private boolean useDeprecatedMemoField = false;
    private Optional<String> bytecode = Optional.empty();
    private Optional<String> newStakedAccountId = Optional.empty();
    private Optional<Long> newStakedNodeId = Optional.empty();
    private Optional<Boolean> newDeclinedReward = Optional.empty();
    private Optional<String> newProxy = Optional.empty();
    private Optional<String> newAutoRenewAccount = Optional.empty();
    private Optional<Integer> newMaxAutomaticAssociations = Optional.empty();
    private List<Long> hookIdsToDelete = List.of();
    private List<Function<HapiSpec, HookCreationDetails>> hookFactories = List.of();

    public HapiContractUpdate(String contract) {
        this.contract = contract;
        sigMapPrefixes(TrieSigMapGenerator.withNature(FULL_PREFIXES));
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ContractUpdate;
    }

    public HapiContractUpdate newKey(String name) {
        newKey = Optional.of(name);
        return this;
    }

    public HapiContractUpdate newMaxAutomaticAssociations(int max) {
        newMaxAutomaticAssociations = Optional.of(max);
        return this;
    }

    public HapiContractUpdate withHooks(final Function<HapiSpec, HookCreationDetails>... hookFactory) {
        if (this.hookFactories.isEmpty()) {
            this.hookFactories = new ArrayList<>();
        }
        this.hookFactories.addAll(List.of(hookFactory));
        return this;
    }

    public HapiContractUpdate removingHook(final long hookId) {
        if (this.hookIdsToDelete.isEmpty()) {
            this.hookIdsToDelete = new ArrayList<>();
        }
        this.hookIdsToDelete.add(hookId);
        return this;
    }

    public HapiContractUpdate newExpiryTime(long t) {
        newExpiryTime = OptionalLong.of(t);
        return this;
    }

    public HapiContractUpdate newProxy(String proxy) {
        newProxy = Optional.of(proxy);
        return this;
    }

    public HapiContractUpdate newExpirySecs(long t) {
        newExpirySecs = Optional.of(t);
        return this;
    }

    public HapiContractUpdate newMemo(String s) {
        newMemo = Optional.of(s);
        return this;
    }

    public HapiContractUpdate newAutoRenew(long autoRenewSecs) {
        newAutoRenew = Optional.of(autoRenewSecs);
        return this;
    }

    public HapiContractUpdate newAutoRenewAccount(String id) {
        newAutoRenewAccount = Optional.of(id);
        return this;
    }

    public HapiContractUpdate useDeprecatedAdminKey() {
        useDeprecatedAdminKey = true;
        return this;
    }

    public HapiContractUpdate useDeprecatedMemoField() {
        useDeprecatedMemoField = true;
        return this;
    }

    public HapiContractUpdate improperlyEmptyingAdminKey() {
        wipeToThresholdKey = true;
        return this;
    }

    public HapiContractUpdate properlyEmptyingAdminKey() {
        useEmptyAdminKeyList = true;
        return this;
    }

    public HapiContractUpdate bytecode(String bytecode) {
        this.bytecode = Optional.of(bytecode);
        return this;
    }

    public HapiContractUpdate newStakedAccountId(String idLit) {
        newStakedAccountId = Optional.of(idLit);
        return this;
    }

    public HapiContractUpdate newStakedNodeId(long idLit) {
        newStakedNodeId = Optional.of(idLit);
        return this;
    }

    public HapiContractUpdate newDeclinedReward(boolean isDeclined) {
        newDeclinedReward = Optional.of(isDeclined);
        return this;
    }

    @Override
    protected void updateStateOf(HapiSpec spec) throws Throwable {
        if (actualStatus != SUCCESS) {
            return;
        }
        if (!useDeprecatedAdminKey) {
            newKey.ifPresent(
                    k -> spec.registry().saveKey(contract, spec.registry().getKey(k)));
        }
        if (useEmptyAdminKeyList) {
            spec.registry().forgetRoleKey(contract, KeyRole.ADMIN);
        }
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        Optional<Key> key = newKey.map(spec.registry()::getKey);
        ContractUpdateTransactionBody opBody = spec.txns()
                .<ContractUpdateTransactionBody, ContractUpdateTransactionBody.Builder>body(
                        ContractUpdateTransactionBody.class, b -> {
                            if (contract.length() == HEXED_EVM_ADDRESS_LEN) {
                                b.setContractID(ContractID.newBuilder()
                                        .setEvmAddress(ByteString.copyFrom(CommonUtils.unhex(contract))));
                            } else {
                                b.setContractID(TxnUtils.asContractId(contract, spec));
                            }
                            newProxy.ifPresent(p -> b.setProxyAccountID(asId(p, spec)));
                            if (useDeprecatedAdminKey) {
                                b.setAdminKey(DEPRECATED_CID_ADMIN_KEY);
                            } else if (wipeToThresholdKey) {
                                b.setAdminKey(TxnUtils.EMPTY_THRESHOLD_KEY);
                            } else if (useEmptyAdminKeyList) {
                                b.setAdminKey(TxnUtils.EMPTY_KEY_LIST);
                            } else {
                                key.ifPresent(b::setAdminKey);
                            }
                            newExpirySecs.ifPresent(t -> b.setExpirationTime(
                                    Timestamp.newBuilder().setSeconds(t).build()));
                            newMemo.ifPresent(s -> {
                                if (useDeprecatedMemoField) {
                                    b.setMemo(s);
                                } else {
                                    b.setMemoWrapper(
                                            StringValue.newBuilder().setValue(s).build());
                                }
                            });
                            newAutoRenew.ifPresent(autoRenew -> b.setAutoRenewPeriod(
                                    Duration.newBuilder().setSeconds(autoRenew).build()));
                            bytecode.ifPresent(f -> b.setFileID(TxnUtils.asFileId(bytecode.get(), spec))
                                    .build());
                            newAutoRenewAccount.ifPresent(p -> b.setAutoRenewAccountId(asId(p, spec)));
                            newMaxAutomaticAssociations.ifPresent(
                                    p -> b.setMaxAutomaticTokenAssociations(Int32Value.of(p)));

                            if (newStakedAccountId.isPresent()) {
                                b.setStakedAccountId(asId(newStakedAccountId.get(), spec));
                            } else {
                                newStakedNodeId.ifPresent(b::setStakedNodeId);
                            }
                            newDeclinedReward.ifPresent(p -> b.setDeclineReward(BoolValue.of(p)));
                            hookIdsToDelete.forEach(b::addHookIdsToDelete);
                            hookFactories.forEach(factory -> b.addHookCreationDetails(pbjToProto(
                                    factory.apply(spec),
                                    HookCreationDetails.class,
                                    com.hedera.hapi.node.hooks.legacy.HookCreationDetails.class)));
                        });
        return builder -> builder.setContractUpdateInstance(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        List<Function<HapiSpec, Key>> signers = new ArrayList<>(oldDefaults());
        if (!useDeprecatedAdminKey && newKey.isPresent()) {
            signers.add(spec -> spec.registry().getKey(newKey.get()));
        }
        newAutoRenewAccount.ifPresent(id -> signers.add(spec -> spec.registry().getKey(id)));
        return signers;
    }

    private List<Function<HapiSpec, Key>> oldDefaults() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getKey(contract));
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        final var newExpiry =
                expiryNowFor(spec, newExpirySecs.orElse(spec.setup().defaultExpirationSecs()));
        Timestamp oldExpiry = TxnUtils.currContractExpiry(contract, spec);
        final Timestamp expiry = TxnUtils.inConsensusOrder(oldExpiry, newExpiry) ? newExpiry : oldExpiry;
        FeeCalculator.ActivityMetrics metricsCalc =
                (txBody, sigUsage) -> scFees.getContractUpdateTxFeeMatrices(txBody, expiry, sigUsage);
        return spec.fees().forActivityBasedOp(HederaFunctionality.ContractUpdate, metricsCalc, txn, numPayerKeys);
    }

    @Override
    protected HapiContractUpdate self() {
        return this;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("contract", contract);
    }
}
