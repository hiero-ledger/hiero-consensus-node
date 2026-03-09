// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.StringValue;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.token.TokenUpdateUsage;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.RegistryNotFound;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.TokenKeyValidation;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenUpdate extends HapiTxnOp<HapiTokenUpdate> {
    static final Logger log = LogManager.getLogger(HapiTokenUpdate.class);

    private String token;

    private OptionalLong expiry = OptionalLong.empty();
    private OptionalLong autoRenewPeriod = OptionalLong.empty();
    private Optional<String> newMemo = Optional.empty();
    private Optional<String> newAdminKey = Optional.empty();
    private Optional<String> newKycKey = Optional.empty();
    private Optional<String> newWipeKey = Optional.empty();
    private Optional<String> newSupplyKey = Optional.empty();
    private Optional<String> newFreezeKey = Optional.empty();
    private Optional<String> newFeeScheduleKey = Optional.empty();
    private Optional<String> newPauseKey = Optional.empty();
    private Optional<String> newMetadataKey = Optional.empty();
    private Optional<String> newMetadata = Optional.empty();

    private Optional<String> newSymbol = Optional.empty();
    private Optional<String> newName = Optional.empty();
    private Optional<String> newTreasury = Optional.empty();
    private Optional<String> autoRenewAccount = Optional.empty();
    private Optional<Function<HapiSpec, Key>> newSupplyKeyFunction = Optional.empty();
    private Optional<Function<HapiSpec, String>> newSymbolFn = Optional.empty();
    private Optional<Function<HapiSpec, String>> newNameFn = Optional.empty();
    private boolean useEmptyAdminKey = false;
    private boolean useEmptyWipeKey = false;
    private boolean useEmptyKycKey = false;
    private boolean useEmptySupplyKey = false;
    private boolean useEmptyFreezeKey = false;
    private boolean useEmptyFeeScheduleKey = false;
    private boolean useEmptyMetadataKey = false;
    private boolean useEmptyPauseKey = false;
    private boolean useInvalidAdminKey = false;
    private boolean useInvalidWipeKey = false;
    private boolean useInvalidKycKey = false;
    private boolean useInvalidSupplyKey = false;
    private boolean useInvalidFreezeKey = false;
    private boolean useInvalidFeeScheduleKey = false;
    private boolean useInvalidMetadataKey = false;
    private boolean useInvalidPauseKey = false;
    private boolean noKeyValidation = false;
    private Optional<String> contractKeyName = Optional.empty();
    private Set<TokenKeyType> contractKeyAppliedTo = Set.of();

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenUpdate;
    }

    public HapiTokenUpdate(String token) {
        this.token = token;
    }

    public HapiTokenUpdate freezeKey(String name) {
        newFreezeKey = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate kycKey(String name) {
        newKycKey = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate wipeKey(String name) {
        newWipeKey = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate supplyKey(String name) {
        newSupplyKey = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate supplyKey(Function<HapiSpec, Key> supplyKeyFn) {
        newSupplyKeyFunction = Optional.of(supplyKeyFn);
        return this;
    }

    public HapiTokenUpdate feeScheduleKey(String name) {
        newFeeScheduleKey = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate pauseKey(String name) {
        newPauseKey = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate metadataKey(String name) {
        newMetadataKey = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate newMetadata(String name) {
        newMetadata = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate entityMemo(String memo) {
        this.newMemo = Optional.of(memo);
        return this;
    }

    public HapiTokenUpdate symbol(String symbol) {
        this.newSymbol = Optional.of(symbol);
        return this;
    }

    public HapiTokenUpdate symbol(Function<HapiSpec, String> symbolFn) {
        this.newSymbolFn = Optional.of(symbolFn);
        return this;
    }

    public HapiTokenUpdate name(String name) {
        this.newName = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate name(Function<HapiSpec, String> nameFn) {
        this.newNameFn = Optional.of(nameFn);
        return this;
    }

    public HapiTokenUpdate adminKey(String name) {
        newAdminKey = Optional.of(name);
        return this;
    }

    public HapiTokenUpdate treasury(String treasury) {
        this.newTreasury = Optional.of(treasury);
        return this;
    }

    public HapiTokenUpdate autoRenewAccount(String account) {
        this.autoRenewAccount = Optional.of(account);
        return this;
    }

    public HapiTokenUpdate autoRenewPeriod(long secs) {
        this.autoRenewPeriod = OptionalLong.of(secs);
        return this;
    }

    public HapiTokenUpdate expiry(long at) {
        this.expiry = OptionalLong.of(at);
        return this;
    }

    public HapiTokenUpdate properlyEmptyingAdminKey() {
        useEmptyAdminKey = true;
        return this;
    }

    public HapiTokenUpdate properlyEmptyingWipeKey() {
        useEmptyWipeKey = true;
        return this;
    }

    public HapiTokenUpdate properlyEmptyingKycKey() {
        useEmptyKycKey = true;
        return this;
    }

    public HapiTokenUpdate properlyEmptyingSupplyKey() {
        useEmptySupplyKey = true;
        return this;
    }

    public HapiTokenUpdate properlyEmptyingFreezeKey() {
        useEmptyFreezeKey = true;
        return this;
    }

    public HapiTokenUpdate properlyEmptyingPauseKey() {
        useEmptyPauseKey = true;
        return this;
    }

    public HapiTokenUpdate properlyEmptyingFeeScheduleKey() {
        useEmptyFeeScheduleKey = true;
        return this;
    }

    public HapiTokenUpdate properlyEmptyingMetadataKey() {
        useEmptyMetadataKey = true;
        return this;
    }

    public HapiTokenUpdate usingInvalidAdminKey() {
        useInvalidAdminKey = true;
        return this;
    }

    public HapiTokenUpdate usingInvalidFeeScheduleKey() {
        useInvalidFeeScheduleKey = true;
        return this;
    }

    public HapiTokenUpdate usingInvalidMetadataKey() {
        useInvalidMetadataKey = true;
        return this;
    }

    public HapiTokenUpdate usingInvalidPauseKey() {
        useInvalidPauseKey = true;
        return this;
    }

    public HapiTokenUpdate applyNoValidationToKeys() {
        noKeyValidation = true;
        return this;
    }

    public HapiTokenUpdate contractKey(final Set<TokenKeyType> contractKeyAppliedTo, final String contractKeyName) {
        this.contractKeyName = Optional.of(contractKeyName);
        this.contractKeyAppliedTo = contractKeyAppliedTo;
        return this;
    }

    @Override
    protected HapiTokenUpdate self() {
        return this;
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        try {
            final TokenInfo info = HapiTokenFeeScheduleUpdate.lookupInfo(spec, token, log, loggingOff);
            FeeCalculator.ActivityMetrics metricsCalc = (_txn, svo) -> {
                var estimate =
                        TokenUpdateUsage.newEstimate(_txn, new TxnUsageEstimator(suFrom(svo), _txn, ESTIMATOR_UTILS));
                estimate.givenCurrentExpiry(info.getExpiry().getSeconds())
                        .givenCurrentMemo(info.getMemo())
                        .givenCurrentName(info.getName())
                        .givenCurrentSymbol(info.getSymbol());
                if (info.hasFreezeKey()) {
                    estimate.givenCurrentFreezeKey(Optional.of(info.getFreezeKey()));
                }
                if (info.hasAdminKey()) {
                    estimate.givenCurrentAdminKey(Optional.of(info.getAdminKey()));
                }
                if (info.hasSupplyKey()) {
                    estimate.givenCurrentSupplyKey(Optional.of(info.getSupplyKey()));
                }
                if (info.hasKycKey()) {
                    estimate.givenCurrentKycKey(Optional.of(info.getKycKey()));
                }
                if (info.hasWipeKey()) {
                    estimate.givenCurrentWipeKey(Optional.of(info.getWipeKey()));
                }
                if (info.hasFeeScheduleKey()) {
                    estimate.givenCurrentFeeScheduleKey(Optional.of(info.getFeeScheduleKey()));
                }
                if (info.hasPauseKey()) {
                    estimate.givenCurrentPauseKey(Optional.of(info.getPauseKey()));
                }
                if (info.hasAutoRenewAccount()) {
                    estimate.givenCurrentlyUsingAutoRenewAccount();
                }
                return estimate.get();
            };
            return spec.fees().forActivityBasedOp(HederaFunctionality.TokenUpdate, metricsCalc, txn, numPayerKeys);
        } catch (Throwable t) {
            log.warn("Couldn't estimate usage", t);
            return HapiSuite.ONE_HBAR;
        }
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        var id = TxnUtils.asTokenId(token, spec);
        if (newSymbolFn.isPresent()) {
            newSymbol = Optional.of(newSymbolFn.get().apply(spec));
        }
        if (newNameFn.isPresent()) {
            newName = Optional.of(newNameFn.get().apply(spec));
        }
        TokenUpdateTransactionBody opBody = spec.txns()
                .<TokenUpdateTransactionBody, TokenUpdateTransactionBody.Builder>body(
                        TokenUpdateTransactionBody.class, b -> {
                            b.setToken(id);
                            newSymbol.ifPresent(b::setSymbol);
                            newName.ifPresent(b::setName);
                            newMemo.ifPresent(s -> b.setMemo(
                                    StringValue.newBuilder().setValue(s).build()));
                            if (useInvalidAdminKey) {
                                b.setAdminKey(TxnUtils.WRONG_LENGTH_EDDSA_KEY);
                            } else if (useEmptyAdminKey) {
                                b.setAdminKey(TxnUtils.EMPTY_KEY_LIST);
                            } else {
                                newAdminKey.ifPresent(
                                        a -> b.setAdminKey(spec.registry().getKey(a)));
                            }
                            newTreasury.ifPresent(a -> b.setTreasury(asId(a, spec)));
                            if (useInvalidSupplyKey) {
                                b.setSupplyKey(TxnUtils.WRONG_LENGTH_EDDSA_KEY);
                            } else if (useEmptySupplyKey) {
                                b.setSupplyKey(TxnUtils.EMPTY_KEY_LIST);
                            } else {
                                newSupplyKey.ifPresent(
                                        k -> b.setSupplyKey(spec.registry().getKey(k)));
                            }
                            newSupplyKeyFunction.ifPresent(fn -> b.setSupplyKey(fn.apply(spec)));
                            if (useInvalidWipeKey) {
                                b.setWipeKey(TxnUtils.WRONG_LENGTH_EDDSA_KEY);
                            } else if (useEmptyWipeKey) {
                                b.setWipeKey(TxnUtils.EMPTY_KEY_LIST);
                            } else {
                                newWipeKey.ifPresent(
                                        k -> b.setWipeKey(spec.registry().getKey(k)));
                            }
                            if (useInvalidKycKey) {
                                b.setKycKey(TxnUtils.WRONG_LENGTH_EDDSA_KEY);
                            } else if (useEmptyKycKey) {
                                b.setKycKey(TxnUtils.EMPTY_KEY_LIST);
                            } else {
                                newKycKey.ifPresent(
                                        k -> b.setKycKey(spec.registry().getKey(k)));
                            }
                            if (useInvalidFeeScheduleKey) {
                                b.setFeeScheduleKey(TxnUtils.WRONG_LENGTH_EDDSA_KEY);
                            } else if (useEmptyFeeScheduleKey) {
                                b.setFeeScheduleKey(TxnUtils.EMPTY_KEY_LIST);
                            } else {
                                newFeeScheduleKey.ifPresent(
                                        k -> b.setFeeScheduleKey(spec.registry().getKey(k)));
                            }
                            if (useInvalidFreezeKey) {
                                b.setFreezeKey(TxnUtils.WRONG_LENGTH_EDDSA_KEY);
                            } else if (useEmptyFreezeKey) {
                                b.setFreezeKey(TxnUtils.EMPTY_KEY_LIST);
                            } else {
                                newFreezeKey.ifPresent(
                                        k -> b.setFreezeKey(spec.registry().getKey(k)));
                            }
                            if (useInvalidPauseKey) {
                                b.setPauseKey(TxnUtils.WRONG_LENGTH_EDDSA_KEY);
                            } else if (useEmptyPauseKey) {
                                b.setPauseKey(TxnUtils.EMPTY_KEY_LIST);
                            } else {
                                newPauseKey.ifPresent(
                                        k -> b.setPauseKey(spec.registry().getKey(k)));
                            }
                            if (useInvalidMetadataKey) {
                                b.setMetadataKey(TxnUtils.WRONG_LENGTH_EDDSA_KEY);
                            } else if (useEmptyMetadataKey) {
                                b.setMetadataKey(TxnUtils.EMPTY_KEY_LIST);
                            } else {
                                newMetadataKey.ifPresent(
                                        k -> b.setMetadataKey(spec.registry().getKey(k)));
                            }
                            if (newMetadata.isPresent()) {
                                var metadataValue = BytesValue.newBuilder()
                                        .setValue(ByteString.copyFrom(
                                                newMetadata.orElseThrow().getBytes()))
                                        .build();
                                b.setMetadata(metadataValue);
                            }
                            if (autoRenewAccount.isPresent()) {
                                var autoRenewId = TxnUtils.asId(autoRenewAccount.get(), spec);
                                b.setAutoRenewAccount(autoRenewId);
                            }
                            expiry.ifPresent(t -> b.setExpiry(
                                    Timestamp.newBuilder().setSeconds(t).build()));
                            autoRenewPeriod.ifPresent(secs -> b.setAutoRenewPeriod(
                                    Duration.newBuilder().setSeconds(secs).build()));
                            if (noKeyValidation) {
                                b.setKeyVerificationMode(TokenKeyValidation.NO_VALIDATION);
                            } else {
                                b.setKeyVerificationMode(TokenKeyValidation.FULL_VALIDATION);
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
                                        default ->
                                            throw new IllegalStateException("Unexpected tokenKeyType: " + tokenKeyType);
                                    }
                                }
                            }
                        });
        return b -> b.setTokenUpdate(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        List<Function<HapiSpec, Key>> signers = new ArrayList<>();
        signers.add(spec -> spec.registry().getKey(effectivePayer(spec)));
        signers.add(spec -> {
            try {
                return spec.registry().getRoleKey(token, KeyRole.ADMIN);
            } catch (RegistryNotFound ignore) {
                // Some tests attempt to update an immutable token,
                // skip the admin key if it's not found
                return Key.getDefaultInstance();
            }
        });
        newTreasury.ifPresent(n -> signers.add((spec -> spec.registry().getKey(n))));
        newAdminKey.ifPresent(n -> signers.add(spec -> spec.registry().getKey(n)));
        autoRenewAccount.ifPresent(a -> signers.add(spec -> spec.registry().getKey(a)));
        return signers;
    }

    @Override
    protected void updateStateOf(HapiSpec spec) {
        if (actualStatus != SUCCESS) {
            return;
        }
        var registry = spec.registry();

        for (KeyRole role : KeyRole.values()) {
            if (shouldForget(role)) {
                registry.forgetRoleKey(token, role);
            }
        }

        newMemo.ifPresent(m -> registry.saveMemo(token, m));
        newSymbol.ifPresent(s -> registry.saveSymbol(token, s));
        newName.ifPresent(s -> registry.saveName(token, s));
        newMetadata.ifPresent(n -> registry.saveMetadata(token, n));
        saveNewKeysIfPresent(registry);
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("token", token);
    }

    private void saveNewKeysIfPresent(HapiSpecRegistry registry) {
        newAdminKey.ifPresent(n -> registry.saveRoleKey(token, KeyRole.ADMIN, registry.getKey(n)));
        newFreezeKey.ifPresent(n -> registry.saveRoleKey(token, KeyRole.FREEZE, registry.getKey(n)));
        newSupplyKey.ifPresent(n -> registry.saveRoleKey(token, KeyRole.SUPPLY, registry.getKey(n)));
        newWipeKey.ifPresent(n -> registry.saveRoleKey(token, KeyRole.WIPE, registry.getKey(n)));
        newKycKey.ifPresent(n -> registry.saveRoleKey(token, KeyRole.KYC, registry.getKey(n)));
        newFeeScheduleKey.ifPresent(n -> registry.saveRoleKey(token, KeyRole.FEE_SCHEDULE, registry.getKey(n)));
        newPauseKey.ifPresent(n -> registry.saveRoleKey(token, KeyRole.PAUSE, registry.getKey(n)));
        newMetadataKey.ifPresent(n -> registry.saveRoleKey(token, KeyRole.METADATA, registry.getKey(n)));
    }

    private boolean shouldForget(KeyRole role) {
        return switch (role) {
            case ADMIN -> useEmptyAdminKey;
            case FREEZE -> useEmptyFreezeKey;
            case SUPPLY -> useEmptySupplyKey;
            case WIPE -> useEmptyWipeKey;
            case KYC -> useEmptyKycKey;
            case FEE_SCHEDULE -> useEmptyFeeScheduleKey;
            case PAUSE -> useEmptyPauseKey;
            case METADATA -> useEmptyMetadataKey;
        };
    }
}
