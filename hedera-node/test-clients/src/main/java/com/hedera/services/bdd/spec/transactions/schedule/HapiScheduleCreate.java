// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.schedule;

import static com.hedera.services.bdd.spec.HapiPropertySource.asScheduleString;
import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.FULL_PREFIXES;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bannerWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleCreate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.schedule.ScheduleUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiScheduleCreate<T extends HapiTxnOp<T>> extends HapiTxnOp<HapiScheduleCreate<T>> {
    private static final Logger log = LogManager.getLogger(HapiScheduleCreate.class);

    private static final long NA = -1;

    private long longTermExpiry = NA;
    private long longTermLifetime = NA;
    private boolean asCallableSchedule = false;
    private boolean advertiseCreation = false;
    private boolean recordScheduledTxn = false;
    private boolean skipRegistryUpdate = false;
    private boolean scheduleNoFunction = false;
    private boolean saveExpectedScheduledTxnId = false;
    private boolean useSentinelKeyListForAdminKey = false;
    private final ByteString bytesSigned = ByteString.EMPTY;

    @Nullable
    private List<Key> explicitInitialSigners = null;

    private List<String> initialSigners = Collections.emptyList();
    private Optional<String> adminKey = Optional.empty();
    private Optional<String> payerAccountID = Optional.empty();
    private Optional<Boolean> withNonExistingPayerAccountID = Optional.empty();
    private Optional<String> entityMemo = Optional.empty();
    private Optional<Boolean> waitForExpiry = Optional.empty();
    private Optional<Pair<String, Long>> expirationTimeRelativeTo = Optional.empty();
    private Optional<BiConsumer<String, byte[]>> successCb = Optional.empty();
    private Optional<Consumer<ScheduleID>> newScheduleIdObserver = Optional.empty();
    private AtomicReference<SchedulableTransactionBody> scheduledTxn = new AtomicReference<>();

    private final String scheduleEntity;
    private final HapiTxnOp<T> scheduled;

    public HapiScheduleCreate(String scheduled, HapiTxnOp<T> txn) {
        this.scheduleEntity = scheduled;
        this.scheduled = txn.withProtoStructure(HapiSpecSetup.TxnProtoStructure.OLD)
                .sansTxnId()
                .sansNodeAccount()
                .signedBy();
        sigMapPrefixes(TrieSigMapGenerator.withNature(FULL_PREFIXES));
    }

    public HapiScheduleCreate<T> asCallableSchedule() {
        asCallableSchedule = true;
        return this;
    }

    public HapiScheduleCreate<T> advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    public HapiScheduleCreate<T> savingExpectedScheduledTxnId() {
        saveExpectedScheduledTxnId = true;
        return this;
    }

    public HapiScheduleCreate<T> recordingScheduledTxn() {
        recordScheduledTxn = true;
        return this;
    }

    public HapiScheduleCreate<T> rememberingNothing() {
        skipRegistryUpdate = true;
        return this;
    }

    public HapiScheduleCreate<T> functionless() {
        scheduleNoFunction = true;
        return this;
    }

    public HapiScheduleCreate<T> adminKey(String s) {
        adminKey = Optional.of(s);
        return this;
    }

    public HapiScheduleCreate<T> usingSentinelKeyListForAdminKey() {
        useSentinelKeyListForAdminKey = true;
        return this;
    }

    public HapiScheduleCreate<T> exposingSuccessTo(BiConsumer<String, byte[]> cb) {
        successCb = Optional.of(cb);
        return this;
    }

    public HapiScheduleCreate<T> exposingCreatedIdTo(final Consumer<ScheduleID> newScheduleIdObserver) {
        this.newScheduleIdObserver = Optional.of(newScheduleIdObserver);
        return this;
    }

    public HapiScheduleCreate<T> designatingPayer(String s) {
        payerAccountID = Optional.of(s);
        return this;
    }

    public HapiScheduleCreate<T> withNonExistingDesignatingPayer() {
        withNonExistingPayerAccountID = Optional.of(true);
        return this;
    }

    public HapiScheduleCreate<T> alsoSigningWith(String... s) {
        initialSigners = List.of(s);
        return this;
    }

    public HapiScheduleCreate<T> alsoSigningWithExplicit(final List<Key> keys) {
        explicitInitialSigners = keys;
        return this;
    }

    public HapiScheduleCreate<T> withEntityMemo(String entityMemo) {
        this.entityMemo = Optional.of(entityMemo);
        return this;
    }

    public HapiScheduleCreate<T> waitForExpiry() {
        this.waitForExpiry = Optional.of(true);
        return this;
    }

    public HapiScheduleCreate<T> waitForExpiry(boolean value) {
        this.waitForExpiry = Optional.of(value);
        return this;
    }

    public HapiScheduleCreate<T> expiringAt(final long expiry) {
        this.longTermExpiry = expiry;
        return this;
    }

    public HapiScheduleCreate<T> expiringIn(final long lifetime) {
        this.longTermLifetime = lifetime;
        return this;
    }

    public HapiScheduleCreate<T> withRelativeExpiry(String txnId, long offsetSeconds) {
        this.expirationTimeRelativeTo = Optional.of(Pair.of(txnId, offsetSeconds));
        return this;
    }

    @Override
    protected HapiScheduleCreate<T> self() {
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ScheduleCreate;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        if (scheduled.hasBatchKey()) {
            throw new RuntimeException("Using batch keys in scheduled transactions is not supported!");
        }
        var subOp = scheduled.signedTxnFor(spec);

        ScheduleCreateTransactionBody opBody = spec.txns()
                .<ScheduleCreateTransactionBody, ScheduleCreateTransactionBody.Builder>body(
                        ScheduleCreateTransactionBody.class, b -> {
                            if (scheduleNoFunction) {
                                b.setScheduledTransactionBody(SchedulableTransactionBody.getDefaultInstance());
                            } else {
                                try {
                                    var deserializedTxn = TransactionBody.parseFrom(subOp.getBodyBytes());
                                    scheduledTxn.set(ScheduleUtils.fromOrdinary(deserializedTxn));
                                    b.setScheduledTransactionBody(scheduledTxn.get());
                                } catch (InvalidProtocolBufferException fatal) {
                                    throw new IllegalStateException(
                                            "Couldn't deserialize serialized" + " TransactionBody!");
                                }
                            }
                            if (useSentinelKeyListForAdminKey) {
                                b.setAdminKey(Key.newBuilder().setKeyList(KeyList.getDefaultInstance()));
                            } else {
                                adminKey.ifPresent(
                                        k -> b.setAdminKey(spec.registry().getKey(k)));
                            }

                            waitForExpiry.ifPresent(b::setWaitForExpiry);

                            if (longTermExpiry != NA) {
                                b.setExpirationTime(Timestamp.newBuilder()
                                        .setSeconds(longTermExpiry)
                                        .build());
                            } else if (longTermLifetime != NA) {
                                final var approxNow = spec.consensusTime();
                                b.setExpirationTime(Timestamp.newBuilder()
                                        .setSeconds(approxNow.getEpochSecond() + longTermLifetime)
                                        .build());
                            } else if (expirationTimeRelativeTo.isPresent()) {
                                var expiry = getRelativeExpiry(
                                        spec,
                                        expirationTimeRelativeTo.get().getKey(),
                                        expirationTimeRelativeTo.get().getValue());

                                b.setExpirationTime(expiry);
                            }

                            entityMemo.ifPresent(b::setMemo);
                            payerAccountID.ifPresent(a -> {
                                var payer = TxnUtils.asId(a, spec);
                                b.setPayerAccountID(payer);
                            });
                            withNonExistingPayerAccountID.ifPresent(a -> {
                                var nonExistingPayer = AccountID.newBuilder()
                                        .setAccountNum(7431)
                                        .build();
                                b.setPayerAccountID(nonExistingPayer);
                            });
                        });
        return b -> b.setScheduleCreate(opBody);
    }

    public static Timestamp getRelativeExpiry(final HapiSpec spec, final String txnId, final Long relative) {
        if (!spec.registry().hasTransactionRecord(txnId)) {
            var createTxn = getTxnRecord(txnId).saveTxnRecordToRegistry(txnId);
            allRunFor(spec, createTxn);
        }

        var consensus = spec.registry().getTransactionRecord(txnId).getConsensusTimestamp();

        var expiry = Instant.ofEpochSecond(consensus.getSeconds(), consensus.getNanos())
                .plusSeconds(relative);

        return Timestamp.newBuilder()
                .setSeconds(expiry.getEpochSecond())
                .setNanos(expiry.getNano())
                .build();
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        final var defaultExpiry =
                spec.targetNetworkOrThrow().startupProperties().getInteger("ledger.schedule.txExpiryTimeSecs");
        FeeCalculator.ActivityMetrics metricsCalc =
                (_txn, svo) -> scheduleOpsUsage.scheduleCreateUsage(_txn, suFrom(svo), defaultExpiry);

        return spec.fees().forActivityBasedOp(HederaFunctionality.ScheduleCreate, metricsCalc, txn, numPayerKeys);
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper().add("entity", scheduleEntity);
        helper.add("id", createdSchedule().orElse("<N/A>"));
        return helper;
    }

    @Override
    protected void updateStateOf(HapiSpec spec) throws Throwable {
        if (actualStatus != SUCCESS) {
            return;
        }
        if (verboseLoggingOn) {
            log.info(
                    "Created schedule '{}' as {}",
                    scheduleEntity,
                    createdSchedule().get());
        }
        final var scheduleId = lastReceipt.getScheduleID();
        successCb.ifPresent(cb -> cb.accept(asScheduleString(scheduleId), bytesSigned.toByteArray()));
        if (skipRegistryUpdate) {
            return;
        }
        final var registry = spec.registry();
        registry.saveScheduleId(scheduleEntity, scheduleId);

        newScheduleIdObserver.ifPresent(obs -> obs.accept(scheduleId));

        adminKey.ifPresent(k -> registry.saveRoleKey(
                scheduleEntity, KeyRole.ADMIN, spec.registry().getKey(k)));
        if (saveExpectedScheduledTxnId) {
            if (verboseLoggingOn) {
                log.info("Returned receipt for scheduled txn is {}", lastReceipt.getScheduledTransactionID());
            }
            registry.saveTxnId(correspondingScheduledTxnId(scheduleEntity), lastReceipt.getScheduledTransactionID());
        }
        if (recordScheduledTxn) {
            if (verboseLoggingOn) {
                log.info("Created scheduled txn {}", scheduledTxn.get());
            }
            registry.saveScheduledTxn(scheduleEntity, scheduledTxn.get());
        }
        if (advertiseCreation) {
            String banner = "\n\n"
                    + bannerWith(String.format(
                            "Created schedule '%s' with id '%s'.", scheduleEntity, asScheduleString(scheduleId)));
            log.info(banner);
        }
        if (asCallableSchedule) {
            registry.saveContractId(
                    scheduleEntity,
                    ContractID.newBuilder()
                            .setShardNum(scheduleId.getShardNum())
                            .setRealmNum(scheduleId.getRealmNum())
                            .setContractNum(scheduleId.getScheduleNum())
                            .build());
        }
    }

    public static String correspondingScheduledTxnId(String entity) {
        return entity + "ScheduledTxnId";
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        List<Function<HapiSpec, Key>> signers =
                new ArrayList<>(List.of(spec -> spec.registry().getKey(effectivePayer(spec))));
        adminKey.ifPresent(k -> signers.add(spec -> spec.registry().getKey(k)));
        if (explicitInitialSigners != null) {
            explicitInitialSigners.forEach(key -> signers.add(spec -> key));
        } else {
            for (String added : initialSigners) {
                signers.add(spec -> spec.registry().getKey(added));
            }
        }
        return signers;
    }

    private Optional<String> createdSchedule() {
        return Optional.ofNullable(lastReceipt).map(receipt -> asScheduleString(receipt.getScheduleID()));
    }
}
