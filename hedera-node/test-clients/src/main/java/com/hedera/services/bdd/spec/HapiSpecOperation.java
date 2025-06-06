// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asIdWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.extractTxnId;
import static java.util.Collections.EMPTY_LIST;
import static java.util.stream.Collectors.toList;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import com.hedera.node.app.hapi.fees.usage.consensus.ConsensusOpsUsage;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage;
import com.hedera.node.app.hapi.fees.usage.file.FileOpsUsage;
import com.hedera.node.app.hapi.fees.usage.schedule.ScheduleOpsUsage;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.fee.CryptoFeeBuilder;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.hapi.utils.fee.FileFeeBuilder;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.keys.SigMapGenerator;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.mod.BodyMutation;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CustomFeeLimit;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Implementation support for any {@link SpecOperation} sends a query or submits a transaction
 * to the target {@link HederaNetwork}.
 */
public abstract class HapiSpecOperation implements SpecOperation {
    private static final Logger log = LogManager.getLogger(HapiSpecOperation.class);

    protected static final FileOpsUsage fileOpsUsage = new FileOpsUsage();
    protected static final CryptoOpsUsage cryptoOpsUsage = new CryptoOpsUsage();
    protected static final ScheduleOpsUsage scheduleOpsUsage = new ScheduleOpsUsage();
    protected static final ConsensusOpsUsage consensusOpsUsage = new ConsensusOpsUsage();

    @SuppressWarnings("java:S2245") // using java.util.Random in tests is fine
    private final Random r = new Random(688679L);

    /* Note that an op may _be_ a txn; or just a query that submits a txn as payment. */
    protected String txnName = UUID.randomUUID().toString().substring(0, 8);
    protected Transaction txnSubmitted;
    protected TransactionRecord recordOfSubmission;

    protected FeeBuilder fees = new FeeBuilder();
    protected FileFeeBuilder fileFees = new FileFeeBuilder();
    protected CryptoFeeBuilder cryptoFees = new CryptoFeeBuilder();
    protected SmartContractFeeBuilder scFees = new SmartContractFeeBuilder();

    protected boolean omitTxnId = false;
    protected boolean loggingOff = false;
    protected boolean yahcliLogger = false;
    protected boolean omitNodeAccount = false;
    protected boolean verboseLoggingOn = false;
    protected boolean shouldRegisterTxn = false;
    protected boolean useDefaultTxnAsCostAnswerPayment = false;
    protected boolean useDefaultTxnAsAnswerOnlyPayment = false;
    protected boolean usePresetTimestamp = false;
    protected boolean asTxnWithOnlySigMap = false;

    @Nullable
    protected HapiSpecSetup.TxnProtoStructure explicitProtoStructure = null;

    @Nullable
    protected BodyMutation bodyMutation = null;

    protected boolean asTxnWithSignedTxnBytesAndSigMap = false;
    protected boolean asTxnWithSignedTxnBytesAndBodyBytes = false;

    protected boolean useTls = false;
    protected HapiSpecSetup.TxnProtoStructure txnProtoStructure = HapiSpecSetup.TxnProtoStructure.ALTERNATE;
    protected Optional<ByteString> expectedLedgerId = Optional.empty();
    protected Optional<Integer> hardcodedNumPayerKeys = Optional.empty();
    protected Optional<SigMapGenerator> sigMapGen = Optional.empty();
    protected Optional<List<Function<HapiSpec, Key>>> signers = Optional.empty();
    protected Optional<ControlForKey[]> controlOverrides = Optional.empty();
    protected Map<Key, SigControl> overrides = Collections.EMPTY_MAP;

    protected Optional<Function<HapiSpec, Key>> batchKey = Optional.empty();
    protected Optional<Long> fee = Optional.empty();
    protected List<Function<HapiSpec, CustomFeeLimit>> maxCustomFeeList = new ArrayList<>();
    protected Optional<Long> validDurationSecs = Optional.empty();
    protected Optional<String> customTxnId = Optional.empty();
    protected Optional<String> memo = Optional.empty();
    protected Optional<String> metadata = Optional.empty();
    protected Optional<String> payer = Optional.empty();
    protected Optional<Boolean> genRecord = Optional.empty();
    protected Optional<String> node = Optional.empty();
    protected Optional<Supplier<AccountID>> nodeSupplier = Optional.empty();
    protected OptionalDouble usdFee = OptionalDouble.empty();
    protected Optional<Integer> retryLimits = Optional.empty();
    protected boolean payingWithAlias = false;

