// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBodyUnchecked;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTokenString;
import static com.hedera.services.bdd.spec.transactions.TxnFactory.defaultExpiryNowFor;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bannerWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsage;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.utils.KeyMetadata;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenCreate extends HapiTxnOp<HapiTokenCreate> {
    static final Logger log = LogManager.getLogger(HapiTokenCreate.class);

    public static final long WELL_KNOWN_INITIAL_SUPPLY = 123L * Integer.MAX_VALUE;
    public static final String WELL_KNOWN_NFT_SUPPLY_KEY = "wellKnownNftSupplyKey";

    private String token;

    private boolean advertiseCreation = false;
    private boolean asCallableContract = false;
    private boolean skipAutoRenewPeriod = false;
    private Optional<TokenType> tokenType = Optional.empty();
    private Optional<SubType> tokenSubType = Optional.empty();
    private Optional<TokenSupplyType> supplyType = Optional.empty();
    private OptionalInt decimals = OptionalInt.empty();
    private OptionalLong expiry = OptionalLong.empty();
    private OptionalLong initialSupply = OptionalLong.empty();
    private OptionalLong maxSupply = OptionalLong.empty();
    private OptionalLong autoRenewPeriod = OptionalLong.empty();
    private Optional<String> freezeKey = Optional.empty();
    private Optional<String> kycKey = Optional.empty();
    private Optional<String> wipeKey = Optional.empty();
    private Optional<String> supplyKey = Optional.empty();
    private Optional<String> contractKeyName = Optional.empty();
    private Set<TokenKeyType> contractKeyAppliedTo = Set.of();
    private Optional<String> feeScheduleKey = Optional.empty();
    private Optional<String> pauseKey = Optional.empty();
    private Optional<String> symbol = Optional.empty();
    private Optional<String> entityMemo = Optional.empty();
    private Optional<String> name = Optional.empty();
    private Optional<String> treasury = Optional.empty();
    private Optional<String> adminKey = Optional.empty();
    private Optional<Boolean> freezeDefault = Optional.empty();
    private Optional<String> autoRenewAccount = Optional.empty();
    private Optional<Consumer<String>> createdIdObs = Optional.empty();

    @Nullable
    private Consumer<Address> createdAddressObs;

    private Optional<Function<HapiSpec, String>> symbolFn = Optional.empty();
    private Optional<Function<HapiSpec, String>> nameFn = Optional.empty();
    private final List<Function<HapiSpec, CustomFee>> feeScheduleSuppliers = new ArrayList<>();
    private Optional<String> metadataKey = Optional.empty();

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenCreate;
    }

    public HapiTokenCreate(final String token) {
        this.token = token;
    }

    public void setTokenPrefix(final String prefix) {
        token = prefix + token;
    }

    public HapiTokenCreate withCustom(final Function<HapiSpec, CustomFee> supplier) {
        feeScheduleSuppliers.add(supplier);
        return this;
    }

    public HapiTokenCreate entityMemo(final String memo) {
        this.entityMemo = Optional.of(memo);
        return this;
    }

    public HapiTokenCreate exposingCreatedIdTo(final Consumer<String> obs) {
        createdIdObs = Optional.of(obs);
        return this;
    }

    public HapiTokenCreate exposingAddressTo(final Consumer<Address> obs) {
        createdAddressObs = obs;
        return this;
    }

    public HapiTokenCreate tokenType(final TokenType tokenType) {
        this.tokenType = Optional.of(tokenType);
        return this;
    }

    public HapiTokenCreate tokenSubType(final SubType tokenSubType) {
        this.tokenSubType = Optional.of(tokenSubType);
        return this;
    }

    public HapiTokenCreate supplyType(final TokenSupplyType supplyType) {
        this.supplyType = Optional.of(supplyType);
        return this;
    }

    public HapiTokenCreate advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    public HapiTokenCreate asCallableContract() {
        asCallableContract = true;
        return this;
    }

    public HapiTokenCreate skipAutoRenewPeriod() {
        skipAutoRenewPeriod = true;
        return this;
    }

    public HapiTokenCreate initialSupply(final long initialSupply) {
        this.initialSupply = OptionalLong.of(initialSupply);
        return this;
    }

    public HapiTokenCreate maxSupply(final long maxSupply) {
        this.maxSupply = OptionalLong.of(maxSupply);
        return this;
    }

    public HapiTokenCreate decimals(final int decimals) {
        this.decimals = OptionalInt.of(decimals);
        return this;
    }

    public HapiTokenCreate freezeDefault(final boolean frozenByDefault) {
        freezeDefault = Optional.of(frozenByDefault);
        return this;
    }

    public HapiTokenCreate freezeKey(final String name) {
        freezeKey = Optional.of(name);
        return this;
    }

    public HapiTokenCreate expiry(final long at) {
        expiry = OptionalLong.of(at);
        return this;
    }

    public HapiTokenCreate kycKey(final String name) {
        kycKey = Optional.of(name);
        return this;
    }

    public HapiTokenCreate wipeKey(final String name) {
        wipeKey = Optional.of(name);
        return this;
    }

    public HapiTokenCreate supplyKey(final String name) {
        supplyKey = Optional.of(name);
        return this;
    }

    public HapiTokenCreate contractKey(final Set<TokenKeyType> contractKeyAppliedTo, final String contractKeyName) {
        this.contractKeyName = Optional.of(contractKeyName);
        this.contractKeyAppliedTo = contractKeyAppliedTo;
        return this;
    }

    public HapiTokenCreate feeScheduleKey(final String name) {
        feeScheduleKey = Optional.of(name);
        return this;
    }

    public HapiTokenCreate pauseKey(final String name) {
        pauseKey = Optional.of(name);
        return this;
    }

    public HapiTokenCreate symbol(final String symbol) {
        this.symbol = Optional.of(symbol);
        return this;
    }

    public HapiTokenCreate symbol(final Function<HapiSpec, String> symbolFn) {
        this.symbolFn = Optional.of(symbolFn);
        return this;
    }

    public HapiTokenCreate name(final String name) {
        this.name = Optional.ofNullable(name);
        return this;
    }

    public HapiTokenCreate name(final Function<HapiSpec, String> nameFn) {
        this.nameFn = Optional.of(nameFn);
        return this;
    }

    public HapiTokenCreate adminKey(final String adminKeyName) {
        this.adminKey = Optional.of(adminKeyName);
        return this;
    }

    public HapiTokenCreate treasury(final String treasury) {
        this.treasury = Optional.of(treasury);
        return this;
    }

    public HapiTokenCreate autoRenewAccount(final String account) {
        this.autoRenewAccount = Optional.of(account);
        return this;
    }

    public HapiTokenCreate autoRenewPeriod(final long secs) {
        this.autoRenewPeriod = OptionalLong.of(secs);
        return this;
    }

    public HapiTokenCreate metadataKey(final String metadataKeyName) {
        this.metadataKey = Optional.of(metadataKeyName);
        return this;
    }

    @Override
    protected HapiTokenCreate self() {
        return this;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        final var txnSubType = getTxnSubType(CommonUtils.extractTransactionBody(txn));
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.TokenCreate, txnSubType, this::usageEstimate, txn, numPayerKeys);
    }

    private SubType getTxnSubType(final TransactionBody txn) {
        final var op = txn.getTokenCreation();
        final SubType chosenType;
        final var usesCustomFees = op.hasFeeScheduleKey() || op.getCustomFeesCount() > 0;
        if (op.getTokenType() == NON_FUNGIBLE_UNIQUE) {
            chosenType = usesCustomFees ? TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES : TOKEN_NON_FUNGIBLE_UNIQUE;
        } else {
            chosenType = usesCustomFees ? TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES : TOKEN_FUNGIBLE_COMMON;
        }
        return chosenType;
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final var accumulator = new UsageAccumulator();
        final var tokenCreateMeta = TOKEN_OPS_USAGE_UTILS.tokenCreateUsageFrom(txn);
        final var baseTransactionMeta =
                new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final TokenOpsUsage tokenOpsUsage = new TokenOpsUsage();
        tokenOpsUsage.tokenCreateUsage(suFrom(svo), baseTransactionMeta, tokenCreateMeta, accumulator);
        return AdapterUtils.feeDataFrom(accumulator);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        if (symbolFn.isPresent()) {
            symbol = Optional.of(symbolFn.get().apply(spec));
        }
        if (nameFn.isPresent()) {
            name = Optional.of(nameFn.get().apply(spec));
        }
        final TokenCreateTransactionBody opBody = spec.txns()
                .<TokenCreateTransactionBody, TokenCreateTransactionBody.Builder>body(
                        TokenCreateTransactionBody.class, b -> {
                            tokenType.ifPresent(b::setTokenType);
                            supplyType.ifPresent(b::setSupplyType);
                            symbol.ifPresent(b::setSymbol);
                            name.ifPresent(b::setName);
                            // If the token doesn't have an explicit memo (which
                            // should help us correlate any related failures to the
                            // originating spec), then we set the spec name as the
                            // memo for this purpose
                            entityMemo.ifPresentOrElse(
                                    b::setMemo,
                                    () -> b.setMemo(spec.getName()
                                            .substring(
                                                    0,
                                                    Math.min(100, spec.getName().length()))));
                            metadata.ifPresent(s -> b.setMetadata(ByteString.copyFromUtf8(s)));
                            initialSupply.ifPresent(b::setInitialSupply);
                            maxSupply.ifPresent(b::setMaxSupply);
                            decimals.ifPresent(b::setDecimals);
                            freezeDefault.ifPresent(b::setFreezeDefault);
                            adminKey.ifPresent(
                                    k -> b.setAdminKey(spec.registry().getKey(k)));
                            freezeKey.ifPresent(
                                    k -> b.setFreezeKey(spec.registry().getKey(k)));
                            supplyKey.ifPresent(
                                    k -> b.setSupplyKey(spec.registry().getKey(k)));
                            feeScheduleKey.ifPresent(
                                    k -> b.setFeeScheduleKey(spec.registry().getKey(k)));
                            pauseKey.ifPresent(
                                    k -> b.setPauseKey(spec.registry().getKey(k)));
                            wipeKey.ifPresent(k -> b.setWipeKey(spec.registry().getKey(k)));
                            kycKey.ifPresent(k -> b.setKycKey(spec.registry().getKey(k)));
                            metadataKey.ifPresent(
                                    k -> b.setMetadataKey(spec.registry().getKey(k)));
                            if (autoRenewAccount.isPresent()) {
                                final var id = TxnUtils.asId(autoRenewAccount.get(), spec);
                                b.setAutoRenewAccount(id);
                                if (autoRenewPeriod.isEmpty() && !skipAutoRenewPeriod) {
                                    b.setAutoRenewPeriod(spec.setup().defaultAutoRenewPeriod());
                                }
                            }
                            autoRenewPeriod.ifPresent(p -> b.setAutoRenewPeriod(
                                    Duration.newBuilder().setSeconds(p).build()));
                            if (autoRenewPeriod.isEmpty()) {
                                expiry.ifPresentOrElse(
                                        t -> b.setExpiry(Timestamp.newBuilder()
                                                .setSeconds(t)
                                                .build()),
                                        () -> b.setExpiry(defaultExpiryNowFor(spec)));
                            }
                            if (treasury.isEmpty()) {
                                treasury = Optional.of(spec.setup().defaultPayerName());
                            }
                            treasury.ifPresent(a -> {
                                final var treasuryId = TxnUtils.asId(a, spec);
                                b.setTreasury(treasuryId);
                            });
                            if (!feeScheduleSuppliers.isEmpty()) {
                                for (final var supplier : feeScheduleSuppliers) {
                                    b.addCustomFees(supplier.apply(spec));
                                }
                            }
                            // We often want to use an existing contract to control the keys of various types (supply,
                            // freeze etc.)
                            // of a token, and in this case we need to use a Key{contractID=0.0.X} as the key; so for
                            // convenience we have a special case and allow the user to specify the name of the
                            // contract it should use from the registry to create this special key.
                            if (contractKeyName.isPresent() && !contractKeyAppliedTo.isEmpty()) {
                                final var contractId = spec.registry().getContractId(contractKeyName.get());
                                final var contractKey = Key.newBuilder()
                                        .setContractID(contractId)
                                        .build();
                                for (final var tokenKeyType : contractKeyAppliedTo) {
                                    switch (tokenKeyType) {
                                        case ADMIN_KEY -> b.setAdminKey(contractKey);
                                        case FREEZE_KEY -> b.setFreezeKey(contractKey);
                                        case KYC_KEY -> b.setKycKey(contractKey);
                                        case PAUSE_KEY -> b.setPauseKey(contractKey);
                                        case SUPPLY_KEY -> b.setSupplyKey(contractKey);
                                        case WIPE_KEY -> b.setWipeKey(contractKey);
                                        case METADATA_KEY -> b.setMetadataKey(contractKey);
                                        default ->
                                            throw new IllegalStateException("Unexpected tokenKeyType: " + tokenKeyType);
                                    }
                                }
                            }
                        });
        return b -> b.setTokenCreation(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final List<Function<HapiSpec, Key>> signers =
                new ArrayList<>(List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                        .getKey(treasury.orElseGet(spec.setup()::defaultPayerName))));
        adminKey.ifPresent(k -> signers.add(spec -> spec.registry().getKey(k)));
        freezeKey.ifPresent(k -> signers.add(spec -> spec.registry().getKey(k)));
        autoRenewAccount.ifPresent(k -> signers.add(spec -> spec.registry().getKey(k)));
        return signers;
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {
        if (actualStatus != SUCCESS) {
            return;
        }
        final TokenID tokenID = lastReceipt.getTokenID();
        createdIdObs.ifPresent(obs -> obs.accept(HapiPropertySource.asTokenString(tokenID)));
        Optional.ofNullable(createdAddressObs).ifPresent(obs -> obs.accept(idAsHeadlongAddress(tokenID)));
        registerKeys(spec);
        registerAttributes(spec);
        if (advertiseCreation) {
            final String banner = "\n\n"
                    + bannerWith(String.format("Created token '%s' with id '%s'.", token, asTokenString(tokenID)));
            log.info(banner);
        }
        if (asCallableContract) {
            spec.registry()
                    .saveContractId(
                            token,
                            ContractID.newBuilder()
                                    .setShardNum(tokenID.getShardNum())
                                    .setRealmNum(tokenID.getRealmNum())
                                    .setContractNum(tokenID.getTokenNum())
                                    .build());
        }
    }

    public long numOfCreatedTokenOrThrow() {
        return Optional.ofNullable(lastReceipt)
                .map(receipt -> receipt.getTokenID().getTokenNum())
                .orElseThrow();
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper().add("token", token);
        Optional.ofNullable(lastReceipt).ifPresent(receipt -> {
            if (receipt.getTokenID().getTokenNum() != 0) {
                helper.add("created", receipt.getTokenID().getTokenNum());
            }
        });
        return helper;
    }

    private void registerKeys(@NonNull final HapiSpec spec) {
        if (lastReceipt == null) {
            throw new IllegalStateException("Token has not been created");
        }
        final var registry = spec.registry();
        final var submittedBody = extractTransactionBodyUnchecked(txnSubmitted);
        final var op = submittedBody.getTokenCreation();

        for (KeyRole role : KeyRole.values()) {
            final var key = getKeyFromOp(op, role);
            if (key != null) {
                registry.saveRoleKey(token, role, key);
            }
        }
    }

    public void registerAttributes(@NonNull final HapiSpec spec) {
        if (lastReceipt == null || treasury.isEmpty()) {
            throw new IllegalStateException("Token has not been created");
        }
        final var registry = spec.registry();
        symbol.ifPresent(s -> registry.saveSymbol(token, s));
        name.ifPresent(s -> registry.saveName(token, s));
        registry.saveMemo(token, memo.orElse(""));
        registry.saveMetadata(token, metadata.orElse(""));
        final TokenID tokenID = lastReceipt.getTokenID();
        registry.saveTokenId(token, tokenID);
        registry.saveTreasury(token, treasury.get());
    }

    public List<KeyMetadata> allCreatedKeyMetadata(@NonNull final HapiSpec spec) {
        final List<KeyMetadata> metadata = new ArrayList<>();
        final var submittedBody = extractTransactionBodyUnchecked(txnSubmitted);
        final var op = submittedBody.getTokenCreation();

        for (KeyRole role : KeyRole.values()) {
            final var key = getKeyFromOp(op, role);
            if (key != null) {
                metadata.add(KeyMetadata.from(key, spec, KeyMetadata.roleBasedRegistration(role)));
            }
        }

        return metadata;
    }

    private Key getKeyFromOp(TokenCreateTransactionBody op, KeyRole role) {
        return switch (role) {
            case KYC -> op.hasKycKey() ? op.getKycKey() : null;
            case WIPE -> op.hasWipeKey() ? op.getWipeKey() : null;
            case ADMIN -> op.hasAdminKey() ? op.getAdminKey() : null;
            case SUPPLY -> op.hasSupplyKey() ? op.getSupplyKey() : null;
            case FREEZE -> op.hasFreezeKey() ? op.getFreezeKey() : null;
            case FEE_SCHEDULE -> op.hasFeeScheduleKey() ? op.getFeeScheduleKey() : null;
            case PAUSE -> op.hasPauseKey() ? op.getPauseKey() : null;
            case METADATA -> op.hasMetadataKey() ? op.getMetadataKey() : null;
        };
    }
}
