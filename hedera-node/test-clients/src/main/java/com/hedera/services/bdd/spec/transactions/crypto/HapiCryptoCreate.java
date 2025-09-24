// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.crypto;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.asShard;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bannerWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.netOf;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoCreateMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.infrastructure.meta.InitialAccountIdentifiers;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.RealmID;
import com.hederahashgraph.api.proto.java.ShardID;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiCryptoCreate extends HapiTxnOp<HapiCryptoCreate> {
    static final Logger log = LogManager.getLogger(HapiCryptoCreate.class);

    private Key key;
    /**
     * when create account were used as an account, whether perform auto recharge when receiving
     * insufficient payer or insufficient transaction fee precheck
     */
    private boolean recharging = false;

    private boolean advertiseCreation = false;
    private boolean asCallableContract = false;
    private boolean forgettingEverything = false;
    /** The time window (unit of second) of not doing another recharge if just recharged recently */
    private Optional<Integer> rechargeWindow = Optional.empty();

    private final String account;
    private Optional<Long> sendThresh = Optional.empty();
    private Optional<Long> receiveThresh = Optional.empty();
    private Optional<Long> initialBalance = Optional.empty();
    private Optional<Long> autoRenewDurationSecs = Optional.empty();
    private Optional<String> proxy = Optional.empty();
    private Optional<Boolean> receiverSigRequired = Optional.empty();
    private Optional<String> keyName = Optional.empty();
    private Optional<String> entityMemo = Optional.empty();
    private Optional<KeyType> keyType = Optional.empty();
    private Optional<SigControl> keyShape = Optional.empty();
    private Optional<Function<HapiSpec, Long>> balanceFn = Optional.empty();
    private Optional<Integer> maxAutomaticTokenAssociations = Optional.empty();
    private Optional<Consumer<AccountID>> newAccountIdObserver = Optional.empty();
    private Optional<String> stakedAccountId = Optional.empty();
    private Optional<Long> stakedNodeId = Optional.empty();
    private boolean isDeclinedReward = false;
    private Optional<ByteString> alias = Optional.empty();
    private Optional<ByteString> evmAddress = Optional.empty();
    private Consumer<Address> addressObserver;
    private boolean fuzzingIdentifiers = false;
    private boolean setEvmAddressAliasFromKey = false;
    private Optional<ShardID> shardId = Optional.empty();
    private Optional<RealmID> realmId = Optional.empty();

    private List<Function<HapiSpec, HookCreationDetails>> hookFactories = List.of();

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.CryptoCreate;
    }

    @Override
    protected Key lookupKey(final HapiSpec spec, final String name) {
        return name.equals(account) ? key : spec.registry().getKey(name);
    }

    public HapiCryptoCreate fuzzingIdentifiersIfEcdsaKey(final boolean flag) {
        fuzzingIdentifiers = flag;
        return this;
    }

    public HapiCryptoCreate exposingCreatedIdTo(final Consumer<AccountID> newAccountIdObserver) {
        this.newAccountIdObserver = Optional.of(newAccountIdObserver);
        return this;
    }

    public HapiCryptoCreate exposingEvmAddressTo(final Consumer<Address> addressObserver) {
        this.addressObserver = addressObserver;
        return this;
    }

    public HapiCryptoCreate withHook(final Function<HapiSpec, HookCreationDetails> hookFactory) {
        if (this.hookFactories.isEmpty()) {
            this.hookFactories = new ArrayList<>();
        }
        this.hookFactories.add(hookFactory);
        return this;
    }

    public HapiCryptoCreate withHooks(final Function<HapiSpec, HookCreationDetails>... hooks) {
        if (this.hookFactories.isEmpty()) {
            this.hookFactories = new ArrayList<>();
        }
        this.hookFactories.addAll(Arrays.asList(hooks));
        return this;
    }

    public HapiCryptoCreate advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    public HapiCryptoCreate asCallableContract() {
        asCallableContract = true;
        return this;
    }

    public HapiCryptoCreate rememberingNothing() {
        forgettingEverything = true;
        return this;
    }

    public HapiCryptoCreate(final String account) {
        this.account = account;
    }

    public HapiCryptoCreate entityMemo(final String memo) {
        entityMemo = Optional.of(memo);
        return this;
    }

    public HapiCryptoCreate sendThreshold(final Long amount) {
        sendThresh = Optional.of(amount);
        return this;
    }

    public HapiCryptoCreate autoRenewSecs(final long time) {
        autoRenewDurationSecs = Optional.of(time);
        return this;
    }

    public HapiCryptoCreate receiveThreshold(final Long amount) {
        receiveThresh = Optional.of(amount);
        return this;
    }

    public HapiCryptoCreate receiverSigRequired(final boolean isRequired) {
        receiverSigRequired = Optional.of(isRequired);
        return this;
    }

    public HapiCryptoCreate balance(final Long amount) {
        initialBalance = Optional.of(amount);
        return this;
    }

    public HapiCryptoCreate maxAutomaticTokenAssociations(final int max) {
        maxAutomaticTokenAssociations = Optional.of(max);
        return this;
    }

    public HapiCryptoCreate balance(final Function<HapiSpec, Long> fn) {
        balanceFn = Optional.of(fn);
        return this;
    }

    public HapiCryptoCreate key(final String name) {
        keyName = Optional.of(name);
        return this;
    }

    public HapiCryptoCreate key(final Key key) {
        this.key = key;
        return this;
    }

    public HapiCryptoCreate keyType(final KeyType type) {
        keyType = Optional.of(type);
        return this;
    }

    public HapiCryptoCreate withRecharging() {
        recharging = true;
        return this;
    }

    public HapiCryptoCreate rechargeWindow(final int window) {
        rechargeWindow = Optional.of(window);
        return this;
    }

    public HapiCryptoCreate keyShape(final SigControl controller) {
        keyShape = Optional.of(controller);
        return this;
    }

    public HapiCryptoCreate proxy(final String acctNum) {
        proxy = Optional.of(acctNum);
        return this;
    }

    public HapiCryptoCreate stakedAccountId(final String acct) {
        stakedAccountId = Optional.of(acct);
        return this;
    }

    public HapiCryptoCreate stakedAccountId(final long acctNum) {
        stakedAccountId = Optional.of(Long.toString(acctNum));
        return this;
    }

    public HapiCryptoCreate stakedNodeId(final long acctNum) {
        stakedNodeId = Optional.of(acctNum);
        return this;
    }

    public HapiCryptoCreate declinedReward(final boolean isDeclined) {
        isDeclinedReward = isDeclined;
        return this;
    }

    public HapiCryptoCreate alias(final ByteString alias) {
        this.alias = Optional.of(alias);
        return this;
    }

    public HapiCryptoCreate shardId(final ShardID shardID) {
        this.shardId = Optional.of(shardID);
        return this;
    }

    public HapiCryptoCreate realmId(final RealmID realmID) {
        this.realmId = Optional.of(realmID);
        return this;
    }

    public HapiCryptoCreate evmAddress(final ByteString evmAddress) {
        this.evmAddress = Optional.of(evmAddress);
        return this;
    }

    public HapiCryptoCreate evmAddress(final Address evmAddress) {
        return evmAddress(ByteString.copyFrom(explicitFromHeadlong(evmAddress)));
    }

    @Override
    protected HapiCryptoCreate self() {
        return this;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return spec.fees().forActivityBasedOp(HederaFunctionality.CryptoCreate, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final var baseMeta = new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final var opMeta = new CryptoCreateMeta(txn.getCryptoCreateAccount());
        final var accumulator = new UsageAccumulator();
        cryptoOpsUsage.cryptoCreateUsage(suFrom(svo), baseMeta, opMeta, accumulator);
        return AdapterUtils.feeDataFrom(accumulator);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        key = key != null ? key : netOf(spec, keyName, keyShape, keyType);
        final long amount = balanceFn.map(fn -> fn.apply(spec)).orElse(initialBalance.orElse(-1L));
        initialBalance = (amount >= 0) ? Optional.of(amount) : Optional.empty();
        final CryptoCreateTransactionBody opBody = spec.txns()
                .<CryptoCreateTransactionBody, CryptoCreateTransactionBody.Builder>body(
                        CryptoCreateTransactionBody.class, b -> {
                            if (fuzzingIdentifiers && key.hasECDSASecp256K1()) {
                                InitialAccountIdentifiers.fuzzedFrom(spec, key).customize(this, b);
                            } else if (setEvmAddressAliasFromKey) {
                                final var congruentAddress = EthSigsUtils.recoverAddressFromPubKey(
                                        key.getECDSASecp256K1().toByteArray());
                                b.setKey(key);
                                b.setAlias(ByteString.copyFrom(congruentAddress));
                            } else {
                                if (alias.isPresent() || evmAddress.isPresent()) {
                                    keyName.ifPresent(
                                            s -> b.setKey(spec.registry().getKey(s)));
                                    alias.ifPresent(b::setAlias);
                                    evmAddress.ifPresent(b::setAlias);
                                } else {
                                    b.setKey(key);
                                }
                            }

                            if (unknownFieldLocation == UnknownFieldLocation.OP_BODY) {
                                b.setUnknownFields(nonEmptyUnknownFields());
                            }

                            final var effectiveShard = shardId.orElseGet(() -> asShard(spec.shard()));
                            final var effectiveRealm = realmId.orElseGet(() -> asRealm(spec.realm()));
                            proxy.ifPresent(p -> b.setStakedAccountId(asAccount(
                                    effectiveShard.getShardNum(), effectiveRealm.getRealmNum(), Long.parseLong(p))));
                            entityMemo.ifPresent(b::setMemo);
                            sendThresh.ifPresent(b::setSendRecordThreshold);
                            receiveThresh.ifPresent(b::setReceiveRecordThreshold);
                            initialBalance.ifPresent(b::setInitialBalance);
                            receiverSigRequired.ifPresent(b::setReceiverSigRequired);
                            autoRenewDurationSecs.ifPresent(s -> b.setAutoRenewPeriod(
                                    Duration.newBuilder().setSeconds(s).build()));
                            maxAutomaticTokenAssociations.ifPresent(b::setMaxAutomaticTokenAssociations);
                            shardId.ifPresent(b::setShardID);
                            realmId.ifPresent(b::setRealmID);
                            if (stakedAccountId.isPresent()) {
                                // Calculate and assign the effective staked account ID
                                AccountID effectiveStakedAcctId = TxnUtils.asId(stakedAccountId.get(), spec);
                                // If the calculated effective shard/realm doesn't match the spec's shard/realm,
                                // override the spec's values
                                if (spec.shard() != effectiveShard.getShardNum()
                                        || spec.realm() != effectiveRealm.getRealmNum()) {
                                    effectiveStakedAcctId = effectiveStakedAcctId.toBuilder()
                                            .setShardNum(effectiveShard.getShardNum())
                                            .setRealmNum(effectiveRealm.getRealmNum())
                                            .build();
                                }
                                b.setStakedAccountId(effectiveStakedAcctId);
                            } else if (stakedNodeId.isPresent()) {
                                b.setStakedNodeId(stakedNodeId.get());
                            }
                            b.setDeclineReward(isDeclinedReward);
                            hookFactories.forEach(factory -> b.addHookCreationDetails(CommonPbjConverters.pbjToProto(
                                    factory.apply(spec),
                                    HookCreationDetails.class,
                                    com.hedera.hapi.node.hooks.legacy.HookCreationDetails.class)));
                        });
        return b -> b.setCryptoCreateAccount(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return Arrays.asList(spec -> spec.registry().getKey(effectivePayer(spec)), ignore -> key);
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {
        if (actualStatus != SUCCESS || forgettingEverything) {
            return;
        }
        final var createdAccountId = lastReceipt.getAccountID();
        if (recharging) {
            spec.registry()
                    .setRecharging(account, initialBalance.orElse(spec.setup().defaultBalance()));
            if (rechargeWindow.isPresent()) {
                spec.registry().setRechargingWindow(account, rechargeWindow.get());
            }
        }
        spec.registry().saveKey(account, key);
        spec.registry().saveAccountId(account, createdAccountId);
        newAccountIdObserver.ifPresent(obs -> obs.accept(createdAccountId));
        receiverSigRequired.ifPresent(r -> spec.registry().saveSigRequirement(account, r));
        Optional.ofNullable(addressObserver)
                .ifPresent(obs -> evmAddress.ifPresentOrElse(
                        address -> obs.accept(HapiParserUtil.asHeadlongAddress(address.toByteArray())),
                        () -> obs.accept(idAsHeadlongAddress(lastReceipt.getAccountID()))));

        if (advertiseCreation) {
            final String banner = "\n\n"
                    + bannerWith(String.format(
                            "Created account '%s' with id '%s'.",
                            account, asAccountString(lastReceipt.getAccountID())));
            log.info(banner);
        }
        if (asCallableContract) {
            spec.registry()
                    .saveContractId(
                            account,
                            ContractID.newBuilder()
                                    .setShardNum(createdAccountId.getShardNum())
                                    .setRealmNum(createdAccountId.getRealmNum())
                                    .setContractNum(createdAccountId.getAccountNum())
                                    .build());
        }
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper().add("account", account);
        keyType.ifPresent(kt -> helper.add("keyType", kt));
        initialBalance.ifPresent(b -> helper.add("balance", b));
        Optional.ofNullable(lastReceipt).ifPresent(receipt -> {
            if (receipt.getAccountID().getAccountNum() != 0) {
                helper.add("created", receipt.getAccountID().getAccountNum());
            }
        });
        return helper;
    }

    public long numOfCreatedAccount() {
        return Optional.ofNullable(lastReceipt)
                .map(receipt -> receipt.getAccountID().getAccountNum())
                .orElse(-1L);
    }

    public Key getKey() {
        return key;
    }

    public HapiCryptoCreate withMatchingEvmAddress() {
        setEvmAddressAliasFromKey = true;
        return this;
    }
}