    @Nullable
    protected UnknownFieldLocation unknownFieldLocation = null;

    public enum UnknownFieldLocation {
        TRANSACTION,
        SIGNED_TRANSACTION,
        TRANSACTION_BODY,
        OP_BODY
    }

    protected abstract long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable;

    protected abstract boolean submitOp(HapiSpec spec) throws Throwable;

    protected Key lookupKey(final HapiSpec spec, final String name) {
        return spec.registry().getKey(name);
    }

    protected void updateStateOf(final HapiSpec spec) throws Throwable {
        /* no-op. */
    }

    protected void assertExpectationsGiven(final HapiSpec spec) throws Throwable {
        /* no-op. */
    }

    public HederaFunctionality type() {
        return HederaFunctionality.UNRECOGNIZED;
    }

    public void finalizeExecFor(final HapiSpec spec) throws Throwable {
        /* no-op. */
    }

    public boolean requiresFinalization(final HapiSpec spec) {
        return false;
    }

    protected AccountID targetNodeFor(final HapiSpec spec) {
        return fixNodeFor(spec);
    }

    protected void configureTlsFor(final HapiSpec spec) {
        useTls = spec.setup().getConfigTLS();
    }

    private void configureProtoStructureFor(final HapiSpec spec) {
        txnProtoStructure = (explicitProtoStructure != null)
                ? explicitProtoStructure
                : spec.setup().txnProtoStructure();
    }

    protected AccountID fixNodeFor(final HapiSpec spec) {
        if (node.isPresent()) {
            return asId(node.get(), spec);
        }
        if (nodeSupplier.isPresent()) {
            return nodeSupplier.get().get();
        } else {
            if (spec.setup().nodeSelector() == HapiSpecSetup.NodeSelection.RANDOM) {
                return randomNodeFrom(spec);
            } else {
                return spec.setup().defaultNode();
            }
        }
    }

    private AccountID randomNodeFrom(final HapiSpec spec) {
        final List<HederaNode> nodes = spec.targetNetworkOrThrow().nodes();
        return fromPbj(nodes.get(r.nextInt(nodes.size())).getAccountId());
    }

    public Optional<Throwable> execFor(final HapiSpec spec) {
        configureProtoStructureFor(spec);
        try {
            final boolean hasCompleteLifecycle = submitOp(spec);

            maybeRegisterTxnSubmitted(spec);

            if (hasCompleteLifecycle) {
                assertExpectationsGiven(spec);
                updateStateOf(spec);
            }
        } catch (final Throwable t) {
            if (verboseLoggingOn) {
                String message = MessageFormat.format("{0}{1} failed", spec.logPrefix(), this);
                log.warn(message, t);
            } else if (!loggingOff) {
                String message = MessageFormat.format("{0}{1} failed - {2}!", spec.logPrefix(), this, t.getMessage());
                log.warn(message, t);
            }
            return Optional.of(t);
        }

        return Optional.empty();
    }

    protected void maybeRegisterTxnSubmitted(final HapiSpec spec) throws Throwable {
        if (shouldRegisterTxn) {
            registerTransaction(spec, txnName, txnSubmitted);
        }
    }

    protected Consumer<TransactionBody.Builder> bodyDef(final HapiSpec spec) {
        return builder -> {
            if (omitTxnId) {
                builder.clearTransactionID();
            } else {
                payer.ifPresent(payerId -> {
                    AccountID id;
                    if (payingWithAlias) {
                        final var key = spec.registry().getKey(payerId);
                        final var lookedUpKey = key.toByteString();
                        id = asIdWithAlias(lookedUpKey);
                    } else {
                        id = TxnUtils.asId(payerId, spec);
                    }

                    final TransactionID txnId = builder.getTransactionID().toBuilder()
                            .setAccountID(id)
                            .build();
                    builder.setTransactionID(txnId);
                });
                if (usePresetTimestamp) {
                    final TransactionID txnId = builder.getTransactionID().toBuilder()
                            .setTransactionValidStart(spec.registry().getTimestamp(txnName))
                            .build();
                    builder.setTransactionID(txnId);
                }
                customTxnId.ifPresent(name -> {
                    final TransactionID id = spec.registry().getTxnId(name);
                    builder.setTransactionID(id);
                });
            }

            if (omitNodeAccount) {
                builder.clearNodeAccountID();
            } else {
                node.ifPresent((s) -> builder.setNodeAccountID(fixNodeFor(spec)));
            }
            validDurationSecs.ifPresent(s -> builder.setTransactionValidDuration(
                    Duration.newBuilder().setSeconds(s).build()));
            genRecord.ifPresent(builder::setGenerateRecord);
            memo.ifPresent(builder::setMemo);
            batchKey.ifPresent(k -> builder.setBatchKey(k.apply(spec)));
        };
    }

