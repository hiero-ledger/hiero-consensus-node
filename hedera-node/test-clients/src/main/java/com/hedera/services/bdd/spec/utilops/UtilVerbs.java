// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromByteString;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_LOG;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.ensureDir;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiPropertySource.idAsHeadlongAddress;
import static com.hedera.services.bdd.spec.TargetNetworkType.EMBEDDED_NETWORK;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.BYTES_4K;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTransactionID;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.timeUntilNextPeriod;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateInitCodeWithConstructorArgs;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.file.HapiFileUpdate.getUpdated121;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.log;
import static com.hedera.services.bdd.spec.utilops.pauses.HapiSpecWaitUntil.untilJustBeforeStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.pauses.HapiSpecWaitUntil.untilStartOfNextAdhocPeriod;
import static com.hedera.services.bdd.spec.utilops.pauses.HapiSpecWaitUntil.untilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.streams.LogContainmentOp.Containment.CONTAINS;
import static com.hedera.services.bdd.spec.utilops.streams.LogContainmentOp.Containment.DOES_NOT_CONTAIN;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.isLongZeroAddress;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.sdec;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.ThrottleDefsLoader.protoDefsFromResource;
import static com.hederahashgraph.api.proto.java.FreezeType.FREEZE_ABORT;
import static com.hederahashgraph.api.proto.java.FreezeType.FREEZE_ONLY;
import static com.hederahashgraph.api.proto.java.FreezeType.FREEZE_UPGRADE;
import static com.hederahashgraph.api.proto.java.FreezeType.PREPARE_UPGRADE;
import static com.hederahashgraph.api.proto.java.FreezeType.TELEMETRY_UPGRADE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS_BUT_MISSING_EXPECTED_OPERATION;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.MarkerFile;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedNetwork;
import com.hedera.services.bdd.junit.hedera.embedded.SyntheticVersion;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.RegistryNotFound;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.consensus.HapiMessageSubmit;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCreate;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumContractCreate;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.transactions.file.HapiFileAppend;
import com.hedera.services.bdd.spec.transactions.file.HapiFileCreate;
import com.hedera.services.bdd.spec.transactions.file.HapiFileUpdate;
import com.hedera.services.bdd.spec.transactions.file.UploadProgress;
import com.hedera.services.bdd.spec.transactions.system.HapiFreeze;
import com.hedera.services.bdd.spec.utilops.checks.VerifyAddLiveHashNotSupported;
import com.hedera.services.bdd.spec.utilops.checks.VerifyGetBySolidityIdNotSupported;
import com.hedera.services.bdd.spec.utilops.checks.VerifyGetExecutionTimeNotSupported;
import com.hedera.services.bdd.spec.utilops.checks.VerifyGetLiveHashNotSupported;
import com.hedera.services.bdd.spec.utilops.checks.VerifyUserFreezeNotAuthorized;
import com.hedera.services.bdd.spec.utilops.embedded.MutateAccountOp;
import com.hedera.services.bdd.spec.utilops.embedded.MutateNodeOp;
import com.hedera.services.bdd.spec.utilops.grouping.GroupedOps;
import com.hedera.services.bdd.spec.utilops.grouping.InBlockingOrder;
import com.hedera.services.bdd.spec.utilops.grouping.ParallelSpecOps;
import com.hedera.services.bdd.spec.utilops.inventory.NewSpecKey;
import com.hedera.services.bdd.spec.utilops.inventory.NewSpecKeyList;
import com.hedera.services.bdd.spec.utilops.inventory.NewSpecThresholdKey;
import com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromEcdsaFile;
import com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromFile;
import com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromLiteral;
import com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromMnemonic;
import com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromMutation;
import com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromPem;
import com.hedera.services.bdd.spec.utilops.inventory.UsableTxnId;
import com.hedera.services.bdd.spec.utilops.lifecycle.ops.CandidateRosterValidationOp;
import com.hedera.services.bdd.spec.utilops.lifecycle.ops.PurgeUpgradeArtifactsOp;
import com.hedera.services.bdd.spec.utilops.lifecycle.ops.WaitForMarkerFileOp;
import com.hedera.services.bdd.spec.utilops.lifecycle.ops.WaitForStatusOp;
import com.hedera.services.bdd.spec.utilops.mod.QueryModification;
import com.hedera.services.bdd.spec.utilops.mod.QueryModificationsOp;
import com.hedera.services.bdd.spec.utilops.mod.SubmitModificationsOp;
import com.hedera.services.bdd.spec.utilops.mod.TxnModification;
import com.hedera.services.bdd.spec.utilops.pauses.HapiSpecSleep;
import com.hedera.services.bdd.spec.utilops.pauses.HapiSpecWaitUntil;
import com.hedera.services.bdd.spec.utilops.pauses.HapiSpecWaitUntilNextBlock;
import com.hedera.services.bdd.spec.utilops.streams.BlockStreamValidationOp;
import com.hedera.services.bdd.spec.utilops.streams.LogContainmentOp;
import com.hedera.services.bdd.spec.utilops.streams.LogContainmentTimeframeOp;
import com.hedera.services.bdd.spec.utilops.streams.LogValidationOp;
import com.hedera.services.bdd.spec.utilops.streams.StreamValidationOp;
import com.hedera.services.bdd.spec.utilops.streams.assertions.AbstractEventualStreamAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.AssertingBiConsumer;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.EventualBlockStreamAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.EventualRecordStreamAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.RecordStreamAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.TransactionBodyAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.ValidContractIdsAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsAssertion.SkipSynthItems;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.perf.PerfTestLoadSettings;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.FeesJsonToGrpcBytes;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.SysFileSerde;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.CurrentAndNextFeeSchedule;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Setting;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;

public class UtilVerbs {
    public static final int DEFAULT_COLLISION_AVOIDANCE_FACTOR = 2;
    private static final Duration HISTORY_PROOF_WAIT_TIMEOUT = Duration.ofMinutes(50);

    /**
     * Private constructor to prevent instantiation.
     *
     * @throws UnsupportedOperationException if invoked by reflection or other means.
     */
    private UtilVerbs() {
        throw new UnsupportedOperationException();
    }

    public static HapiFreeze freeze() {
        return new HapiFreeze();
    }

    public static HapiFreeze prepareUpgrade() {
        return new HapiFreeze(PREPARE_UPGRADE);
    }

    public static HapiFreeze telemetryUpgrade() {
        return new HapiFreeze(TELEMETRY_UPGRADE);
    }

