// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asScheduleString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTokenString;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_RECEIVER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.meta.ActionableContractCall;
import com.hedera.services.bdd.spec.infrastructure.meta.ActionableContractCallLocal;
import com.hedera.services.bdd.spec.infrastructure.meta.SupportedContract;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.utilops.inventory.TypedKey;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ConsensusCreateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusUpdateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ContractGetInfoResponse;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.CryptoGetInfoResponse;
import com.hederahashgraph.api.proto.java.EntityNumber;
import com.hederahashgraph.api.proto.java.FileGetInfoResponse;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.GetAccountDetailsResponse;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.NodeCreateTransactionBody;
import com.hederahashgraph.api.proto.java.NodeUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class HapiSpecRegistry {
    private final Map<String, Object> registry = new HashMap<>();
    private final HapiSpecSetup setup;
    private final Map<Class, List<RegistryChangeListener>> listenersByType = new HashMap<>();

    private static final Integer ZERO = 0;

    public HapiSpecRegistry(HapiSpecSetup setup) throws Exception {
        this.setup = setup;
        final var key = TypedKey.from(setup.payerKey());
        final var genesisKey = asPublicKey(key.pubKey(), key.type());

        saveAccountId(setup.genesisAccountName(), setup.genesisAccount());
        saveKey(setup.genesisAccountName(), asKeyList(genesisKey));
        saveAccountId(setup.defaultPayerName(), setup.defaultPayer());
        saveKey(setup.defaultPayerName(), asKeyList(genesisKey));
        // The default contract sender is the default payer unless using Ethereum transactions
        saveAccountId(DEFAULT_CONTRACT_SENDER, setup.defaultPayer());
        saveKey(DEFAULT_CONTRACT_SENDER, asKeyList(genesisKey));
        saveAccountId(DEFAULT_CONTRACT_RECEIVER, setup.fundingAccount());
        saveKey(DEFAULT_CONTRACT_RECEIVER, asKeyList(genesisKey));
        saveAccountId(setup.defaultNodeName(), setup.defaultNode());
        saveAccountId(setup.fundingAccountName(), setup.fundingAccount());
        saveContractId(setup.invalidContractName(), setup.invalidContract());
        saveAccountId(setup.stakingRewardAccountName(), setup.stakingRewardAccount());
        saveAccountId(setup.nodeRewardAccountName(), setup.nodeRewardAccount());
        saveAccountId(setup.feeCollectorAccountName(), setup.feeCollectorAccount());

        saveAccountId(setup.strongControlName(), setup.strongControlAccount());
        saveKey(setup.strongControlName(), asKeyList(genesisKey));
        saveAccountId(setup.systemDeleteAdminName(), setup.systemDeleteAdmin());
        saveKey(setup.systemDeleteAdminName(), asKeyList(genesisKey));
        saveAccountId(setup.systemUndeleteAdminName(), setup.systemUndeleteAdmin());
        saveKey(setup.systemUndeleteAdminName(), asKeyList(genesisKey));
        saveAccountId(setup.freezeAdminName(), setup.freezeAdminId());
        saveKey(setup.freezeAdminName(), asKeyList(genesisKey));
        saveAccountId(setup.softwareUpdateAdminName(), setup.softwareUpdateAdminId());
        saveKey(setup.softwareUpdateAdminName(), asKeyList(genesisKey));

        /* (system file 1) :: Address Book */
        saveFileId(setup.addressBookName(), setup.addressBookId());
        saveKey(setup.addressBookName(), asKeyList(genesisKey));
        saveAccountId(setup.addressBookControlName(), setup.addressBookControl());
        saveKey(setup.addressBookControlName(), asKeyList(genesisKey));
        /* (system file 2) :: Node Details */
        saveFileId(setup.nodeDetailsName(), setup.nodeDetailsId());
        saveKey(setup.nodeDetailsName(), asKeyList(genesisKey));
        /* (system file 3) :: Exchange Rates */
        saveFileId(setup.exchangeRatesName(), setup.exchangeRatesId());
        saveKey(setup.exchangeRatesName(), asKeyList(genesisKey));
        saveAccountId(setup.exchangeRatesControlName(), setup.exchangeRatesControl());
        saveKey(setup.exchangeRatesControlName(), asKeyList(genesisKey));
        /* (system 4) :: Fee Schedule */
        saveFileId(setup.feeScheduleName(), setup.feeScheduleId());
        saveKey(setup.feeScheduleName(), asKeyList(genesisKey));
        saveAccountId(setup.feeScheduleControlName(), setup.feeScheduleControl());
        saveKey(setup.feeScheduleControlName(), asKeyList(genesisKey));
        /* (system 4) :: Simple Fees Schedule */
        saveFileId(setup.simpleFeesScheduleName(), setup.simpleFeesScheduleId());
        saveKey(setup.simpleFeesScheduleName(), asKeyList(genesisKey));
        saveAccountId(setup.simpleFeesScheduleControlName(), setup.simpleFeesScheduleControl());
        saveKey(setup.simpleFeesScheduleControlName(), asKeyList(genesisKey));
        /* (system 5) :: API Permissions */
        saveFileId(setup.apiPermissionsFile(), setup.apiPermissionsId());
        saveKey(setup.apiPermissionsFile(), asKeyList(genesisKey));
        /* (system 6) :: App Properties */
        saveFileId(setup.appPropertiesFile(), setup.appPropertiesId());
        saveKey(setup.appPropertiesFile(), asKeyList(genesisKey));
        /* (system 7) :: Update Feature */
        saveFileId(setup.updateFeatureName(), setup.updateFeatureId());
        saveKey(setup.updateFeatureName(), asKeyList(genesisKey));
        /* (system 8) :: Throttle Definitions */
        saveFileId(setup.throttleDefinitionsName(), setup.throttleDefinitionsId());
        saveKey(setup.throttleDefinitionsName(), asKeyList(genesisKey));

        saveKey(HapiSuite.NONSENSE_KEY, nonsenseKey());
    }

    public void include(@NonNull final HapiSpecRegistry that) {
        this.registry.putAll(that.registry);
    }

    private Key nonsenseKey() {
        return Key.getDefaultInstance();
    }

    private Key asPublicKey(byte[] pubKey, SigControl control) {
        if (control == SigControl.SECP256K1_ON) {
            return Key.newBuilder()
                    .setECDSASecp256K1(ByteString.copyFrom(pubKey))
                    .build();
        } else {
            return Key.newBuilder().setEd25519(ByteString.copyFrom(pubKey)).build();
        }
    }

    private Key asKeyList(Key key) {
        return Key.newBuilder().setKeyList(KeyList.newBuilder().addKeys(key)).build();
    }

    public void register(RegistryChangeListener<?> listener) {
        Class<?> type = listener.forType();
        listenersByType.computeIfAbsent(type, ignore -> new ArrayList<>()).add(listener);
    }

    public void saveContractChoice(String name, SupportedContract choice) {
        put(name, choice);
    }

    public SupportedContract getContractChoice(String name) {
        return get(name, SupportedContract.class);
    }

    public boolean hasContractChoice(String name) {
        return hasVia(this::getContractChoice, name);
    }

    public void removeContractChoice(String name) {
        remove(name, SupportedContract.class);
    }

    public ActionableContractCall getActionableCall(String name) {
        return get(name, ActionableContractCall.class);
    }

    public void saveActionableCall(String name, ActionableContractCall call) {
        put(name, call);
    }

    public void removeActionableCall(String name) {
        remove(name, ActionableContractCall.class);
    }

    public void saveActionableLocalCall(String name, ActionableContractCallLocal call) {
        put(name, call);
    }

    public void removeActionableLocalCall(String name) {
        remove(name, ActionableContractCallLocal.class);
    }

    public ActionableContractCallLocal getActionableLocalCall(String name) {
        return get(name, ActionableContractCallLocal.class);
    }

    public void saveBalanceSnapshot(String name, Long balance) {
        put(name, balance);
    }

    public long getBalanceSnapshot(String name) {
        return get(name, Long.class);
    }

    public void saveTokenBalanceSnapshot(String token, String name, Long balance) {
        put(token + "-" + name, balance);
    }

    public long getTokenBalanceSnapshot(String token, String name) {
        return get(token + "-" + name, Long.class);
    }

    public Timestamp getTimestamp(String label) {
        return get(label, Timestamp.class);
    }

    public void saveTimestamp(String label, Timestamp when) {
        put(label, when, Timestamp.class);
    }

    public void removeTimestamp(String label) {
        try {
            remove(label, Timestamp.class);
        } catch (Exception ignore) {
        }
    }

    public void saveKey(String name, Key key) {
        put(name, key, Key.class);
    }

    public void saveExpiry(String name, Long value) {
        put(name + "Expiry", value, Long.class);
    }

    public void saveCreationTime(String name, Timestamp value) {
        put(name + "CreationTime", value, Timestamp.class);
    }

    public Key getRoleKey(String tokenName, KeyRole role) {
        return getOrElse(getRoleKeyName(tokenName, role), Key.class, null);
    }

    public void saveRoleKey(String name, KeyRole role, Key key) {
        put(getRoleKeyName(name, role), key);
    }

    public void forgetRoleKey(String name, KeyRole role) {
        remove(getRoleKeyName(name, role), Key.class);
    }

    public boolean hasRoleKey(String name, KeyRole role) {
        return has(getRoleKeyName(name, role), Key.class);
    }

    public void saveSymbol(String token, String symbol) {
        put(token + "Symbol", symbol, String.class);
    }

    public void saveName(String token, String name) {
        put(token + "Name", name, String.class);
    }

    public void saveEVMAddress(String name, String address) {
        put(name + "-EVMAddress", address, String.class);
    }

    public String getEVMAddress(String name) {
        return get(name + "-EVMAddress", String.class);
    }

    public boolean hasEVMAddress(String name) {
        return has(name + "-EVMAddress", String.class);
    }

    public void saveMemo(String entity, String memo) {
        put(entity + "Memo", memo, String.class);
    }

    public String getMemo(String entity) {
        return get(entity + "Memo", String.class);
    }

    public void forgetSymbol(String token) {
        remove(token + "Symbol", String.class);
    }

    public String getName(String token) {
        return get(token + "Name", String.class);
    }

    public void forgetName(String token) {
        remove(token + "Name", String.class);
    }

    public Key getKey(String name) {
        return get(name, Key.class);
    }

    public Long getExpiry(String name) {
        return get(name + "Expiry", Long.class);
    }

    public Timestamp getCreationTime(String name) {
        return get(name + "CreationTime", Timestamp.class);
    }

    public boolean hasKey(String name) {
        return hasVia(this::getKey, name);
    }

    public void removeKey(String name) {
        try {
            remove(name, Key.class);
        } catch (Exception ignore) {
        }
    }

    public void saveTopicMeta(String name, ConsensusCreateTopicTransactionBody meta, Long approxConsensusTime) {
        put(name, meta);
        put(name, approxConsensusTime + meta.getAutoRenewPeriod().getSeconds() + 60);
    }

    public void saveTopicMeta(String name, ConsensusUpdateTopicTransactionBody txn) {
        ConsensusCreateTopicTransactionBody.Builder meta;
        if (hasTopicMeta(name)) {
            meta = getTopicMeta(name).toBuilder();
        } else {
            meta = ConsensusCreateTopicTransactionBody.newBuilder();
        }
        if (txn.hasAdminKey()) {
            meta.setAdminKey(txn.getAdminKey());
        }
        if (txn.hasAutoRenewAccount()) {
            meta.setAutoRenewAccount(txn.getAutoRenewAccount());
        }
        if (txn.hasAutoRenewPeriod()) {
            meta.setAutoRenewPeriod(txn.getAutoRenewPeriod());
        }
        if (txn.hasSubmitKey()) {
            meta.setSubmitKey(txn.getSubmitKey());
        }
        if (txn.hasMemo()) {
            meta.setMemo(txn.getMemo().getValue());
        }
        if (txn.hasCustomFees()) {
            meta.addAllCustomFees(txn.getCustomFees().getFeesList());
        }
        put(name, meta.build());
        if (txn.hasExpirationTime()) {
            put(name, txn.getExpirationTime().getSeconds());
        }
    }

    public ConsensusCreateTopicTransactionBody getTopicMeta(String name) {
        return get(name, ConsensusCreateTopicTransactionBody.class);
    }

    public long getTopicExpiry(String name) {
        return get(name, Long.class);
    }

    public boolean hasTopicMeta(String name) {
        return hasVia(this::getTopicMeta, name);
    }

    public void saveBytes(String name, ByteString bytes) {
        put(name, bytes, ByteString.class);
    }

    public byte[] getBytes(String name) {
        return get(name, ByteString.class).toByteArray();
    }

    public void saveAmount(String name, Long amount) {
        put(name, amount);
    }

    public Long getAmount(String name) {
        return get(name, Long.class);
    }

    public void saveSigRequirement(String name, Boolean isRequired) {
        put(name, isRequired);
    }

    public void removeSigRequirement(String name) {
        remove(name, Boolean.class);
    }

    public boolean isSigRequired(String name) {
        return registry.containsKey(full(name, Boolean.class))
                ? get(name, Boolean.class)
                : setup.defaultReceiverSigRequired();
    }

    public boolean hasSigRequirement(String name) {
        return hasVia(this::isSigRequired, name);
    }

    private <T> boolean hasVia(Function<String, T> tGetter, String thing) {
        try {
            tGetter.apply(thing);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    public void saveScheduledTxn(String name, SchedulableTransactionBody scheduledTxn) {
        put(name, scheduledTxn);
    }

    public SchedulableTransactionBody getScheduledTxn(String name) {
        return get(name, SchedulableTransactionBody.class);
    }

    public void saveTxnId(String name, TransactionID txnId) {
        put(name, txnId);
    }

    public TransactionID getTxnId(String name) {
        return get(name, TransactionID.class);
    }

    public Optional<TransactionID> getMaybeTxnId(String name) {
        return Optional.ofNullable(getOrElse(name, TransactionID.class, null));
    }

    public void saveAccountId(String name, AccountID id) {
        put(name, id);
        put(asAccountString(id), name);
    }

    public void saveNodeId(String name, EntityNumber nodeId) {
        put(name, nodeId);
        put(String.valueOf(nodeId), name);
    }

    public void saveScheduleId(String name, ScheduleID id) {
        put(name, id);
        put(asScheduleString(id), name);
    }

    public ScheduleID getScheduleId(String name) {
        return get(name, ScheduleID.class);
    }

    public void saveTokenId(String name, TokenID id) {
        put(name, id);
        put(asTokenString(id), name);
    }

    public void saveAccountAlias(String alias, AccountID id) {
        put(alias, id);
    }

    public AccountID getAccountAlias(String name) {
        return get(name, AccountID.class);
    }

    public AccountID getKeyAlias(@NonNull final String keyName) {
        final var key = get(keyName, Key.class);
        return AccountID.newBuilder().setAlias(key.toByteString()).build();
    }

    public void forgetTokenId(String name) {
        try {
            var id = getTokenID(name);
            remove(name, TokenID.class);
            remove(asTokenString(id), String.class);
        } catch (Throwable ignore) {
        }
    }

    public void saveTokenRel(String account, String token) {
        put(tokenRelKey(account, token), new TokenAccountRegistryRel(token, account));
    }

    public boolean hasTokenRel(String account, String token) {
        return has(tokenRelKey(account, token), TokenAccountRegistryRel.class);
    }

    private String tokenRelKey(String account, String token) {
        return account + "|" + token;
    }

    public void saveTreasury(String token, String treasury) {
        put(token + "Treasury", treasury);
    }

    public void forgetTreasury(String token) {
        remove(token + "Treasury", String.class);
    }

    public String getTreasury(String token) {
        return get(token + "Treasury", String.class);
    }

    public void setRecharging(String account, long amount) {
        put(account, Boolean.TRUE);
        put(account + "Recharge", amount);
    }

    public boolean isRecharging(String account) {
        return registry.containsKey(full(account, Boolean.class));
    }

    public Long getRechargeAmount(String account) {
        return get(account + "Recharge", Long.class);
    }

    public void setRechargingTime(String account, Instant time) {
        put(account + "RechargeTime", time);
    }

    public Instant getRechargingTime(String account) {
        try {
            return get(account + "RechargeTime", Instant.class);
        } catch (Exception ignore) {
            return Instant.MIN;
        }
    }

    public void setRechargingWindow(String account, Integer seconds) {
        put(account + "RechargeWindow", seconds);
    }

    public Integer getRechargingWindow(String account) {
        return getOrElse(account + "RechargeWindow", Integer.class, ZERO);
    }

    public boolean hasAccountId(String name) {
        return registry.get(full(name, AccountID.class)) != null;
    }

    public AccountID getAccountID(String name) {
        return get(name, AccountID.class);
    }

    public AccountID keyAliasIdFor(HapiSpec spec, String keyName) {
        final var key = get(keyName, Key.class);
        return AccountID.newBuilder()
                .setShardNum(spec.shard())
                .setRealmNum(spec.realm())
                .setAlias(key.toByteString())
                .build();
    }

    public String getAccountIdName(AccountID account) {
        return get(asAccountString(account), String.class);
    }

    public boolean hasAccountIdName(AccountID accountId) {
        return registry.get(full(asAccountString(accountId), String.class)) != null;
    }

    public void removeAccount(String name) {
        try {
            var id = getAccountID(name);
            remove(name, AccountID.class);
            remove(asAccountString(id), String.class);
        } catch (Exception ignore) {
        }
    }

    public void saveTopicId(String name, TopicID id) {
        put(name, id);
        put(HapiPropertySource.asTopicString(id), name);
    }

    public TopicID getTopicID(String name) {
        return get(name, TopicID.class);
    }

    public boolean hasTopicID(String name) {
        return registry.containsKey(full(name, TopicID.class));
    }

    public TokenID getTokenID(String name) {
        return get(name, TokenID.class);
    }

    public void saveFileId(String name, FileID id) {
        put(name, id);
    }

    public FileID getFileId(String name) {
        return get(name, FileID.class);
    }

    public EntityNumber getNodeId(String name) {
        return get(name, EntityNumber.class);
    }

    public void removeFileId(String name) {
        try {
            remove(name, FileID.class);
        } catch (Exception ignore) {
        }
    }

    public void saveContractList(String name, List<ContractID> list) {
        long listSize = list.size();
        saveAmount(name + "Size", listSize);
        for (int i = 0; i < listSize; i++) {
            saveContractId(name + i, list.get(i));
        }
    }

    public void saveContractId(String name, ContractID id) {
        put(name, id);
    }

    public void saveContractId(String name, HapiSpec spec, ByteString evmAddress) {
        put(
                name,
                ContractID.newBuilder()
                        .setShardNum(spec.shard())
                        .setRealmNum(spec.realm())
                        .setEvmAddress(evmAddress)
                        .build());
    }

    public ContractID getContractId(String name) {
        return get(name, ContractID.class);
    }

    public boolean hasContractId(String name) {
        return registry.containsKey(full(name, ContractID.class));
    }

    public void removeContractId(String name) {
        try {
            remove(name, ContractID.class);
        } catch (Exception ignore) {
        }
    }

    public void saveContractInfo(String name, ContractGetInfoResponse.ContractInfo info) {
        put(name, info);
    }

    public void removeContractInfo(String name) {
        try {
            remove(name, ContractGetInfoResponse.ContractInfo.class);
        } catch (Exception ignore) {
        }
    }

    public TransactionRecord getTransactionRecord(String name) {
        return get(name, TransactionRecord.class);
    }

    public void saveTransactionRecord(String name, TransactionRecord txnRecord) {
        put(name, txnRecord);
    }

    public boolean hasTransactionRecord(String name) {
        return has(name, TransactionRecord.class);
    }

    public ContractGetInfoResponse.ContractInfo getContractInfo(String name) {
        return get(name, ContractGetInfoResponse.ContractInfo.class);
    }

    public void saveFileInfo(String name, FileGetInfoResponse.FileInfo info) {
        put(name, info);
    }

    public FileGetInfoResponse.FileInfo getFileInfo(String name) {
        return get(name, FileGetInfoResponse.FileInfo.class);
    }

    public void saveAccountInfo(String name, CryptoGetInfoResponse.AccountInfo info) {
        put(name, info);
    }

    public void saveAccountDetails(String name, GetAccountDetailsResponse.AccountDetails details) {
        put(name, details);
    }

    public CryptoGetInfoResponse.AccountInfo getAccountInfo(String name) {
        return get(name, CryptoGetInfoResponse.AccountInfo.class);
    }

    public GetAccountDetailsResponse.AccountDetails getAccountDetails(String name) {
        return get(name, GetAccountDetailsResponse.AccountDetails.class);
    }

    public <T> T getId(String name, Class<T> type) {
        return get(name, type);
    }

    private synchronized void remove(String name, Class<?> type, Optional<HapiSpecOperation> cause) {
        registry.remove(full(name, type));
        notifyAllOnDelete(type, name, cause);
    }

    private synchronized void remove(String name, Class<?> type) {
        remove(name, type, Optional.empty());
    }

    private void notifyAllOnDelete(Class type, String name, Optional<HapiSpecOperation> cause) {
        Optional.ofNullable(listenersByType.get(type)).ifPresent(a -> a.forEach(l -> l.onDelete(name, cause)));
    }

    private synchronized void put(String name, Object obj, Optional<HapiSpecOperation> cause, Class type) {
        if (obj == null) {
            return;
        }
        registry.put(full(name, type), obj);
        notifyAllOnPut(type, name, obj, cause);
    }

    private synchronized void put(String name, Object obj, Class<?> type) {
        put(name, obj, Optional.empty(), type);
    }

    private synchronized void put(String name, Object obj) {
        put(name, obj, obj.getClass());
    }

    private void notifyAllOnPut(Class type, String name, Object value, Optional<HapiSpecOperation> cause) {
        Optional.ofNullable(listenersByType.get(type))
                .ifPresent(a -> a.forEach(l -> {
                    Class<?> lType = l.forType();
                    notifyOnPut(l, lType, name, value, cause);
                }));
    }

    private <T> void notifyOnPut(
            RegistryChangeListener<T> listener,
            Class<T> type,
            String name,
            Object value,
            Optional<HapiSpecOperation> cause) {
        listener.onPut(name, type.cast(value), cause);
    }

    private synchronized <T> T get(String name, Class<T> type) {
        Object v = registry.get(full(name, type));
        if (v == null) {
            throw new RegistryNotFound("Missing " + type.getSimpleName() + " '" + name + "'!");
        }
        return type.cast(v);
    }

    private <T> boolean has(String name, Class<T> type) {
        return registry.containsKey(full(name, type));
    }

    private synchronized <T> T getOrElse(String name, Class<T> type, T defaultValue) {
        Object v = registry.get(full(name, type));
        if (v == null) {
            return defaultValue;
        }
        return type.cast(v);
    }

    private String full(String name, Class<?> type) {
        String typeName = type.getSimpleName();
        return typeName + "-" + name;
    }

    public void saveMetadata(String token, String metadata) {
        put(token + "Metadata", metadata, String.class);
    }

    public boolean hasNodeMeta(String name) {
        return hasVia(this::getNodeMeta, name);
    }

    public NodeCreateTransactionBody getNodeMeta(String name) {
        return get(name, NodeCreateTransactionBody.class);
    }

    public void saveNodeMeta(String name, NodeUpdateTransactionBody txn) {
        NodeCreateTransactionBody.Builder builder;
        if (hasNodeMeta(name)) {
            builder = getNodeMeta(name).toBuilder();
        } else {
            builder = NodeCreateTransactionBody.newBuilder();
        }
        if (txn.hasAdminKey()) {
            builder.setAdminKey(txn.getAdminKey());
        }
        if (txn.hasAccountId()) {
            builder.setAccountId(txn.getAccountId());
        }
        if (txn.hasDescription()) {
            builder.setDescription(txn.getDescription().getValue());
        }
        if (txn.hasGossipCaCertificate()) {
            builder.setGossipCaCertificate(txn.getGossipCaCertificate().toByteString());
        }
        if (txn.hasGrpcCertificateHash()) {
            builder.setGrpcCertificateHash(txn.getGossipCaCertificate().toByteString());
        }
        builder.addAllGossipEndpoint(txn.getGossipEndpointList());
        builder.addAllServiceEndpoint(txn.getServiceEndpointList());
        put(name, builder.build());
    }

    private String getRoleKeyName(String tokenName, KeyRole role) {
        return String.format("%s_%s_KEY", tokenName, role);
    }
}