    protected Transaction finalizedTxn(final HapiSpec spec, final Consumer<TransactionBody.Builder> opDef)
            throws Throwable {
        return finalizedTxn(spec, opDef, false);
    }

    protected Transaction finalizedTxn(
            final HapiSpec spec, final Consumer<TransactionBody.Builder> opDef, final boolean forCost)
            throws Throwable {
        if ((forCost && useDefaultTxnAsCostAnswerPayment) || (!forCost && useDefaultTxnAsAnswerOnlyPayment)) {
            return Transaction.getDefaultInstance();
        }

        final Transaction txn;
        final Consumer<TransactionBody.Builder> minDef = bodyDef(spec);
        if (usdFee.isPresent()) {
            final double centsFee = usdFee.getAsDouble() * 100.0;
            final double tinybarFee = centsFee
                    / spec.ratesProvider().rates().getCentEquiv()
                    * spec.ratesProvider().rates().getHbarEquiv()
                    * HapiSuite.ONE_HBAR;
            fee = Optional.of((long) tinybarFee);
        }
        Consumer<TransactionBody.Builder> netDef = fee.map(amount -> minDef.andThen(b -> b.setTransactionFee(amount)))
                .orElse(minDef)
                .andThen(opDef);

        for (final var supplier : maxCustomFeeList) {
            netDef = netDef.andThen(b -> b.addMaxCustomFees(supplier.apply(spec)));
        }

        setKeyControlOverrides(spec);
        List<Key> keys = signersToUseFor(spec);

        final Transaction.Builder builder = spec.txns().getReadyToSign(netDef, bodyMutation, spec);
        final Transaction provisional = getSigned(spec, builder, keys);
        if (fee.isPresent()) {
            txn = provisional;
        } else {
            final Key payerKey =
                    spec.registry().getKey(payer.orElse(spec.setup().defaultPayerName()));
            final int numPayerKeys = hardcodedNumPayerKeys.orElse(spec.keys().controlledKeyCount(payerKey, overrides));
            final long customFee = feeFor(spec, provisional, numPayerKeys);
            netDef = netDef.andThen(b -> b.setTransactionFee(customFee));
            txn = getSigned(spec, spec.txns().getReadyToSign(netDef, bodyMutation, spec), keys);
        }

        return finalizedTxnFromTxnWithBodyBytesAndSigMap(txn);
    }

    protected static UnknownFieldSet nonEmptyUnknownFields() {
        return UnknownFieldSet.newBuilder()
                .addField(666, UnknownFieldSet.Field.newBuilder().addFixed32(42).build())
                .build();
    }

    private Transaction finalizedTxnFromTxnWithBodyBytesAndSigMap(Transaction txnWithBodyBytesAndSigMap)
            throws Throwable {
        if (asTxnWithOnlySigMap) {
            return txnWithBodyBytesAndSigMap.toBuilder().clearBodyBytes().build();
        }
        if (explicitProtoStructure == HapiSpecSetup.TxnProtoStructure.OLD) {
            return txnWithBodyBytesAndSigMap;
        }
        ByteString bodyByteString = CommonUtils.extractTransactionBodyByteString(txnWithBodyBytesAndSigMap);
        if (unknownFieldLocation == UnknownFieldLocation.TRANSACTION_BODY) {
            bodyByteString = TransactionBody.parseFrom(bodyByteString).toBuilder()
                    .setUnknownFields(nonEmptyUnknownFields())
                    .build()
                    .toByteString();
        }
        final SignatureMap sigMap = CommonUtils.extractSignatureMap(txnWithBodyBytesAndSigMap);
        final var wrapper =
                SignedTransaction.newBuilder().setBodyBytes(bodyByteString).setSigMap(sigMap);
        if (unknownFieldLocation == UnknownFieldLocation.SIGNED_TRANSACTION) {
            wrapper.setUnknownFields(nonEmptyUnknownFields());
        }
        final var signedTransaction = wrapper.build();
        final Transaction.Builder txnWithSignedTxnBytesBuilder =
                Transaction.newBuilder().setSignedTransactionBytes(signedTransaction.toByteString());
        if (asTxnWithSignedTxnBytesAndSigMap) {
            return txnWithSignedTxnBytesBuilder.setSigMap(sigMap).build();
        }
        if (asTxnWithSignedTxnBytesAndBodyBytes) {
            return txnWithSignedTxnBytesBuilder.setBodyBytes(bodyByteString).build();
        }
        if (txnProtoStructure == HapiSpecSetup.TxnProtoStructure.OLD) {
            if (unknownFieldLocation == UnknownFieldLocation.TRANSACTION) {
                txnWithBodyBytesAndSigMap = txnWithBodyBytesAndSigMap.toBuilder()
                        .setUnknownFields(nonEmptyUnknownFields())
                        .build();
            }
            return txnWithBodyBytesAndSigMap;
        }

        if (unknownFieldLocation == UnknownFieldLocation.TRANSACTION) {
            txnWithSignedTxnBytesBuilder.setUnknownFields(nonEmptyUnknownFields());
        }
        return txnWithSignedTxnBytesBuilder.build();
    }