    /**
     * Returns an operation that ensures staking is activated. In general this is the one
     * property override that doesn't need default values to be preserved, since all production
     * network behavior must work with staking active in any case.
     *
     * @return the operation that ensures staking is activated
     */
    public static HapiSpecOperation ensureStakingActivated() {
        return blockingOrder(
                overridingTwo(
                        "staking.startThreshold", "" + 0,
                        "staking.rewardBalanceThreshold", "" + 0),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)));
    }

    /**
     * Returns an operation that, when executed, will compute a delegate operation by calling the given factory
     * with the startup value of the given property on the target network; and execute its delegate.
     *
     * @param property the property whose startup value is needed for the delegate operation
     * @param factory the factory for the delegate operation
     * @return the operation that will execute the delegate created from the target network's startup value
     */
    public static SpecOperation doWithStartupConfig(
            @NonNull final String property, @NonNull final Function<String, SpecOperation> factory) {
        return doSeveralWithStartupConfig(property, startupValue -> new SpecOperation[] {factory.apply(startupValue)});
    }

    /**
     * Returns an operation that, when executed, will compute a delegate operation by calling the given factory
     * with the startup value of the given property on the target network; and execute its delegate.
     *
     * @param factory the factory for the delegate operation
     * @return the operation that will execute the delegate created from the target network's startup value
     */
    public static SpecOperation doWithShardAndRealm(@NonNull final BiFunction<Long, Long, SpecOperation> factory) {
        return withOpContext((spec, opLog) -> {
            final long shard = spec.targetNetworkOrThrow().startupProperties().getLong("hedera.shard");
            final long realm = spec.targetNetworkOrThrow().startupProperties().getLong("hedera.realm");
            allRunFor(spec, factory.apply(shard, realm));
        });
    }

    /**
     * Returns an operation that, when executed, will compute a delegate operation by calling the given factory
     * with the startup value of the given property on the target network and its current consensus time; and
     * execute its delegate.
     *
     * @param property the property whose startup value is needed for the delegate operation
     * @param factory the factory for the delegate operation
     * @return the operation that will execute the delegate created from the target network's startup value
     */
    public static SpecOperation doWithStartupConfigNow(
            @NonNull final String property, @NonNull final BiFunction<String, Instant, SpecOperation> factory) {
        return doSeveralWithStartupConfigNow(property, (startupValue, consensusTime) ->
                new SpecOperation[] {factory.apply(startupValue, consensusTime)});
    }

    /**
     * Returns an operation that, when executed, will compute a sequence of delegate operation by calling the
     * given factory with the startup value of the given property on the target network and its current consensus time;
     * and execute the delegates in order.
     *
     * @param property the property whose startup value is needed for the delegate operation
     * @param factory the factory for the delegate operations
     * @return the operation that will execute the delegate created from the target network's startup value
     */
    public static SpecOperation doSeveralWithStartupConfigNow(
            @NonNull final String property, @NonNull final BiFunction<String, Instant, SpecOperation[]> factory) {
        return withOpContext((spec, opLog) -> {
            final var startupValue =
                    spec.targetNetworkOrThrow().startupProperties().get(property);
            allRunFor(spec, factory.apply(startupValue, spec.consensusTime()));
        });
    }

    /**
     * Returns an operation that, when executed, will compute a delegate operation by calling the given factory
     * with the startup value of the given property on the target network; and execute its delegate.
     *
     * @param property the property whose startup value is needed for the delegate operation
     * @param factory the factory for the delegate operation
     * @return the operation that will execute the delegate created from the target network's startup value
     */
    public static SpecOperation doSeveralWithStartupConfig(
            @NonNull final String property, @NonNull final Function<String, SpecOperation[]> factory) {
        return withOpContext((spec, opLog) -> {
            final var startupValue =
                    spec.targetNetworkOrThrow().startupProperties().get(property);
            allRunFor(spec, factory.apply(startupValue));
        });
    }

    public static HapiFreeze freezeOnly() {
        return new HapiFreeze(FREEZE_ONLY);
    }

    public static HapiFreeze freezeUpgrade() {
        return new HapiFreeze(FREEZE_UPGRADE);
    }

    public static HapiFreeze freezeAbort() {
        return new HapiFreeze(FREEZE_ABORT);
    }

    /**
     * Returns an operation that validates the streams of the target network.
     *
     * @return the operation that validates the streams
     */
    public static StreamValidationOp validateStreams() {
        final int proofsToWaitFor = Optional.ofNullable(System.getProperty("hapi.spec.numHistoryProofsToObserve"))
                .map(Integer::parseInt)
                .orElse(0);
        return new StreamValidationOp(proofsToWaitFor, HISTORY_PROOF_WAIT_TIMEOUT);
    }

    /**
     * Returns an operation that validates the streams of the target network with dynamic validators
     *
     * @return the operation that validates the streams
     */
    public static BlockStreamValidationOp validateBlockStream() {
        return new BlockStreamValidationOp();
    }

    /**
     * Returns an operation that delays for the given time and then validates
     * any of the target network node application logs.
     *
     * @return the operation that validates the logs of a node
     */
    public static HapiSpecOperation validateAnyLogAfter(@NonNull final Duration delay) {
        return new LogValidationOp(LogValidationOp.Scope.ANY_NODE, delay);
    }

    /**
     * Returns an operation that delays for the given time and then validates that the selected nodes'
     * application logs contain the given pattern.
     *
     * @param selector the selector for the node whose log to validate
     * @param pattern the pattern that must be present
     * @param delay the delay before validation
     * @return the operation that validates the logs of the target network
     */
    public static LogContainmentOp assertHgcaaLogContains(
            @NonNull final NodeSelector selector, @NonNull final String pattern, @NonNull final Duration delay) {
        return new LogContainmentOp(selector, APPLICATION_LOG, CONTAINS, pattern, delay);
    }

    /**
     * Returns an operation that delays for the given time and then validates that the selected nodes'
     * application logs do not contain the given pattern.
     *
     * @param selector the selector for the node whose log to validate
     * @param pattern the pattern that must be present
     * @param delay the delay before validation
     * @return the operation that validates the logs of the target network
     */
    public static LogContainmentOp assertHgcaaLogDoesNotContain(
            @NonNull final NodeSelector selector, @NonNull final String pattern, @NonNull final Duration delay) {
        return new LogContainmentOp(selector, APPLICATION_LOG, DOES_NOT_CONTAIN, pattern, delay);
    }

    /**
     * Returns an operation that delays for the given time and then validates
     * all of the target network node application logs.
     *
     * @return the operation that validates the logs of the target network
     */
    public static HapiSpecOperation validateAllLogsAfter(@NonNull final Duration delay) {
        return new LogValidationOp(LogValidationOp.Scope.ALL_NODES, delay);
    }

    /* Some fairly simple utility ops */
    public static InBlockingOrder blockingOrder(SpecOperation... ops) {
        return new InBlockingOrder(ops);
    }

    public static NetworkTypeFilterOp ifNotEmbeddedTest(@NonNull final HapiSpecOperation... ops) {
        return new NetworkTypeFilterOp(EnumSet.complementOf(EnumSet.of(EMBEDDED_NETWORK)), ops);
    }

    public static EnvFilterOp ifCi(@NonNull final HapiSpecOperation... ops) {
        requireNonNull(ops);
        return new EnvFilterOp(EnvFilterOp.EnvType.CI, ops);
    }

    public static EnvFilterOp ifNotCi(@NonNull final HapiSpecOperation... ops) {
        requireNonNull(ops);
        return new EnvFilterOp(EnvFilterOp.EnvType.NOT_CI, ops);
    }

    /**
     * Returns an operation that repeatedly submits a transaction from the given
     * supplier, but each time after modifying its body with one of the
     * {@link TxnModification}'s computed by the given function.
     *
     * <p>This function will be called with the <b>unmodified</b> transaction,
     * so that the modifications are all made relative to the same initial
     * transaction.
     *
     * @param modificationsFn the function that computes modifications to apply
     * @param txnOpSupplier the supplier of the transaction to submit
     * @return the operation that submits the modified transactions
     */
    public static SubmitModificationsOp submitModified(
            @NonNull final Function<Transaction, List<TxnModification>> modificationsFn,
            @NonNull final Supplier<HapiTxnOp<?>> txnOpSupplier) {
        return new SubmitModificationsOp(txnOpSupplier, modificationsFn);
    }

    public static SubmitModificationsOp submitModifiedWithFixedPayer(
            @NonNull final Function<Transaction, List<TxnModification>> modificationsFn,
            @NonNull final Supplier<HapiTxnOp<?>> txnOpSupplier) {
        return new SubmitModificationsOp(false, txnOpSupplier, modificationsFn);
    }

    /**
     * Returns an operation that repeatedly sends a query from the given
     * supplier, but each time after modifying the query with one of the
     * {@link QueryModification}'s computed by the given function.
     *
     * <p>This function will be called with the <b>unmodified</b> query,
     * so that the modifications are all made relative to the same initial
     * query.
     *
     * @param modificationsFn the function that computes modifications to apply
     * @param queryOpSupplier the supplier of the query to send
     * @return the operation that sends the modified queries
     */
    public static QueryModificationsOp sendModified(
            @NonNull final Function<Query, List<QueryModification>> modificationsFn,
            @NonNull final Supplier<HapiQueryOp<?>> queryOpSupplier) {
        return new QueryModificationsOp(queryOpSupplier, modificationsFn);
    }

    public static QueryModificationsOp sendModifiedWithFixedPayer(
            @NonNull final Function<Query, List<QueryModification>> modificationsFn,
            @NonNull final Supplier<HapiQueryOp<?>> queryOpSupplier) {
        return new QueryModificationsOp(false, queryOpSupplier, modificationsFn);
    }

    public static SourcedOp sourcing(Supplier<HapiSpecOperation> source) {
        return new SourcedOp(source);
    }

    public static ContextualSourcedOp sourcingContextual(Function<HapiSpec, SpecOperation> source) {
        return new ContextualSourcedOp(source);
    }

    public static ContextualActionOp doingContextual(Consumer<HapiSpec> action) {
        return new ContextualActionOp(action);
    }

    public static WaitForStatusOp waitForActive(String name, Duration timeout) {
        return waitForActive(NodeSelector.byName(name), timeout);
    }

    public static WaitForStatusOp waitForActive(@NonNull final NodeSelector selector, @NonNull final Duration timeout) {
        return new WaitForStatusOp(selector, timeout, ACTIVE);
    }

    /**
     * Returns an operation that waits for the target node to be any of the given statuses.
     * @param selector the selector for the node to wait for
     * @param timeout the maximum time to wait for the node to reach one of the desired statuses
     * @param statuses the statuses to wait for
     * @return the operation that waits for the node to reach one of the desired statuses
     */
    public static WaitForStatusOp waitForAny(
            @NonNull final NodeSelector selector,
            @NonNull final Duration timeout,
            @NonNull final PlatformStatus... statuses) {
        return new WaitForStatusOp(selector, timeout, statuses);
    }

    /**
     * Returns an operation that waits for the target network to be active, and if this is a subprocess network,
     * refreshes the gRPC clients to reflect reassigned ports.
     * @param timeout the maximum time to wait for the network to become active
     * @return the operation that waits for the network to become active
     */
    public static SpecOperation waitForActiveNetworkWithReassignedPorts(@NonNull final Duration timeout) {
        return blockingOrder(new WaitForStatusOp(NodeSelector.allNodes(), timeout, ACTIVE), doingContextual(spec -> {
            if (spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork) {
                subProcessNetwork.refreshClients();
            }
        }));
    }

    /**
     * Returns a submission strategy that requires an embedded network and given one submits a transaction with
     * the given synthetic version.
     *
     * @param syntheticVersion the synthetic version to use
     * @return the submission strategy
     */
    public static HapiTxnOp.SubmissionStrategy usingVersion(@NonNull final SyntheticVersion syntheticVersion) {
        return (network, transaction, functionality, target, nodeAccountId) -> {
            if (!(network instanceof EmbeddedNetwork embeddedNetwork)) {
                throw new IllegalArgumentException("Expected an EmbeddedNetwork");
            }
            return embeddedNetwork.embeddedHederaOrThrow().submit(transaction, nodeAccountId, syntheticVersion);
        };
    }

    /**
     * Returns a submission strategy that requires an embedded network and given one submits a transaction with
     * the given {@link StateSignatureTransaction}-callback.
     *
     * @param preHandleCallback the callback that is called during preHandle when a {@link StateSignatureTransaction} is encountered
     * @param handleCallback the callback that is called when a {@link StateSignatureTransaction} is encountered
     * @return the submission strategy
     */
    public static HapiTxnOp.SubmissionStrategy usingStateSignatureTransactionCallback(
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> preHandleCallback,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> handleCallback) {
        return (network, transaction, functionality, target, nodeAccountId) -> {
            if (!(network instanceof EmbeddedNetwork embeddedNetwork)) {
                throw new IllegalArgumentException("Expected an EmbeddedNetwork");
            }
            return embeddedNetwork
                    .embeddedHederaOrThrow()
                    .submit(transaction, nodeAccountId, preHandleCallback, handleCallback);
        };
    }

    public static WaitForStatusOp waitForFrozenNetwork(@NonNull final Duration timeout) {
        return waitForFrozenNetwork(timeout, NodeSelector.allNodes());
    }

    public static WaitForStatusOp waitForFrozenNetwork(
            @NonNull final Duration timeout, @NonNull final NodeSelector selector) {
        return new WaitForStatusOp(selector, timeout, FREEZE_COMPLETE);
    }

    /**
     * Returns an operation that initiates background traffic running until the target network's
     * first node has reached {@link PlatformStatus#FREEZE_COMPLETE}.
     * @return the operation
     */
    public static SpecOperation runBackgroundTrafficUntilFreezeComplete() {
        return withOpContext((spec, opLog) -> {
            opLog.info("Starting background traffic until freeze complete");
            final var stopTraffic = new AtomicBoolean();
            CompletableFuture.runAsync(() -> {
                while (!stopTraffic.get()) {
                    allRunFor(
                            spec,
                            cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, 1))
                                    .fireAndForget()
                                    .noLogging());
                    spec.sleepConsensusTime(Duration.ofMillis(1L));
                }
            });
            spec.targetNetworkOrThrow()
                    .nodes()
                    .getFirst()
                    .statusFuture((status) -> {}, FREEZE_COMPLETE)
                    .thenRun(() -> {
                        stopTraffic.set(true);
                        opLog.info("Stopping background traffic after freeze complete");
                    });
        });
    }

    public static HapiSpecSleep sleepForSeconds(final long seconds) {
        return sleepFor(seconds * 1_000L);
    }

    public static HapiSpecSleep sleepFor(long timeMs) {
        return new HapiSpecSleep(timeMs);
    }

    public static HapiSpecWaitUntil waitUntil(String timeOfDay) throws ParseException {
        return new HapiSpecWaitUntil(timeOfDay);
    }

    public static HapiSpecWaitUntil waitUntilStartOfNextStakingPeriod(final long stakePeriodMins) {
        return untilStartOfNextStakingPeriod(stakePeriodMins);
    }

    public static BuildUpgradeZipOp buildUpgradeZipFrom(@NonNull final Path path) {
        return new BuildUpgradeZipOp(path);
    }

    public static WaitForMarkerFileOp waitForMf(@NonNull final MarkerFile markerFile, @NonNull final Duration timeout) {
        return new WaitForMarkerFileOp(NodeSelector.allNodes(), markerFile, timeout);
    }

    /**
     * Returns an operation that validates that each node's generated <i>config.txt</i> in its upgrade
     * artifacts directory passes the given validator.
     *
     * @param rosterValidator the validator to apply to each node's <i>config.txt</i>
     * @return the operation that validates the <i>config.txt</i> files
     */
    public static CandidateRosterValidationOp validateCandidateRoster(@NonNull final Consumer<Roster> rosterValidator) {
        return validateCandidateRoster(NodeSelector.allNodes(), rosterValidator);
    }

    /**
     * Returns an operation that validates that each node's generated <i>config.txt</i> in its upgrade
     * artifacts directory passes the given validator.
     *
     * @param selector the selector for the nodes to validate
     * @param rosterValidator the validator to apply to each node's <i>config.txt</i>
     * @return the operation that validates the <i>config.txt</i> files
     */
    public static CandidateRosterValidationOp validateCandidateRoster(
            @NonNull final NodeSelector selector, @NonNull final Consumer<Roster> rosterValidator) {
        return new CandidateRosterValidationOp(selector, rosterValidator);
    }

    /**
     * Returns an operation that purges the upgrade artifacts directory on each node.
     *
     * @return the operation that purges the upgrade artifacts directory
     */
    public static PurgeUpgradeArtifactsOp purgeUpgradeArtifacts() {
        return new PurgeUpgradeArtifactsOp(NodeSelector.allNodes());
    }

    /**
     * Returns an operation that, if the current time is "too close" to
     * the next staking period start (as measured by a given window),
     * performs a given {@code then} operation.
     *
     * <p>Useful when you want to perform consecutive operations to,
     * <ol>
     *     <Li>Create a staking account; then</Li>
     *     <Li>Wait until the start of a staking period; then</Li>
     *     <Li>Wait until one more staking period starts so that
     *     the account is eligible for one period of rewards.</Li>
     * </ol>
     * To be sure your account will be eligible for only one period of
     * rewards, you need to ensure that the first two operations occur
     * <b>in the same period</b>.
     *
     * <p>By giving a {@link HapiSpecWaitUntil} operation as {@code then},
     * and a conservative window, you can ensure that the first two operations
     * occur in the same period without adding a full period of delay to
     * every test execution.
     *
     * @param window the minimum time until the next period start that will trigger the wait
     * @param stakePeriodMins the length of the staking period in minutes
     * @param then the operation to perform if the current time is within the window
     * @return the operation that conditionally does the {@code then} operation
     */
    public static HapiSpecOperation ifNextStakePeriodStartsWithin(
            @NonNull final Duration window, final long stakePeriodMins, @NonNull final HapiSpecOperation then) {
        return withOpContext((spec, opLog) -> {
            final var buffer = timeUntilNextPeriod(spec.consensusTime(), stakePeriodMins);
            if (buffer.compareTo(window) < 0) {
                opLog.info("Waiting for next staking period, buffer {} less than window {}", buffer, window);
                allRunFor(spec, then);
            }
        });
    }

    /**
     * Returns a {@link HapiSpecOperation} that sleeps until the beginning of the next period
     * of the given length since the UTC epoch in clock time.
     *
     * <p>This is not the same thing as sleeping until the next <i>consensus</i> period, of
     * course; but since consensus time will track clock time very closely in practice, this
     * operation can let us be almost certain we have e.g. moved into a new staking period
     * or a new block period by the time the sleep ends.
     *
     * @param periodMs the length of the period in milliseconds
     * @return the operation that sleeps until the beginning of the next period
     */
    public static HapiSpecWaitUntil waitUntilStartOfNextAdhocPeriod(final long periodMs) {
        return untilStartOfNextAdhocPeriod(periodMs);
    }

    /**
     * Returns a {@link HapiSpecOperation} that sleeps until at least the beginning of the next block stream block.
     * @return the operation that sleeps until the beginning of the next block stream block
     */
    public static HapiSpecWaitUntilNextBlock waitUntilNextBlock() {
        return new HapiSpecWaitUntilNextBlock();
    }

    public static HapiSpecWaitUntil waitUntilJustBeforeNextStakingPeriod(
            final long stakePeriodMins, final long secondsBefore) {
        return untilJustBeforeStakingPeriod(stakePeriodMins, secondsBefore);
    }

    public static UsableTxnId usableTxnIdNamed(String txnId) {
        return new UsableTxnId(txnId);
    }

    public static SpecKeyFromMnemonic keyFromMnemonic(String name, String mnemonic) {
        return new SpecKeyFromMnemonic(name, mnemonic);
    }

    public static SpecKeyFromMutation keyFromMutation(String name, String mutated) {
        return new SpecKeyFromMutation(name, mutated);
    }

    public static SpecKeyFromLiteral keyFromLiteral(String name, String hexEncodedPrivateKey) {
        return new SpecKeyFromLiteral(name, hexEncodedPrivateKey);
    }

    public static SpecKeyFromEcdsaFile keyFromEcdsaFile(String loc, String name) {
        return new SpecKeyFromEcdsaFile(loc, name);
    }

    public static SpecKeyFromEcdsaFile keyFromEcdsaFile(String loc) {
        return new SpecKeyFromEcdsaFile(loc, loc);
    }

    public static SpecKeyFromFile keyFromFile(String name, String flexLoc) {
        return new SpecKeyFromFile(name, flexLoc);
    }

    public static SpecKeyFromPem keyFromPem(String pemLoc) {
        return new SpecKeyFromPem(pemLoc);
    }

    public static SpecKeyFromPem keyFromPem(Supplier<String> pemLocFn) {
        return new SpecKeyFromPem(pemLocFn);
    }

    public static NewSpecKey newKeyNamed(String key) {
        return new NewSpecKey(key);
    }

    public static NewSpecKeyList newKeyListNamed(String key, List<String> childKeys) {
        return new NewSpecKeyList(key, childKeys);
    }

    public static NewSpecThresholdKey newThresholdKeyNamed(String key, int nRequired, List<String> childKeys) {
        return new NewSpecThresholdKey(key, nRequired, childKeys);
    }

    /**
     * Unless the {@link HapiSpec} is in a repeatable mode, returns an operation that will
     * run the given sub-operations in parallel.
     *
     * <p>If in repeatable mode, instead returns an operation that will run the sub-operations
     * in blocking order, since parallelism can lead to non-deterministic outcomes.
     *
     * @param subs the sub-operations to run in parallel
     * @return the operation that runs the sub-operations in parallel
     */
    public static GroupedOps<?> inParallel(@NonNull final SpecOperation... subs) {
        return "repeatable".equalsIgnoreCase(System.getProperty("hapi.spec.embedded.mode"))
                ? blockingOrder(subs)
                : new ParallelSpecOps(subs);
    }

    public static CustomSpecAssert assertionsHold(CustomSpecAssert.ThrowingConsumer custom) {
        return new CustomSpecAssert(custom);
    }

    public static CustomSpecAssert addLogInfo(CustomSpecAssert.ThrowingConsumer custom) {
        return new CustomSpecAssert(custom);
    }

    public static CustomSpecAssert withOpContext(CustomSpecAssert.ThrowingConsumer custom) {
        return new CustomSpecAssert(custom);
    }

    private static final ByteString MAINNET_LEDGER_ID = ByteString.copyFrom(new byte[] {0x00});
    private static final ByteString TESTNET_LEDGER_ID = ByteString.copyFrom(new byte[] {0x01});
    private static final ByteString PREVIEWNET_LEDGER_ID = ByteString.copyFrom(new byte[] {0x02});
    private static final ByteString DEVNET_LEDGER_ID = ByteString.copyFrom(new byte[] {0x03});

    private static final Set<ByteString> RECOGNIZED_LEDGER_IDS =
            Set.of(MAINNET_LEDGER_ID, TESTNET_LEDGER_ID, PREVIEWNET_LEDGER_ID, DEVNET_LEDGER_ID);

    /**
     * Returns an operation that uses a {@link com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountInfo} query
     * against the {@code 0.0.2} account to look up the ledger id of the target network; and then passes the ledger
     * id to the given callback.
     *
     * @param ledgerIdConsumer the callback to pass the ledger id to
     * @return the operation exposing the ledger id to the callback
     */
    public static HapiSpecOperation exposeTargetLedgerIdTo(@NonNull final Consumer<ByteString> ledgerIdConsumer) {
        return getAccountInfo(GENESIS).payingWith(GENESIS).exposingLedgerIdTo(ledgerId -> {
            if (!RECOGNIZED_LEDGER_IDS.contains(ledgerId)) {
                Assertions.fail(
                        "Target network is claiming unrecognized ledger id " + CommonUtils.hex(ledgerId.toByteArray()));
            }
            ledgerIdConsumer.accept(ledgerId);
        });
    }

    /**
     * A convenience operation that accepts a factory mapping the target ledger id into a {@link HapiSpecOperation}
     * (for example, a query that asserts something about the ledger id); and then,
     * <ol>
     *     <Li>Looks up the ledger id via {@link UtilVerbs#exposeTargetLedgerIdTo(Consumer)}; and,</Li>
     *     <Li>Calls the given factory with this id, and runs the resulting {@link HapiSpecOperation}.</Li>
     * </ol>
     *
     * @param opFn the factory mapping the ledger id into a {@link HapiSpecOperation}
     * @return the operation that looks up the ledger id and runs the resulting {@link HapiSpecOperation}
     */
    public static HapiSpecOperation withTargetLedgerId(@NonNull final Function<ByteString, HapiSpecOperation> opFn) {
        final AtomicReference<ByteString> targetLedgerId = new AtomicReference<>();
        return blockingOrder(
                exposeTargetLedgerIdTo(targetLedgerId::set), sourcing(() -> opFn.apply(targetLedgerId.get())));
    }

    public static BalanceSnapshot balanceSnapshot(String name, String forAccount) {
        return new BalanceSnapshot(forAccount, name);
    }

    public static MutateAccountOp mutateAccount(
            @NonNull final String name, @NonNull final Consumer<Account.Builder> mutation) {
        return new MutateAccountOp(name, mutation);
    }

    public static MutateNodeOp mutateNode(@NonNull final String name, @NonNull final Consumer<Node.Builder> mutation) {
        return new MutateNodeOp(name, mutation);
    }

    public static BalanceSnapshot balanceSnapshot(Function<HapiSpec, String> nameFn, String forAccount) {
        return new BalanceSnapshot(forAccount, nameFn);
    }

    public static VerifyGetLiveHashNotSupported getClaimNotSupported() {
        return new VerifyGetLiveHashNotSupported();
    }

    public static VerifyGetExecutionTimeNotSupported getExecutionTimeNotSupported() {
        return new VerifyGetExecutionTimeNotSupported();
    }

    public static VerifyGetBySolidityIdNotSupported getBySolidityIdNotSupported() {
        return new VerifyGetBySolidityIdNotSupported();
    }

    public static VerifyAddLiveHashNotSupported verifyAddLiveHashNotSupported() {
        return new VerifyAddLiveHashNotSupported();
    }

    public static VerifyUserFreezeNotAuthorized verifyUserFreezeNotAuthorized() {
        return new VerifyUserFreezeNotAuthorized();
    }

    public static RunLoadTest runLoadTest(Supplier<HapiSpecOperation[]> opSource) {
        return new RunLoadTest(opSource);
    }

    public static NoOp noOp() {
        return new NoOp();
    }

    public static LogMessage logIt(String msg) {
        return new LogMessage(msg);
    }

    public static LogMessage logIt(Function<HapiSpec, String> messageFn) {
        return new LogMessage(messageFn);
    }

    public static ProviderRun runWithProvider(Function<HapiSpec, OpProvider> provider) {
        return new ProviderRun(provider);
    }

    public static HapiSpecOperation overriding(String property, String value) {
        return overridingAllOf(Map.of(property, value));
    }

    /**
     * Returns an operation that overrides the throttles on the target network to the values from the named resource.
     * @param resource the resource to load the throttles from
     * @return the operation that overrides the throttles
     */
    public static SpecOperation overridingThrottles(@NonNull final String resource) {
        requireNonNull(resource);
        return sourcing(() -> fileUpdate(THROTTLE_DEFS)
                .noLogging()
                .payingWith(GENESIS)
                .contents(protoDefsFromResource(resource).toByteArray())
                .hasKnownStatusFrom(SUCCESS, SUCCESS_BUT_MISSING_EXPECTED_OPERATION));
    }

    /**
     * Returns an operation that attempts overrides the throttles on the target network to the values from the
     * named resource and expects the given failure status.
     * @param resource the resource to load the throttles from
     * @param status the expected status
     * @return the operation that overrides the throttles and expects failure
     */
    public static SpecOperation overridingThrottlesFails(
            @NonNull final String resource, @NonNull final ResponseCodeEnum status) {
        requireNonNull(resource);
        requireNonNull(status);
        return sourcing(() -> fileUpdate(THROTTLE_DEFS)
                .noLogging()
                .payingWith(GENESIS)
                .contents(protoDefsFromResource(resource).toByteArray())
                .hasKnownStatus(status));
    }

    /**
     * Returns an operation that restores the given property to its startup value on the target network.
     *
     * @param property the property to restore
     * @return the operation that restores the property
     */
    public static SpecOperation restoreDefault(@NonNull final String property) {
        return doWithStartupConfig(property, value -> overriding(property, value));
    }

    /**
     * Returns an operation that runs a given callback with the EVM address implied by the given key.
     *
     * @param obs the callback to run with the address
     * @return the operation that runs the callback using the address
     */
    public static SpecOperation useAddressOfKey(@NonNull final String key, @NonNull final Consumer<Address> obs) {
        return withOpContext((spec, opLog) -> {
            final var publicKey = fromByteString(spec.registry().getKey(key).getECDSASecp256K1());
            final var address =
                    asHeadlongAddress(recoverAddressFromPubKey(publicKey).toByteArray());
            obs.accept(address);
        });
    }

    /**
     * Returns an operation that computes and executes a {@link SpecOperation} returned by a function whose
     * input is the EVM address implied by the given key.
     *
     * @param opFn the function that computes the resulting operation
     * @return the operation that computes and executes the operation using the address
     */
    public static SpecOperation withAddressOfKey(
            @NonNull final String key, @NonNull final Function<Address, SpecOperation> opFn) {
        return withOpContext((spec, opLog) -> {
            final var publicKey = fromByteString(spec.registry().getKey(key).getECDSASecp256K1());
            final var address =
                    asHeadlongAddress(recoverAddressFromPubKey(publicKey).toByteArray());
            allRunFor(spec, opFn.apply(address));
        });
    }

    /**
     * Returns an operation that computes and executes a {@link SpecOperation} returned by a function whose
     * input is the EVM addresses implied by the given keys.
     *
     * @param opFn the function that computes the resulting operation
     * @return the operation that computes and executes the operation using the addresses
     */
    public static SpecOperation withAddressesOfKeys(
            @NonNull final List<String> keys, @NonNull final Function<List<Address>, SpecOperation> opFn) {
        return withOpContext((spec, opLog) -> allRunFor(
                spec,
                opFn.apply(keys.stream()
                        .map(key -> {
                            final var publicKey =
                                    fromByteString(spec.registry().getKey(key).getECDSASecp256K1());
                            return asHeadlongAddress(
                                    recoverAddressFromPubKey(publicKey).toByteArray());
                        })
                        .toList())));
    }

    /**
     * Returns an operation that computes and executes a of {@link SpecOperation} returned by a function whose
     * input is the long-zero EVM address implied by the given account's id.
     *
     * @param opFn the function that computes the resulting operation
     * @return the operation that computes and executes the operation using the address
     */
    public static SpecOperation withLongZeroAddress(
            @NonNull final String account, @NonNull final Function<Address, SpecOperation> opFn) {
        return withOpContext((spec, opLog) -> {
            final var address = idAsHeadlongAddress(spec.registry().getAccountID(account));
            allRunFor(spec, opFn.apply(address));
        });
    }

    /**
     * Returns an operation that creates the requested number of hollow accounts with names given by the
     * given name function.
     *
     * @param n the number of hollow accounts to create
     * @param nameFn the function that computes the spec registry names for the accounts
     * @return the operation
     */
    public static SpecOperation createHollow(final int n, @NonNull final IntFunction<String> nameFn) {
        return createHollow(n, nameFn, address -> cryptoTransfer(tinyBarsFromTo(GENESIS, address, ONE_HUNDRED_HBARS)));
    }

    /**
     * Returns an operation that creates the requested number of hollow accounts with names given by the
     * given name function, and then executes the given creation function on each account.
     * @param n the number of hollow accounts to create
     * @param nameFn the function that computes the spec registry names for the accounts
     * @param creationFn the function that computes the creation operation for each account
     * @return the operation
     */
    public static SpecOperation createHollow(
            final int n,
            @NonNull final IntFunction<String> nameFn,
            @NonNull final Function<Address, HapiCryptoTransfer> creationFn) {
        requireNonNull(nameFn);
        requireNonNull(creationFn);
        return withOpContext((spec, opLog) -> {
            final List<AccountID> createdIds = new ArrayList<>();
            final List<String> keyNames = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final var keyName = "forHollow" + i;
                keyNames.add(keyName);
                allRunFor(spec, newKeyNamed(keyName).shape(SECP_256K1_SHAPE));
            }
            allRunFor(
                    spec,
                    withAddressesOfKeys(
                            keyNames,
                            addresses -> blockingOrder(addresses.stream()
                                    .map(address -> blockingOrder(
                                            creationFn.apply(address).via("autoCreate" + address),
                                            getTxnRecord("autoCreate" + address)
                                                    .exposingCreationsTo(creations ->
                                                            createdIds.add(asAccount(creations.getFirst())))))
                                    .toArray(SpecOperation[]::new))));
            for (int i = 0; i < n; i++) {
                final var name = nameFn.apply(i);
                spec.registry().saveKey(name, spec.registry().getKey(keyNames.get(i)));
                spec.registry().saveAccountId(name, createdIds.get(i));
            }
        });
    }

    /**
     * Returns an operation that creates the requested number of HIP-32 auto-created accounts using a key alias
     * of the given type, with names given by the given name function and default {@link HapiCryptoTransfer} using
     * the standard transfer of tinybar to a key alias.
     * @param n the number of HIP-32 accounts to create
     * @param keyShape the type of key alias to use
     * @param nameFn the function that computes the spec registry names for the accounts
     * @return the operation
     */
    public static SpecOperation createHip32Auto(
            final int n, @NonNull final KeyShape keyShape, @NonNull final IntFunction<String> nameFn) {
        return createHip32Auto(
                n,
                keyShape,
                nameFn,
                keyName -> cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, keyName, ONE_HUNDRED_HBARS)));
    }

    /**
     * The function that computes the spec registry names of the keys that
     * {@link #createHollow(int, IntFunction, Function)} uses to create the hollow accounts.
     */
    public static final IntFunction<String> AUTO_CREATION_KEY_NAME_FN = i -> "forAutoCreated" + i;

    /**
     * Returns an operation that creates the requested number of HIP-32 auto-created accounts using a key alias
     * of the given type, with names given by the given name function and {@link HapiCryptoTransfer} derived
     * from the given factory.
     * @param n the number of HIP-32 accounts to create
     * @param keyShape the type of key alias to use
     * @param nameFn the function that computes the spec registry names for the accounts
     * @param creationFn the function that computes the creation operation for each account
     * @return the operation
     */
    public static SpecOperation createHip32Auto(
            final int n,
            @NonNull final KeyShape keyShape,
            @NonNull final IntFunction<String> nameFn,
            @NonNull final Function<String, HapiCryptoTransfer> creationFn) {
        requireNonNull(nameFn);
        requireNonNull(keyShape);
        requireNonNull(creationFn);
        return withOpContext((spec, opLog) -> {
            final List<AccountID> createdIds = new ArrayList<>();
            final List<String> keyNames = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final var keyName = AUTO_CREATION_KEY_NAME_FN.apply(i);
                keyNames.add(keyName);
                allRunFor(spec, newKeyNamed(keyName).shape(keyShape));
            }
            allRunFor(
                    spec,
                    blockingOrder(keyNames.stream()
                            .map(keyName -> blockingOrder(
                                    creationFn.apply(keyName).via("hip32" + keyName),
                                    getTxnRecord("hip32" + keyName)
                                            .exposingCreationsTo(
                                                    creations -> createdIds.add(asAccount(creations.getFirst())))))
                            .toArray(SpecOperation[]::new)));
            for (int i = 0; i < n; i++) {
                final var name = nameFn.apply(i);
                spec.registry().saveKey(name, spec.registry().getKey(keyNames.get(i)));
                spec.registry().saveAccountId(name, createdIds.get(i));
            }
        });
    }

    public static HapiSpecOperation overridingTwo(
            final String aProperty, final String aValue, final String bProperty, final String bValue) {
        return overridingAllOf(Map.of(
                aProperty, aValue,
                bProperty, bValue));
    }

    public static HapiSpecOperation overridingThree(
            final String aProperty,
            final String aValue,
            final String bProperty,
            final String bValue,
            final String cProperty,
            final String cValue) {
        return overridingAllOf(Map.of(
                aProperty, aValue,
                bProperty, bValue,
                cProperty, cValue));
    }

    public static HapiSpecOperation overridingAllOf(@NonNull final Map<String, String> explicit) {
        return withOpContext((spec, opLog) -> {
            final var updated121 = getUpdated121(spec, explicit);
            final var multiStepUpdate = updateLargeFile(
                    GENESIS, APP_PROPERTIES, ByteString.copyFrom(updated121), true, OptionalLong.of(0L));
            allRunFor(spec, multiStepUpdate);
        });
    }

    public static HapiSpecOperation remembering(final Map<String, String> props, final String... ofInterest) {
        return remembering(props, Arrays.asList(ofInterest));
    }

    public static HapiSpecOperation remembering(final Map<String, String> props, final List<String> ofInterest) {
        final Predicate<String> filter = new HashSet<>(ofInterest)::contains;
        return blockingOrder(
                getFileContents(APP_PROPERTIES)
                        .payingWith(GENESIS)
                        .nodePayment(ONE_HBAR)
                        .fee(ONE_HBAR)
                        .addingFilteredConfigListTo(props, filter),
                withOpContext((spec, opLog) -> {
                    final var defaultProperties = spec.targetNetworkOrThrow().startupProperties();
                    ofInterest.forEach(prop -> props.computeIfAbsent(prop, defaultProperties::get));
                    allRunFor(spec, logIt("Remembered props: " + props));
                }));
    }

    /* Stream validation. */
    public static EventualRecordStreamAssertion recordStreamMustIncludeNoFailuresFrom(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertion) {
        return EventualRecordStreamAssertion.eventuallyAssertingNoFailures(assertion);
    }

    public static EventualRecordStreamAssertion recordStreamMustIncludePassFrom(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertion) {
        return EventualRecordStreamAssertion.eventuallyAssertingExplicitPass(assertion);
    }

    /**
     * Returns an operation that asserts that the record stream must include a pass from the given assertion
     * before its timeout elapses.
     * @param assertion the assertion to apply to the record stream
     * @param timeout the timeout for the assertion
     * @return the operation that asserts a passing record stream
     */
    public static EventualRecordStreamAssertion recordStreamMustIncludePassFrom(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertion, @NonNull final Duration timeout) {
        requireNonNull(assertion);
        requireNonNull(timeout);
        return EventualRecordStreamAssertion.eventuallyAssertingExplicitPass(assertion, timeout);
    }

    /**
     * Returns an operation that asserts that the block stream must include no failures from the given assertion
     * before its timeout elapses.
     * @param assertion the assertion to apply to the block stream
     * @return the operation that asserts no block stream problems
     */
    public static EventualBlockStreamAssertion blockStreamMustIncludeNoFailuresFrom(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertion) {
        return EventualBlockStreamAssertion.eventuallyAssertingNoFailures(assertion);
    }

    /**
     * Returns an operation that asserts that the block stream must include a pass from the given assertion
     * before its timeout elapses.
     * @param assertion the assertion to apply to the block stream
     * @return the operation that asserts a passing block stream
     */
    public static AbstractEventualStreamAssertion blockStreamMustIncludePassFrom(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertion) {
        return EventualBlockStreamAssertion.eventuallyAssertingExplicitPass(assertion);
    }

    public static RunnableOp verify(@NonNull final Runnable runnable) {
        return new RunnableOp(runnable);
    }

    public static RunnableOp given(@NonNull final Runnable runnable) {
        return new RunnableOp(runnable);
    }

    public static RunnableOp doAdhoc(@NonNull final Runnable runnable) {
        return new RunnableOp(runnable);
    }

    public static HapiSpecOperation[] nOps(final int n, @NonNull final IntFunction<HapiSpecOperation> source) {
        return IntStream.range(0, n).mapToObj(source).toArray(HapiSpecOperation[]::new);
    }

    /**
     * Returns an operation that exposes the consensus time of the current spec to the given observer.
     * @param observer the observer to pass the consensus time to
     * @return the operation that exposes the consensus time
     */
    public static SpecOperation exposeSpecSecondTo(@NonNull final LongConsumer observer) {
        return exposeSpecTimeTo(instant -> observer.accept(instant.getEpochSecond()));
    }

    /**
     * Returns an operation that exposes the consensus time of the current spec to the given observer.
     * @param observer the observer to pass the consensus time to
     * @return the operation that exposes the consensus time
     */
    public static SpecOperation exposeSpecTimeTo(@NonNull final Consumer<Instant> observer) {
        return doingContextual(spec -> observer.accept(spec.consensusTime()));
    }

    /**
     * Returns the given varags as a {@link SpecOperation} array.
     *
     * @param ops the varargs to return as an array
     * @return the array of varargs
     */
    public static SpecOperation[] specOps(@NonNull final SpecOperation... ops) {
        return requireNonNull(ops);
    }

    public static Function<HapiSpec, RecordStreamAssertion> sidecarIdValidator() {
        return spec -> new ValidContractIdsAssertion();
    }

    public static Function<HapiSpec, RecordStreamAssertion> visibleItems(
            @NonNull final VisibleItemsValidator validator, @NonNull final String... specTxnIds) {
        requireNonNull(specTxnIds);
        requireNonNull(validator);
        return spec -> new VisibleItemsAssertion(spec, validator, SkipSynthItems.NO, specTxnIds);
    }

    public static Function<HapiSpec, RecordStreamAssertion> selectedItems(
            @NonNull final VisibleItemsValidator validator,
            final int n,
            @NonNull final BiPredicate<HapiSpec, RecordStreamItem> test) {
        requireNonNull(validator);
        requireNonNull(test);
        return spec -> new SelectedItemsAssertion(n, spec, test, validator);
    }

    public static Function<HapiSpec, RecordStreamAssertion> visibleNonSyntheticItems(
            @NonNull final VisibleItemsValidator validator, @NonNull final String... specTxnIds) {
        requireNonNull(specTxnIds);
        requireNonNull(validator);
        return spec -> new VisibleItemsAssertion(spec, validator, SkipSynthItems.YES, specTxnIds);
    }

    public static Function<HapiSpec, RecordStreamAssertion> recordedChildBodyWithId(
            @NonNull final String specTxnId,
            final int nonce,
            @NonNull final AssertingBiConsumer<HapiSpec, TransactionBody> assertion) {
        requireNonNull(specTxnId);
        requireNonNull(assertion);
        return spec -> new TransactionBodyAssertion(specTxnId, spec, txnId -> txnId.getNonce() == nonce, assertion);
    }

    /* Some more complicated ops built from primitive sub-ops */
    public static CustomSpecAssert recordFeeAmount(String forTxn, String byName) {
        return new CustomSpecAssert((spec, workLog) -> {
            HapiGetTxnRecord subOp = getTxnRecord(forTxn);
            allRunFor(spec, subOp);
            TransactionRecord rcd = subOp.getResponseRecord();
            long fee = rcd.getTransactionFee();
            spec.registry().saveAmount(byName, fee);
        });
    }

    public static HapiSpecOperation fundAnAccount(String account) {
        return withOpContext((spec, ctxLog) -> {
            if (!asId(account, spec).equals(asId(GENESIS, spec))) {
                HapiCryptoTransfer subOp = cryptoTransfer(tinyBarsFromTo(GENESIS, account, HapiSuite.ADEQUATE_FUNDS));
                CustomSpecAssert.allRunFor(spec, subOp);
            }
        });
    }

    public static HapiSpecOperation emptyChildRecordsCheck(String parentTxnId, ResponseCodeEnum parentalStatus) {
        return childRecordsCheck(parentTxnId, parentalStatus);
    }

    public static HapiSpecOperation childRecordsCheck(
            final String parentTxnId,
            final ResponseCodeEnum parentalStatus,
            final TransactionRecordAsserts... childRecordAsserts) {
        return childRecordsCheck(parentTxnId, parentalStatus, parentRecordAsserts -> {}, childRecordAsserts);
    }

    public static HapiSpecOperation childRecordsCheck(
            final String parentTxnId,
            final ResponseCodeEnum parentalStatus,
            final Consumer<TransactionRecordAsserts> parentRecordAssertsSpec,
            final TransactionRecordAsserts... childRecordAsserts) {
        return withOpContext((spec, opLog) -> {
            final var lookup = getTxnRecord(parentTxnId);
            allRunFor(spec, lookup);
            final var parentId = lookup.getResponseRecord().getTransactionID();
            final var parentRecordAsserts = recordWith().status(parentalStatus).txnId(parentId);
            parentRecordAssertsSpec.accept(parentRecordAsserts);
            allRunFor(
                    spec,
                    getTxnRecord(parentTxnId)
                            .andAllChildRecords()
                            .hasPriority(parentRecordAsserts)
                            .hasChildRecords(parentId, childRecordAsserts)
                            .logged());
        });
    }

    public static Setting from(String name, String value) {
        return Setting.newBuilder().setName(name).setValue(value).build();
    }

    public static HapiSpecOperation chunkAFile(String filePath, int chunkSize, String payer, String topic) {
        return chunkAFile(filePath, chunkSize, payer, topic, new AtomicLong(-1));
    }

    public static HapiSpecOperation chunkAFile(
            String filePath, int chunkSize, String payer, String topic, AtomicLong count) {
        return withOpContext((spec, ctxLog) -> {
            List<SpecOperation> opsList = new ArrayList<>();
            String overriddenFile = filePath;
            int overriddenChunkSize = chunkSize;
            String overriddenTopic = topic;
            boolean validateRunningHash = false;

            long currentCount = count.getAndIncrement();
            if (currentCount >= 0) {
                var ciProperties = spec.setup().ciPropertiesMap();
                if (null != ciProperties) {
                    if (ciProperties.has("file")) {
                        overriddenFile = ciProperties.get("file");
                    }
                    if (ciProperties.has("chunkSize")) {
                        overriddenChunkSize = ciProperties.getInteger("chunkSize");
                    }
                    if (ciProperties.has("validateRunningHash")) {
                        validateRunningHash = ciProperties.getBoolean("validateRunningHash");
                    }
                    int threads = PerfTestLoadSettings.DEFAULT_THREADS;
                    if (ciProperties.has("threads")) {
                        threads = ciProperties.getInteger("threads");
                    }
                    int factor = DEFAULT_COLLISION_AVOIDANCE_FACTOR;
                    if (ciProperties.has("collisionAvoidanceFactor")) {
                        factor = ciProperties.getInteger("collisionAvoidanceFactor");
                    }
                    overriddenTopic += currentCount % (threads * factor);
                }
            }
            ByteString msg = ByteString.copyFrom(Files.readAllBytes(Paths.get(overriddenFile)));
            int size = msg.size();
            int totalChunks = (size + overriddenChunkSize - 1) / overriddenChunkSize;
            int position = 0;
            int currentChunk = 0;
            var initialTransactionID = asTransactionID(spec, Optional.of(payer));

            while (position < size) {
                ++currentChunk;
                int newPosition = Math.min(size, position + overriddenChunkSize);
                ByteString subMsg = msg.substring(position, newPosition);
                HapiMessageSubmit subOp = submitMessageTo(overriddenTopic)
                        .message(subMsg)
                        .chunkInfo(totalChunks, currentChunk, initialTransactionID)
                        .payingWith(payer)
                        .hasKnownStatus(SUCCESS)
                        .hasRetryPrecheckFrom(
                                BUSY,
                                DUPLICATE_TRANSACTION,
                                PLATFORM_TRANSACTION_NOT_CREATED,
                                INSUFFICIENT_PAYER_BALANCE)
                        .noLogging();
                if (1 == currentChunk) {
                    subOp = subOp.usePresetTimestamp();
                }
                if (validateRunningHash) {
                    String txnName = "submitMessage-" + overriddenTopic + "-" + currentChunk;
                    HapiGetTxnRecord validateOp = getTxnRecord(txnName)
                            .hasCorrectRunningHash(overriddenTopic, subMsg.toByteArray())
                            .payingWith(payer)
                            .noLogging();
                    opsList.add(subOp.via(txnName));
                    opsList.add(validateOp);
                } else {
                    opsList.add(subOp.deferStatusResolution());
                }
                position = newPosition;
            }

            CustomSpecAssert.allRunFor(spec, opsList);
        });
    }

    public static HapiSpecOperation reduceFeeFor(
            HederaFunctionality function,
            long tinyBarMaxNodeFee,
            long tinyBarMaxNetworkFee,
            long tinyBarMaxServiceFee) {
        return reduceFeeFor(List.of(function), tinyBarMaxNodeFee, tinyBarMaxNetworkFee, tinyBarMaxServiceFee);
    }

    public static HapiSpecOperation reduceFeeFor(
            List<HederaFunctionality> functions,
            long tinyBarMaxNodeFee,
            long tinyBarMaxNetworkFee,
            long tinyBarMaxServiceFee) {
        return withOpContext((spec, opLog) -> {
            var shard = spec.startupProperties().getLong("hedera.shard");
            var realm = spec.startupProperties().getLong("hedera.realm");

            if (!spec.setup().defaultNode().equals(asAccount(String.format("%d.%d.3", shard, realm)))) {
                opLog.info("Sleeping to wait for fee reduction...");
                Thread.sleep(20000);
                return;
            }
            opLog.info("Reducing fee for {}...", functions);
            var query = getFileContents(FEE_SCHEDULE).payingWith(GENESIS);
            allRunFor(spec, query);
            byte[] rawSchedules = query.getResponse()
                    .getFileGetContents()
                    .getFileContents()
                    .getContents()
                    .toByteArray();

            // Convert from tinyBar to one-thousandth of a tinyCent, the unit of max field
            // in FeeComponents
            long centEquiv = spec.ratesProvider().rates().getCentEquiv();
            long hbarEquiv = spec.ratesProvider().rates().getHbarEquiv();
            long maxNodeFee = tinyBarMaxNodeFee * centEquiv * 1000L / hbarEquiv;
            long maxNetworkFee = tinyBarMaxNetworkFee * centEquiv * 1000L / hbarEquiv;
            long maxServiceFee = tinyBarMaxServiceFee * centEquiv * 1000L / hbarEquiv;

            var perturbedSchedules = CurrentAndNextFeeSchedule.parseFrom(rawSchedules).toBuilder();
            for (final var function : functions) {
                reduceFeeComponentsFor(
                        perturbedSchedules.getCurrentFeeScheduleBuilder(),
                        function,
                        maxNodeFee,
                        maxNetworkFee,
                        maxServiceFee);
                reduceFeeComponentsFor(
                        perturbedSchedules.getNextFeeScheduleBuilder(),
                        function,
                        maxNodeFee,
                        maxNetworkFee,
                        maxServiceFee);
            }
            var rawPerturbedSchedules = perturbedSchedules.build().toByteString();
            allRunFor(spec, updateLargeFile(GENESIS, FEE_SCHEDULE, rawPerturbedSchedules));
        });
    }

    private static void reduceFeeComponentsFor(
            FeeSchedule.Builder feeSchedule,
            HederaFunctionality function,
            long maxNodeFee,
            long maxNetworkFee,
            long maxServiceFee) {
        var feesList = feeSchedule.getTransactionFeeScheduleBuilderList().stream()
                .filter(tfs -> tfs.getHederaFunctionality() == function)
                .findAny()
                .orElseThrow()
                .getFeesBuilderList();

        for (FeeData.Builder builder : feesList) {
            builder.getNodedataBuilder().setMax(maxNodeFee);
            builder.getNetworkdataBuilder().setMax(maxNetworkFee);
            builder.getServicedataBuilder().setMax(maxServiceFee);
        }
    }

    public static HapiSpecOperation uploadScheduledContractPrices(@NonNull final String payer) {
        return withOpContext((spec, opLog) -> {
            allRunFor(spec, updateLargeFile(payer, FEE_SCHEDULE, feeSchedulesWith("scheduled-contract-fees.json")));
            if (!spec.tryReinitializingFees()) {
                throw new IllegalStateException("New fee schedules won't be available, dying!");
            }
        });
    }

    private static ByteString feeSchedulesWith(String feeSchedules) {
        SysFileSerde<String> serde = new FeesJsonToGrpcBytes();
        var baos = new ByteArrayOutputStream();
        try {
            var schedulesIn = HapiFileCreate.class.getClassLoader().getResourceAsStream(feeSchedules);
            if (schedulesIn == null) {
                throw new IllegalStateException("No " + feeSchedules + " resource available!");
            }
            schedulesIn.transferTo(baos);
            baos.close();
            baos.flush();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        var stylized = new String(baos.toByteArray());
        return ByteString.copyFrom(serde.toRawFile(stylized, null));
    }

    public static HapiSpecOperation createLargeFile(String payer, String fileName, ByteString byteString) {
        return blockingOrder(
                fileCreate(fileName).payingWith(payer).contents(new byte[0]),
                updateLargeFile(payer, fileName, byteString, false, OptionalLong.empty()));
    }

    public static HapiSpecOperation updateLargeFile(String payer, String fileName, ByteString byteString) {
        return updateLargeFile(payer, fileName, byteString, false, OptionalLong.empty());
    }

    public static HapiSpecOperation updateLargeFile(
            String payer,
            String fileName,
            ByteString byteString,
            boolean signOnlyWithPayer,
            OptionalLong tinyBarsToOffer) {
        return updateLargeFile(
                payer, fileName, byteString, signOnlyWithPayer, tinyBarsToOffer, op -> {}, (op, i) -> {});
    }

    public static HapiSpecOperation updateSpecialFile(
            final String payer,
            final String fileName,
            final Path path,
            final int bytesPerOp,
            final int appendsPerBurst) {
        final ByteString contents;
        try {
            contents = ByteString.copyFrom(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return updateSpecialFile(payer, fileName, contents, bytesPerOp, appendsPerBurst, 0);
    }

    public static HapiSpecOperation updateSpecialFile(
            final String payer,
            final String fileName,
            final ByteString contents,
            final int bytesPerOp,
            final int appendsPerBurst) {
        return updateSpecialFile(payer, fileName, contents, bytesPerOp, appendsPerBurst, 0);
    }

    public static HapiSpecOperation updateSpecialFile(
            final String payer,
            final String fileName,
            final ByteString contents,
            final int bytesPerOp,
            final int appendsPerBurst,
            final int appendsToSkip) {
        return withOpContext((spec, opLog) -> {
            final var bytesToUpload = contents.size();
            final var bytesToAppend = bytesToUpload - Math.min(bytesToUpload, bytesPerOp);
            var appendsRequired = bytesToAppend / bytesPerOp + Math.min(1, bytesToAppend % bytesPerOp);
            final var uploadProgress = new UploadProgress();
            uploadProgress.initializeFor(appendsRequired);

            if (appendsToSkip == 0) {
                System.out.println(
                        ".i. Beginning upload for " + fileName + " (" + appendsRequired + " appends required)");
            } else {
                System.out.println(".i. Continuing upload for "
                        + fileName
                        + " with "
                        + appendsToSkip
                        + " appends already finished (out of "
                        + appendsRequired
                        + " appends required)");
            }
            final var numBursts = (appendsRequired - appendsToSkip) / appendsPerBurst
                    + Math.min(1, appendsRequired % appendsPerBurst);

            int position =
                    (appendsToSkip == 0) ? Math.min(bytesPerOp, bytesToUpload) : bytesPerOp * (1 + appendsToSkip);
            if (appendsToSkip == 0) {
                final var updateSubOp = fileUpdate(fileName)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(contents.substring(0, position))
                        .alertingPre(fid -> System.out.println(".i. Submitting initial update for file"
                                + String.format(" %s.%s.%s, ", fid.getShardNum(), fid.getRealmNum(), fid.getFileNum())))
                        .alertingPost(code -> System.out.println(".i. Finished initial update with " + code))
                        .noLogging()
                        .payingWith(payer)
                        .signedBy(payer);
                allRunFor(spec, updateSubOp);
            }

            try {
                finishAppendsFor(
                        contents,
                        position,
                        bytesPerOp,
                        appendsPerBurst,
                        numBursts,
                        fileName,
                        payer,
                        spec,
                        uploadProgress,
                        appendsToSkip,
                        opLog);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                final var finished = uploadProgress.finishedAppendPrefixLength();
                if (finished != -1) {
                    log.error(
                            "Upload failed, but at least {} appends appear to have"
                                    + " finished; please re-run with --restart-from-failure",
                            finished,
                            e);
                } else {
                    log.error("Upload failed without any reusable work; please try again", e);
                }
                throw new IllegalStateException(e);
            }
        });
    }

    private static void finishAppendsFor(
            final ByteString contents,
            int position,
            final int bytesPerOp,
            final int appendsPerBurst,
            final int numBursts,
            final String fileName,
            final String payer,
            final HapiSpec spec,
            final UploadProgress uploadProgress,
            final int appendsSkipped,
            final Logger opLog)
            throws InterruptedException {
        final var bytesToUpload = contents.size();
        final AtomicInteger burstNo = new AtomicInteger(1);
        final AtomicInteger nextAppendNo = new AtomicInteger(appendsSkipped);
        while (position < bytesToUpload) {
            final var totalBytesLeft = bytesToUpload - position;
            final var appendsLeft = totalBytesLeft / bytesPerOp + Math.min(1, totalBytesLeft % bytesPerOp);
            final var appendsHere = new AtomicInteger(Math.min(appendsPerBurst, appendsLeft));
            boolean isFirstAppend = true;
            final List<SpecOperation> theBurst = new ArrayList<>();
            final CountDownLatch burstLatch = new CountDownLatch(1);
            final AtomicReference<Instant> burstStart = new AtomicReference<>();
            while (appendsHere.getAndDecrement() > 0) {
                final var bytesLeft = bytesToUpload - position;
                final var bytesThisAppend = Math.min(bytesLeft, bytesPerOp);
                final var newPosition = position + bytesThisAppend;
                final var appendNoToTrack = nextAppendNo.getAndIncrement();
                opLog.info("Constructing append #{} ({} bytes)", appendNoToTrack, bytesThisAppend);
                final var appendSubOp = fileAppend(fileName)
                        .content(contents.substring(position, newPosition).toByteArray())
                        .fee(ONE_HUNDRED_HBARS)
                        .noLogging()
                        .payingWith(payer)
                        .signedBy(payer)
                        .deferStatusResolution()
                        .trackingProgressIn(uploadProgress, appendNoToTrack);
                if (isFirstAppend) {
                    final var fixedBurstNo = burstNo.get();
                    final var fixedAppendsHere = appendsHere.get() + 1;
                    appendSubOp.alertingPre(fid -> {
                        burstStart.set(Instant.now());
                        System.out.println(".i. Starting burst " + fixedBurstNo + "/" + numBursts + " ("
                                + fixedAppendsHere + " ops)");
                    });
                    isFirstAppend = false;
                }
                if (appendsHere.get() == 0) {
                    final var fixedBurstNo = burstNo.get();
                    appendSubOp.alertingPost(code -> {
                        final var burstSecs = Duration.between(burstStart.get(), Instant.now())
                                .getSeconds();
                        System.out.println(".i. Completed burst #"
                                + fixedBurstNo
                                + "/"
                                + numBursts
                                + " in "
                                + burstSecs
                                + "s with "
                                + code);
                        burstLatch.countDown();
                    });
                }
                theBurst.add(appendSubOp);
                position = newPosition;
            }
            allRunFor(spec, theBurst);
            burstLatch.await();
            burstNo.getAndIncrement();
        }
    }

    public static HapiSpecOperation updateLargeFile(
            String payer,
            String fileName,
            ByteString byteString,
            boolean signOnlyWithPayer,
            OptionalLong tinyBarsToOffer,
            Consumer<HapiFileUpdate> updateCustomizer,
            ObjIntConsumer<HapiFileAppend> appendCustomizer) {
        return withOpContext((spec, ctxLog) -> {
            List<SpecOperation> opsList = new ArrayList<>();

            int fileSize = byteString.size();
            int position = Math.min(BYTES_4K, fileSize);

            HapiFileUpdate updateSubOp = fileUpdate(fileName)
                    .contents(byteString.substring(0, position))
                    .hasKnownStatusFrom(
                            SUCCESS, FEE_SCHEDULE_FILE_PART_UPLOADED, SUCCESS_BUT_MISSING_EXPECTED_OPERATION)
                    .noLogging()
                    .payingWith(payer);
            updateCustomizer.accept(updateSubOp);
            if (tinyBarsToOffer.isPresent()) {
                updateSubOp = updateSubOp.fee(tinyBarsToOffer.getAsLong());
            }
            if (signOnlyWithPayer) {
                updateSubOp = updateSubOp.signedBy(payer);
            }
            opsList.add(updateSubOp);

            final int bytesLeft = fileSize - position;
            final int totalAppendsRequired = bytesLeft / BYTES_4K + Math.min(1, bytesLeft % BYTES_4K);
            int numAppends = 0;
            while (position < fileSize) {
                int newPosition = Math.min(fileSize, position + BYTES_4K);
                var appendSubOp = fileAppend(fileName)
                        .content(byteString.substring(position, newPosition).toByteArray())
                        .hasKnownStatusFrom(SUCCESS, FEE_SCHEDULE_FILE_PART_UPLOADED)
                        .noLogging()
                        .payingWith(payer);
                appendCustomizer.accept(appendSubOp, totalAppendsRequired - numAppends);
                if (tinyBarsToOffer.isPresent()) {
                    appendSubOp = appendSubOp.fee(tinyBarsToOffer.getAsLong());
                }
                if (signOnlyWithPayer) {
                    appendSubOp = appendSubOp.signedBy(payer);
                }
                opsList.add(appendSubOp);
                position = newPosition;
                numAppends++;
            }

            CustomSpecAssert.allRunFor(spec, opsList);
        });
    }

    public static HapiSpecOperation updateLargeFile(String payer, String fileName, String registryEntry) {
        return withOpContext((spec, ctxLog) -> {
            ByteString bt = ByteString.copyFrom(spec.registry().getBytes(registryEntry));
            CustomSpecAssert.allRunFor(spec, updateLargeFile(payer, fileName, bt));
        });
    }

    /**
     * Returns a {@link CustomSpecAssert} that asserts that the provided contract creation has the
     * expected maxAutoAssociations value.
     *
     * @param txn the contract create transaction which resulted in contract creation.
     * @param creationNum the index of the contract creation in the transaction. If we have nested contract create, the top-level contract creation is at index 0.
     * @param maxAutoAssociations the expected maxAutoAssociations value.
     * @return a {@link CustomSpecAssert}
     */
    public static CustomSpecAssert assertCreationMaxAssociations(
            final String txn, final int creationNum, final int maxAutoAssociations) {
        return assertCreationMaxAssociationsCommon(
                txn, creationNum, maxAutoAssociations, TransactionRecord::getContractCreateResult);
    }

    /**
     * Returns a {@link CustomSpecAssert} that asserts that the provided contract creation has the
     * expected maxAutoAssociations value.
     *
     * @param txn the contract call transaction which resulted in contract creation.
     * @param creationNum the index of the contract creation in the transaction.
     * @param maxAutoAssociations the expected maxAutoAssociations value.
     * @return a {@link CustomSpecAssert}
     */
    public static CustomSpecAssert assertCreationViaCallMaxAssociations(
            final String txn, final int creationNum, final int maxAutoAssociations) {
        return assertCreationMaxAssociationsCommon(
                txn, creationNum, maxAutoAssociations, TransactionRecord::getContractCallResult);
    }

    private static CustomSpecAssert assertCreationMaxAssociationsCommon(
            final String txn,
            final int creationNum,
            final int maxAutoAssociations,
            final Function<TransactionRecord, ContractFunctionResult> resultExtractor) {
        return assertionsHold((spec, opLog) -> {
            final var op = getTxnRecord(txn);
            allRunFor(spec, op);
            final var creationResult = resultExtractor.apply(op.getResponseRecord());
            final var createdIds = creationResult.getCreatedContractIDsList().stream()
                    .sorted(Comparator.comparing(ContractID::getContractNum))
                    .toList();
            final var createdId = createdIds.get(creationNum);
            final var accDetails = getContractInfo(CommonUtils.hex(
                            asEvmAddress(createdId.getShardNum(), createdId.getRealmNum(), createdId.getContractNum())))
                    .logged();
            allRunFor(spec, accDetails);
        });
    }

    @SuppressWarnings("java:S5960")
    public static HapiSpecOperation contractListWithPropertiesInheritedFrom(
            final String contractList, final long expectedSize, final String parent) {
        return withOpContext((spec, ctxLog) -> {
            List<SpecOperation> opsList = new ArrayList<>();
            long contractListSize = spec.registry().getAmount(contractList + "Size");
            Assertions.assertEquals(expectedSize, contractListSize, contractList + " has bad size!");
            if (contractListSize > 1) {
                ContractID currentID = spec.registry().getContractId(contractList + "0");
                long nextIndex = 1;
                while (nextIndex < contractListSize) {
                    ContractID nextID = spec.registry().getContractId(contractList + nextIndex);
                    Assertions.assertEquals(currentID.getShardNum(), nextID.getShardNum());
                    Assertions.assertEquals(currentID.getRealmNum(), nextID.getRealmNum());
                    assertTrue(currentID.getContractNum() < nextID.getContractNum());
                    currentID = nextID;
                    nextIndex++;
                }
            }
            for (long i = 0; i < contractListSize; i++) {
                HapiSpecOperation op = getContractInfo(contractList + i)
                        .has(contractWith().propertiesInheritedFrom(parent))
                        .logged();
                opsList.add(op);
            }
            CustomSpecAssert.allRunFor(spec, opsList);
        });
    }

    /**
     * Validates that fee charged for a transaction is within +/- 0.0001$ of expected fee (taken
     * from pricing calculator)
     *
     * @param txn transaction to be validated
     * @param expectedUsd expected fee in USD
     * @return assertion for the validation
     */
    public static CustomSpecAssert validateChargedUsd(String txn, double expectedUsd) {
        return validateChargedUsdWithin(txn, expectedUsd, 1.0);
    }

    public static CustomSpecAssert validateChargedUsd(String txn, double expectedUsd, double allowedPercentDiff) {
        return validateChargedUsdWithin(txn, expectedUsd, allowedPercentDiff);
    }

    public static CustomSpecAssert validateChargedUsdWithChild(
            String txn, double expectedUsd, double allowedPercentDiff) {
        return assertionsHold((spec, assertLog) -> {
            final var actualUsdCharged = getChargedUsdFromChild(spec, txn);
            assertEquals(
                    expectedUsd,
                    actualUsdCharged,
                    (allowedPercentDiff / 100.0) * expectedUsd,
                    String.format(
                            "%s fee (%s) more than %.2f percent different than expected!",
                            sdec(actualUsdCharged, 4), txn, allowedPercentDiff));
        });
    }

    public static CustomSpecAssert validateChargedUsdWithin(String txn, double expectedUsd, double allowedPercentDiff) {
        return assertionsHold((spec, assertLog) -> {
            final var actualUsdCharged = getChargedUsed(spec, txn);
            assertEquals(
                    expectedUsd,
                    actualUsdCharged,
                    (allowedPercentDiff / 100.0) * expectedUsd,
                    String.format(
                            "%s fee (%s) more than %.2f percent different than expected!",
                            sdec(actualUsdCharged, 4), txn, allowedPercentDiff));
        });
    }

    public static CustomSpecAssert validateInnerTxnChargedUsd(
            String txn, String parent, double expectedUsd, double allowedPercentDiff) {
        return assertionsHold((spec, assertLog) -> {
            final var actualUsdCharged = getChargedUsedForInnerTxn(spec, parent, txn);
            assertEquals(
                    expectedUsd,
                    actualUsdCharged,
                    (allowedPercentDiff / 100.0) * expectedUsd,
                    String.format(
                            "%s fee (%s) more than %.2f percent different than expected!",
                            sdec(actualUsdCharged, 4), txn, allowedPercentDiff));
        });
    }

    /**
     * Validates that fee charged for a transaction is within the allowedPercentDiff of expected fee (taken
     * from pricing calculator) without the charge for gas.
     * @param txn txn to be validated
     * @param expectedUsd expected fee in usd
     * @param allowedPercentDiff allowed percentage difference
     * @return
     */
    public static CustomSpecAssert validateChargedUsdWithoutGas(
            String txn, double expectedUsd, double allowedPercentDiff) {
        return assertionsHold((spec, assertLog) -> {
            final var actualUsdCharged = getChargedUsed(spec, txn);
            final var gasCharged = getChargedGas(spec, txn);
            assertEquals(
                    expectedUsd,
                    actualUsdCharged - gasCharged,
                    (allowedPercentDiff / 100.0) * expectedUsd,
                    String.format(
                            "%s fee without gas (%s) more than %.2f percent different than expected!",
                            sdec(actualUsdCharged - gasCharged, 4), txn, allowedPercentDiff));
        });
    }

    /**
     * Validates that the gas charge for a transaction is within the allowedPercentDiff of expected gas in USD.
     * @param txn txn to be validated
     * @param expectedUsdForGas expected gas charge in usd
     * @param allowedPercentDiff allowed percentage difference
     * @return
     */
    public static CustomSpecAssert validateChargedUsdForGasOnly(
            String txn, double expectedUsdForGas, double allowedPercentDiff) {
        return assertionsHold((spec, assertLog) -> {
            final var gasCharged = getChargedGas(spec, txn);
            assertEquals(
                    expectedUsdForGas,
                    gasCharged,
                    (allowedPercentDiff / 100.0) * expectedUsdForGas,
                    String.format(
                            "%s gas charge (%s) more than %.2f percent different than expected!",
                            sdec(expectedUsdForGas, 4), txn, allowedPercentDiff));
        });
    }

    /**
     * Validates that an amount is within a certain percentage of an expected value.
     * @param expected expected value
     * @param actual actual value
     * @param allowedPercentDiff allowed percentage difference
     * @param quantity quantity being compared
     * @param context context of the comparison
     */
    public static void assertCloseEnough(
            final double expected,
            final double actual,
            final double allowedPercentDiff,
            final String quantity,
            final String context) {
        assertEquals(
                expected,
                actual,
                (allowedPercentDiff / 100.0) * expected,
                String.format(
                        "%s %s (%s) more than %.2f percent different than expected",
                        sdec(actual, 4), quantity, context, allowedPercentDiff));
    }

    public static CustomSpecAssert validateChargedUsdExceeds(String txn, double amount) {
        return validateChargedUsd(txn, actualUsdCharged -> {
            assertTrue(
                    actualUsdCharged > amount,
                    String.format("%s fee (%s) is not greater than %s!", sdec(actualUsdCharged, 4), txn, amount));
        });
    }

    public static CustomSpecAssert validateChargedUsd(String txn, DoubleConsumer validator) {
        return assertionsHold((spec, assertLog) -> {
            final var actualUsdCharged = getChargedUsed(spec, txn);
            validator.accept(actualUsdCharged);
        });
    }

    public static CustomSpecAssert getTransactionFee(String txn, StringBuilder feeTableBuilder, String operation) {
        return assertionsHold((spec, asertLog) -> {
            var subOp = getTxnRecord(txn);
            allRunFor(spec, subOp);

            var rcd = subOp.getResponseRecord();
            double actualUsdCharged = (1.0 * rcd.getTransactionFee())
                    / ONE_HBAR
                    / rcd.getReceipt().getExchangeRate().getCurrentRate().getHbarEquiv()
                    * rcd.getReceipt().getExchangeRate().getCurrentRate().getCentEquiv()
                    / 100;

            feeTableBuilder.append(String.format("%30s | %1.5f \t |%n", operation, actualUsdCharged));
        });
    }

    public static SpecOperation[] takeBalanceSnapshots(String... entities) {
        return HapiSuite.flattened(
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, 1_000_000_000L))
                        .noLogging(),
                Stream.of(entities)
                        .map(account -> balanceSnapshot(
                                        spec -> asAccountString(spec.registry().getAccountID(account)) + "Snapshot",
                                        account)
                                .payingWith(EXCHANGE_RATE_CONTROL))
                        .toArray(n -> new SpecOperation[n]));
    }

    public static HapiSpecOperation validateRecordTransactionFees(HapiSpec spec, String txn) {
        var shard = spec.startupProperties().getLong("hedera.shard");
        var realm = spec.startupProperties().getLong("hedera.realm");
        var fundingAccount = spec.startupProperties().getLong("ledger.fundingAccount");
        var stakingRewardAccount = spec.startupProperties().getLong("accounts.stakingRewardAccount");
        var nodeRewardAccount = spec.startupProperties().getLong("accounts.nodeRewardAccount");

        return validateRecordTransactionFees(
                txn,
                Set.of(
                        asAccount(String.format("%s.%s.3", shard, realm)),
                        asAccount(String.format("%s.%s.%s", shard, realm, fundingAccount)),
                        asAccount(String.format("%s.%s.%s", shard, realm, stakingRewardAccount)),
                        asAccount(String.format("%s.%s.%s", shard, realm, nodeRewardAccount))));
    }

    /**
     * Returns an operation that writes the requested contents to the working directory of each node.
     * @param contents the contents to write
     * @param segments the path segments to the file relative to the node working directory
     * @return the operation
     * @throws NullPointerException if the target network is not local, hence working directories are null
     */
    public static SpecOperation writeToNodeWorkingDirs(
            @NonNull final String contents, @NonNull final String... segments) {
        requireNonNull(segments);
        requireNonNull(contents);
        return withOpContext((spec, opLog) -> {
            spec.getNetworkNodes().forEach(node -> {
                var path = node.metadata().workingDirOrThrow();
                for (int i = 0; i < segments.length - 1; i++) {
                    path = path.resolve(segments[i]);
                }
                ensureDir(path.toString());
                try {
                    Files.writeString(path.resolve(segments[segments.length - 1]), contents);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        });
    }

    /**
     * Returns an operation that validates the node payment returned by the {@link ResponseType#COST_ANSWER}
     * version of the query returned by the given factory is <b>exact</b> in the sense that offering even
     * 1 tinybar less as node payment results in a {@link ResponseCodeEnum#INSUFFICIENT_TX_FEE} precheck.
     *
     * @param queryOp the query operation factory
     * @return the cost validation operation
     */
    public static HapiSpecOperation withStrictCostAnswerValidation(@NonNull final Supplier<HapiQueryOp<?>> queryOp) {
        final var requiredNodePayment = new AtomicLong();
        return blockingOrder(
                sourcing(() -> queryOp.get().exposingNodePaymentTo(requiredNodePayment::set)),
                sourcing(() -> queryOp.get()
                        .nodePayment(requiredNodePayment.get() - 1)
                        .hasAnswerOnlyPrecheck(INSUFFICIENT_TX_FEE)));
    }

    public static HapiSpecOperation validateRecordTransactionFees(String txn, Set<AccountID> feeRecipients) {
        return assertionsHold((spec, assertLog) -> {
            final AtomicReference<TransactionRecord> txnRecord = new AtomicReference<>();
            allRunFor(spec, withStrictCostAnswerValidation(() -> getTxnRecord(txn)
                    .payingWith(EXCHANGE_RATE_CONTROL)
                    .exposingTo(txnRecord::set)
                    .logged()));
            final var rcd = txnRecord.get();
            long realFee = rcd.getTransferList().getAccountAmountsList().stream()
                    .filter(aa -> feeRecipients.contains(aa.getAccountID()))
                    .mapToLong(AccountAmount::getAmount)
                    .sum();
            Assertions.assertEquals(realFee, rcd.getTransactionFee(), "Inconsistent transactionFee field!");
        });
    }

    public static HapiSpecOperation validateTransferListForBalances(String txn, List<String> accounts) {
        return validateTransferListForBalances(List.of(txn), accounts);
    }

    public static HapiSpecOperation validateTransferListForBalances(
            String txn, List<String> accounts, Set<String> wereDeleted) {
        return validateTransferListForBalances(List.of(txn), accounts, wereDeleted);
    }

    public static HapiSpecOperation validateTransferListForBalances(List<String> txns, List<String> accounts) {
        return validateTransferListForBalances(txns, accounts, Collections.emptySet());
    }

    public static HapiSpecOperation validateTransferListForBalances(
            List<String> txns, List<String> accounts, Set<String> wereDeleted) {
        return assertionsHold((spec, assertLog) -> {
            Map<String, Long> actualBalances = accounts.stream()
                    .collect(Collectors.toMap(
                            (String account) -> asAccountString(spec.registry().getAccountID(account)),
                            (String account) -> {
                                if (wereDeleted.contains(account)) {
                                    return 0L;
                                }
                                long balance = -1L;
                                try {
                                    BalanceSnapshot preOp = balanceSnapshot("x", account);
                                    allRunFor(spec, preOp);
                                    balance = spec.registry().getBalanceSnapshot("x");
                                } catch (Exception ignore) {
                                    // Intentionally ignored
                                }
                                return balance;
                            }));

            List<AccountAmount> transfers = new ArrayList<>();

            for (String txn : txns) {
                HapiGetTxnRecord subOp = getTxnRecord(txn).logged().payingWith(EXCHANGE_RATE_CONTROL);
                allRunFor(spec, subOp);
                TransactionRecord rcd =
                        subOp.getResponse().getTransactionGetRecord().getTransactionRecord();
                transfers.addAll(rcd.getTransferList().getAccountAmountsList());
            }

            Map<String, Long> changes = changesAccordingTo(transfers);
            assertLog.info("Balance changes according to transfer list: {}", changes);
            changes.entrySet().forEach(change -> {
                String account = change.getKey();
                long oldBalance = -1L;
                /* The account/contract may have just been created, no snapshot was taken. */
                try {
                    oldBalance = spec.registry().getBalanceSnapshot(account + "Snapshot");
                } catch (Exception ignored) {
                    // Intentionally ignored
                }
                long expectedBalance = change.getValue() + Math.max(0L, oldBalance);
                long actualBalance = actualBalances.getOrDefault(account, -1L);
                assertLog.info(
                        "Balance of {} was expected to be {}, is actually" + " {}...",
                        account,
                        expectedBalance,
                        actualBalance);
                Assertions.assertEquals(
                        expectedBalance,
                        actualBalance,
                        "New balance for " + account + " should be " + expectedBalance + " tinyBars.");
            });
        });
    }

    private static Map<String, Long> changesAccordingTo(List<AccountAmount> transfers) {
        return transfers.stream()
                .map(aa -> new AbstractMap.SimpleEntry<>(asAccountString(aa.getAccountID()), aa.getAmount()))
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.summingLong(Map.Entry::getValue)));
    }

    public static Tuple[] wrapIntoTupleArray(Tuple tuple) {
        return new Tuple[] {tuple};
    }

    public static TransferListBuilder transferList() {
        return new TransferListBuilder();
    }

    /**
     * Returns an operation that attempts to execute the given transaction passing the
     * provided name to {@link HapiTxnOp#via(String)}; and accepting either
     * {@link com.hedera.hapi.node.base.ResponseCodeEnum#SUCCESS} or
     * {@link com.hedera.hapi.node.base.ResponseCodeEnum#MAX_CHILD_RECORDS_EXCEEDED}
     * as the final status.
     *
     * <p>On success, executes the remaining operations. This lets us stabilize operations
     * in CI that need to use all preceding child records to succeed; and hence fail if
     * their transaction triggers an end-of-day staking record.
     *
     * @param txnRequiringMaxChildRecords the transaction requiring all child records
     * @param name the transaction name to use
     * @param onSuccess the operations to run on success
     * @return the operation doing this conditional execution
     */
    public static HapiSpecOperation assumingNoStakingChildRecordCausesMaxChildRecordsExceeded(
            @NonNull final HapiTxnOp<?> txnRequiringMaxChildRecords,
            @NonNull final String name,
            @NonNull final HapiSpecOperation... onSuccess) {
        return blockingOrder(
                txnRequiringMaxChildRecords
                        .via(name)
                        // In CI this could fail due to an end-of-staking period record already
                        // being added as a child to this transaction before its auto-creations
                        .hasKnownStatusFrom(SUCCESS, MAX_CHILD_RECORDS_EXCEEDED),
                withOpContext((spec, opLog) -> {
                    final var lookup = getTxnRecord(name);
                    allRunFor(spec, lookup);
                    final var actualStatus =
                            lookup.getResponseRecord().getReceipt().getStatus();
                    // Continue with more assertions given the normal case the preceding transfer succeeded
                    if (actualStatus == SUCCESS) {
                        allRunFor(spec, onSuccess);
                    }
                }));
    }

    /**
     * Asserts that a scheduled execution is as expected.
     */
    public interface ScheduledExecutionAssertion {
        /**
         * Tests that a scheduled execution body and result are as expected within the given spec.
         * @param spec the context in which the assertion is being made
         * @param body the transaction body of the scheduled execution
         * @param result the transaction result of the scheduled execution
         * @throws AssertionError if the assertion fails
         */
        void test(
                @NonNull HapiSpec spec,
                @NonNull com.hedera.hapi.node.transaction.TransactionBody body,
                @NonNull TransactionResult result);
    }

    /**
     * Returns a {@link ScheduledExecutionAssertion} that asserts the status of the execution result
     * is as expected; and that the record of the scheduled execution is queryable, again with the expected status.
     * @param status the expected status
     * @return the assertion
     */
    public static ScheduledExecutionAssertion withStatus(
            @NonNull final com.hedera.hapi.node.base.ResponseCodeEnum status) {
        requireNonNull(status);
        return (spec, body, result) -> {
            assertEquals(status, result.status());
            allRunFor(spec, getTxnRecord(body.transactionIDOrThrow()).assertingNothingAboutHashes());
        };
    }

    /**
     * Returns a {@link ScheduledExecutionAssertion} that asserts the status of the execution result
     * is as expected; and that a query for its record, customized by the given spec, passes.
     * @return the assertion
     */
    public static ScheduledExecutionAssertion withRecordSpec(@NonNull final Consumer<HapiGetTxnRecord> querySpec) {
        requireNonNull(querySpec);
        return (spec, body, result) -> {
            final var op = getTxnRecord(body.transactionIDOrThrow()).assertingNothingAboutHashes();
            querySpec.accept(op);
            try {
                allRunFor(spec, op);
            } catch (Exception e) {
                Assertions.fail(Optional.ofNullable(e.getCause()).orElse(e).getMessage());
            }
        };
    }

    /**
     * Returns a {@link BlockStreamAssertion} factory that asserts the result of a scheduled execution
     * of the given named transaction passes the given assertion.
     * @param creationTxn the name of the transaction that created the scheduled execution
     * @param assertion the assertion to apply to the scheduled execution
     * @return a factory for a {@link BlockStreamAssertion} that asserts the result of the scheduled execution
     */
    public static Function<HapiSpec, BlockStreamAssertion> scheduledExecutionResult(
            @NonNull final String creationTxn, @NonNull final ScheduledExecutionAssertion assertion) {
        requireNonNull(creationTxn);
        requireNonNull(assertion);
        return spec -> block -> {
            final com.hederahashgraph.api.proto.java.TransactionID creationTxnId;
            try {
                creationTxnId = spec.registry().getTxnId(creationTxn);
            } catch (RegistryNotFound ignore) {
                return false;
            }
            final var executionTxnId =
                    protoToPbj(creationTxnId.toBuilder().setScheduled(true).build(), TransactionID.class);
            final var items = block.items();
            for (int i = 0, n = items.size(); i < n; i++) {
                final var item = items.get(i);
                if (item.hasEventTransaction()) {
                    final var parts =
                            TransactionParts.from(item.eventTransactionOrThrow().applicationTransactionOrThrow());
                    if (parts.transactionIdOrThrow().equals(executionTxnId)) {
                        for (int j = i + 1; j < n; j++) {
                            final var followingItem = items.get(j);
                            if (followingItem.hasTransactionResult()) {
                                assertion.test(spec, parts.body(), followingItem.transactionResultOrThrow());
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        };
    }

    public static class TransferListBuilder {
        private Tuple transferList;

        public TransferListBuilder withAccountAmounts(final Tuple... accountAmounts) {
            this.transferList = Tuple.singleton(accountAmounts);
            return this;
        }

        public Tuple build() {
            return transferList;
        }
    }

    public static TokenTransferListBuilder tokenTransferList() {
        return new TokenTransferListBuilder();
    }

    public static TokenTransferListsBuilder tokenTransferLists() {
        return new TokenTransferListsBuilder();
    }

    public static class TokenTransferListBuilder {
        private Tuple tokenTransferList;
        private Address token;

        public TokenTransferListBuilder forToken(final TokenID token) {
            this.token = HapiParserUtil.asHeadlongAddress(asAddress(token));
            return this;
        }

        public TokenTransferListBuilder withAccountAmounts(final Tuple... accountAmounts) {
            this.tokenTransferList = Tuple.of(token, accountAmounts, new Tuple[] {});
            return this;
        }

        public TokenTransferListBuilder withNftTransfers(final Tuple... nftTransfers) {
            this.tokenTransferList = Tuple.of(token, new Tuple[] {}, nftTransfers);
            return this;
        }

        public Tuple build() {
            return tokenTransferList;
        }
    }

    public static class TokenTransferListsBuilder {
        private Tuple[] tokenTransferLists;

        public TokenTransferListsBuilder withTokenTransferList(final Tuple... tokenTransferLists) {
            this.tokenTransferLists = tokenTransferLists;
            return this;
        }

        public Object build() {
            return tokenTransferLists;
        }
    }

    public static Tuple accountAmount(final AccountID account, final Long amount) {
        return Tuple.of(HapiParserUtil.asHeadlongAddress(asAddress(account)), amount);
    }

    public static Tuple accountAmount(final AccountID account, final Long amount, final boolean isApproval) {
        return Tuple.of(HapiParserUtil.asHeadlongAddress(asAddress(account)), amount, isApproval);
    }

    public static Tuple accountAmountAlias(final byte[] alias, final Long amount) {
        return Tuple.of(HapiParserUtil.asHeadlongAddress(alias), amount);
    }

    public static Tuple accountAmountAlias(final byte[] alias, final Long amount, final boolean isApproval) {
        return Tuple.of(HapiParserUtil.asHeadlongAddress(alias), amount, isApproval);
    }

    public static Tuple nftTransfer(final AccountID sender, final AccountID receiver, final Long serialNumber) {

        return Tuple.of(
                HapiParserUtil.asHeadlongAddress(asAddress(sender)),
                HapiParserUtil.asHeadlongAddress(asAddress(receiver)),
                serialNumber);
    }

    public static Tuple nftTransfer(
            final AccountID sender, final AccountID receiver, final Long serialNumber, final boolean isApproval) {
        return Tuple.of(
                HapiParserUtil.asHeadlongAddress(asAddress(sender)),
                HapiParserUtil.asHeadlongAddress(asAddress(receiver)),
                serialNumber,
                isApproval);
    }

    public static List<SpecOperation> convertHapiCallsToEthereumCalls(
            final List<SpecOperation> ops,
            final String privateKeyRef,
            final Key adminKey,
            final long defaultGas,
            final HapiSpec spec) {
        final var convertedOps = new ArrayList<SpecOperation>(ops.size());
        for (final var op : ops) {
            if (op instanceof HapiContractCall callOp
                    && callOp.isConvertableToEthCall()
                    && callOp.isKeySECP256K1(spec)) {
                // if we have function params, try to swap the long zero address with the EVM address
                if (callOp.getParams().length > 0 && callOp.getAbi() != null) {
                    var convertedParams = tryToSwapLongZeroToEVMAddresses(callOp.getParams(), spec);
                    callOp.setParams(convertedParams);
                }
                convertedOps.add(new HapiEthereumCall(callOp));

            } else if (op instanceof HapiContractCreate callOp && callOp.isConvertableToEthCreate()) {
                // if we have constructor args, update the bytecode file with one containing the args
                if (callOp.getArgs().isPresent() && callOp.getAbi().isPresent()) {
                    var convertedArgs =
                            tryToSwapLongZeroToEVMAddresses(callOp.getArgs().get(), spec);
                    callOp.args(Optional.of(convertedArgs));
                    convertedOps.add(updateInitCodeWithConstructorArgs(
                            Optional.empty(),
                            callOp.getContract(),
                            callOp.getAbi().get(),
                            callOp.getArgs().get()));
                }

                var createEthereum = withOpContext((spec1, logger) -> {
                    var createTxn = new HapiEthereumContractCreate(callOp, privateKeyRef, adminKey, defaultGas);
                    allRunFor(spec1, createTxn);
                    // if create was successful, save the EVM address to the registry, so we can use it in future calls
                    if (spec1.registry().hasContractId(callOp.getContract())) {
                        allRunFor(
                                spec1,
                                getContractInfo(callOp.getContract()).saveEVMAddressToRegistry(callOp.getContract()));
                    }
                });
                convertedOps.add(createEthereum);

            } else {
                convertedOps.add(op);
            }
        }
        return convertedOps;
    }

    private static Object[] tryToSwapLongZeroToEVMAddresses(Object[] args, HapiSpec spec) {
        return Arrays.stream(args)
                .map(arg -> {
                    if (arg instanceof Address address) {
                        return swapLongZeroToEVMAddresses(spec, arg, address);
                    }
                    return arg;
                })
                .toArray();
    }

    private static Object swapLongZeroToEVMAddresses(HapiSpec spec, Object arg, Address address) {
        if (isLongZeroAddress(
                spec.setup().defaultShard().getShardNum(),
                spec.setup().defaultRealm().getRealmNum(),
                explicitFromHeadlong(address))) {
            var contractNum = numberOfLongZero(explicitFromHeadlong(address));
            if (spec.registry().hasEVMAddress(String.valueOf(contractNum))) {
                return HapiParserUtil.asHeadlongAddress(spec.registry().getEVMAddress(String.valueOf(contractNum)));
            }
        }
        return arg;
    }

    public static byte[] getEcdsaPrivateKeyFromSpec(final HapiSpec spec, final String privateKeyRef) {
        var key = spec.registry().getKey(privateKeyRef);
        final var privateKey = spec.keys()
                .getEcdsaPrivateKey(CommonUtils.hex(key.getECDSASecp256K1().toByteArray()));

        byte[] privateKeyByteArray;
        byte[] dByteArray = ((BCECPrivateKey) privateKey).getD().toByteArray();
        if (dByteArray.length < 32) {
            privateKeyByteArray = new byte[32];
            System.arraycopy(dByteArray, 0, privateKeyByteArray, 32 - dByteArray.length, dByteArray.length);
        } else if (dByteArray.length == 32) {
            privateKeyByteArray = dByteArray;
        } else {
            privateKeyByteArray = new byte[32];
            System.arraycopy(dByteArray, dByteArray.length - 32, privateKeyByteArray, 0, 32);
        }

        return privateKeyByteArray;
    }

    public static PrivateKey getEd25519PrivateKeyFromSpec(final HapiSpec spec, final String privateKeyRef) {
        var key = spec.registry().getKey(privateKeyRef);
        final var privateKey = spec.keys()
                .getEd25519PrivateKey(CommonUtils.hex(key.getEd25519().toByteArray()));
        return privateKey;
    }

    private static double getChargedUsed(@NonNull final HapiSpec spec, @NonNull final String txn) {
        requireNonNull(spec);
        requireNonNull(txn);
        var subOp = getTxnRecord(txn).logged();
        allRunFor(spec, subOp);
        final var rcd = subOp.getResponseRecord();
        return (1.0 * rcd.getTransactionFee())
                / ONE_HBAR
                / rcd.getReceipt().getExchangeRate().getCurrentRate().getHbarEquiv()
                * rcd.getReceipt().getExchangeRate().getCurrentRate().getCentEquiv()
                / 100;
    }

    private static double getChargedUsedForInnerTxn(
            @NonNull final HapiSpec spec, @NonNull final String parent, @NonNull final String txn) {
        requireNonNull(spec);
        requireNonNull(txn);
        var subOp = getTxnRecord(txn).assertingNothingAboutHashes().logged();
        var parentOp = getTxnRecord(parent);
        allRunFor(spec, parentOp, subOp);
        final var rcd = subOp.getResponseRecord();
        final var parentRcd = parentOp.getResponseRecord();
        return (1.0 * rcd.getTransactionFee())
                / ONE_HBAR
                / parentRcd.getReceipt().getExchangeRate().getCurrentRate().getHbarEquiv()
                * parentRcd.getReceipt().getExchangeRate().getCurrentRate().getCentEquiv()
                / 100;
    }

    /**
     * Returns the charged gas for a transaction in USD, assuming a standard cost of
     * 71 tinybars per gas unit.
     *
     * @param spec the spec
     * @param txn the transaction
     * @return the charged gas in USD
     */
    private static double getChargedGas(@NonNull final HapiSpec spec, @NonNull final String txn) {
        requireNonNull(spec);
        requireNonNull(txn);
        var subOp = getTxnRecord(txn).logged();
        allRunFor(spec, subOp);
        final var rcd = subOp.getResponseRecord();
        final var gasUsed = rcd.getContractCallResult().getGasUsed();
        return (gasUsed * 71.0)
                / ONE_HBAR
                / rcd.getReceipt().getExchangeRate().getCurrentRate().getHbarEquiv()
                * rcd.getReceipt().getExchangeRate().getCurrentRate().getCentEquiv()
                / 100;
    }

    private static double getChargedUsdFromChild(@NonNull final HapiSpec spec, @NonNull final String txn) {
        requireNonNull(spec);
        requireNonNull(txn);
        var subOp = getTxnRecord(txn).andAllChildRecords();
        allRunFor(spec, subOp);
        final var rcd = subOp.getResponseRecord();
        final var fees = subOp.getChildRecords().isEmpty()
                ? 0L
                : subOp.getChildRecords().stream()
                        .mapToLong(TransactionRecord::getTransactionFee)
                        .sum();
        return (1.0 * (rcd.getTransactionFee() + fees))
                / ONE_HBAR
                / rcd.getReceipt().getExchangeRate().getCurrentRate().getHbarEquiv()
                * rcd.getReceipt().getExchangeRate().getCurrentRate().getCentEquiv()
                / 100;
    }

    /**
     * Asserts that a sequence of log messages appears in the specified node's log within a timeframe.
     *
     * @param selector the node selector
     * @param startTimeSupplier supplier for the start time of the timeframe
     * @param timeframe the duration of the timeframe window to search for messages
     * @param waitTimeout the duration to wait for messages to appear
     * @param patterns the sequence of patterns to look for
     * @return a new LogContainmentTimeframeOp
     */
    public static LogContainmentTimeframeOp assertHgcaaLogContainsTimeframe(
            @NonNull final NodeSelector selector,
            @NonNull final Supplier<Instant> startTimeSupplier,
            @NonNull final Duration timeframe,
            @NonNull final Duration waitTimeout,
            @NonNull final String... patterns) {
        return new LogContainmentTimeframeOp(
                selector,
                ExternalPath.APPLICATION_LOG,
                Arrays.asList(patterns),
                startTimeSupplier,
                timeframe,
                waitTimeout);
    }
}