    protected Transaction getSigned(final HapiSpec spec, final Transaction.Builder builder, final List<Key> keys)
            throws Throwable {
        return sigMapGen.isPresent()
                ? spec.keys().sign(spec, builder, keys, overrides, sigMapGen.get())
                : spec.keys().sign(spec, builder, keys, overrides);
    }

    public Map<Key, SigControl> setKeyControlOverrides(final HapiSpec spec) {
        if (controlOverrides.isPresent()) {
            overrides = new HashMap<>();
            Stream.of(controlOverrides.get())
                    .forEach(c -> overrides.put(lookupKey(spec, c.getKeyName()), c.getController()));
            return overrides;
        }
        return Collections.emptyMap();
    }

    public List<Key> signersToUseFor(final HapiSpec spec) {
        final List<Key> active = signers.orElse(defaultSigners()).stream()
                .map(f -> f.apply(spec))
                .filter(k -> k != null && k != Key.getDefaultInstance())
                .collect(toList());
        if (!signers.isPresent()) {
            active.addAll(variableDefaultSigners().apply(spec));
        }
        return active;
    }

    protected boolean isWithInRetryLimit(final int retryCount) {
        return retryLimits.map(integer -> retryCount < integer).orElse(true);
    }

    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return Arrays.asList(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    protected Function<HapiSpec, List<Key>> variableDefaultSigners() {
        return spec -> EMPTY_LIST;
    }

    protected String effectivePayer(final HapiSpec spec) {
        return payer.orElse(spec.setup().defaultPayerName());
    }

    protected void lookupSubmissionRecord(final HapiSpec spec) throws Throwable {
        final HapiGetTxnRecord subOp = getTxnRecord(extractTxnId(txnSubmitted))
                .noLogging()
                .assertingNothing()
                .nodePayment(spec.setup().defaultNodePaymentTinyBars());
        final Optional<Throwable> error = subOp.execFor(spec);
        if (error.isPresent()) {
            throw error.get();
        }
        recordOfSubmission = subOp.getResponse().getTransactionGetRecord().getTransactionRecord();
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this);
        if (txnSubmitted != null) {
            helper.add("sigs", FeeBuilder.getSignatureCount(txnSubmitted));
        }
        payer.ifPresent(a -> helper.add("payer", a));
        node.ifPresent(id -> helper.add("node", id));
        return helper;
    }

    public Optional<String> getPayer() {
        return payer;
    }

    public String getTxnName() {
        return txnName;
    }

    public boolean shouldRegisterTxn() {
        return shouldRegisterTxn;
    }

    protected ByteString rationalize(final String expectedLedgerId) {
        final var hex = expectedLedgerId.substring(2);
        final var bytes = HexFormat.of().parseHex(hex);
        return ByteString.copyFrom(bytes);
    }

    /**
     * Registers a transaction in a {@link HapiSpec}'s registry by a given name
     *
     * @param spec the spec to register the transaction with
     * @param txnName the name given to reference the transaction
     * @param txn the value to store for the given name
     * @throws Throwable if no transaction ID can be extracted from the given `txn` param
     */
    public static void registerTransaction(final HapiSpec spec, final String txnName, final Transaction txn)
            throws Throwable {
        if (txn != Transaction.getDefaultInstance()) {
            spec.registry().saveBytes(txnName, txn.toByteString());
            final TransactionID txnId = extractTxnId(txn);
            spec.registry().saveTxnId(txnName, txnId);
        }
    }
}
