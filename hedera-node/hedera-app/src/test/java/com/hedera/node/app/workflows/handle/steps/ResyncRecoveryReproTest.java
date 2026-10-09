// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.FRONTEND_THROTTLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServicesConfigurationList;
import com.hedera.hapi.node.base.Setting;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.ThrottleBucket;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.ThrottleGroup;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fees.FeeService;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.fees.schemas.V0490FeeSchema;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.throttle.ThrottleParser;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.handle.steps.SystemFileUpdates.ResyncTarget;
import com.hedera.node.app.workflows.handle.steps.SystemFileUpdates.ResyncTarget.Facility;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Reproduces defects in the in-memory facility re-sync that runs after a system file update is rolled back
 * (for example when a contract completes a scheduled system file update and then reverts). Each test uses the real
 * implementation of the facility whose state the defect corrupts and asserts the correct behavior, so it fails
 * on the current code.
 */
@ExtendWith(MockitoExtension.class)
class ResyncRecoveryReproTest {

    private static final Instant CONSENSUS_NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final TimestampSeconds EXPIRY =
            TimestampSeconds.newBuilder().seconds(150_000L).build();
    private static final AccountID SYSTEM_ADMIN =
            AccountID.newBuilder().accountNum(50L).build();
    private static final AccountID RATES_ADMIN =
            AccountID.newBuilder().accountNum(57L).build();

    @Mock(strictness = Strictness.LENIENT)
    private ConfigProviderImpl configProvider;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    private FakeState state;
    private Map<FileID, File> files;
    private AtomicReference<ExchangeRateSet> midnightRates;
    private FilesConfig filesConfig;

    private ExchangeRateManager exchangeRateManager;
    private FeeManager feeManager;
    private ThrottleAccumulator backendThrottle;
    private ThrottleServiceManager throttleServiceManager;
    private SystemFileUpdates subject;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.createConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1L));
        filesConfig = config.getConfigData(FilesConfig.class);

        files = new HashMap<>();
        midnightRates = new AtomicReference<>();
        state = new FakeState()
                .addService(FileService.NAME, Map.of(FILES_STATE_ID, files))
                .addService(FeeService.NAME, Map.of(V0490FeeSchema.MIDNIGHT_RATES_STATE_ID, midnightRates));

        exchangeRateManager = new ExchangeRateManager(configProvider);
        feeManager = new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(), Set.of());
        final var ingestThrottle =
                new ThrottleAccumulator(configProvider::getConfiguration, () -> 1, FRONTEND_THROTTLE);
        backendThrottle = new ThrottleAccumulator(configProvider::getConfiguration, () -> 1, BACKEND_THROTTLE);
        throttleServiceManager = new ThrottleServiceManager(
                new ThrottleParser(configProvider), ingestThrottle, backendThrottle, congestionMultipliers);

        subject = new SystemFileUpdates(
                configProvider, exchangeRateManager, feeManager, throttleServiceManager, blockNodeConnectionManager);
    }

    // ---- Bug 1: file 0.0.123 ------------------------------------------------------------------------------------

    @Test
    void throttleRecoveryPreservesAccumulatedUsage() {
        final var fileId = fileIdOf(filesConfig.throttleDefinitions());
        final var committedDefs = ThrottleDefinitions.PROTOBUF.toBytes(ThrottleDefinitions.newBuilder()
                .throttleBuckets(ThrottleBucket.newBuilder()
                        .name("Transfers")
                        .burstPeriodMs(1000)
                        .throttleGroups(ThrottleGroup.newBuilder()
                                .milliOpsPerSec(10_000)
                                .operations(CRYPTO_TRANSFER)
                                .build())
                        .build())
                .build());
        files.put(fileId, File.newBuilder().contents(committedDefs).build());
        // The node has been running on these definitions and the backend bucket has accumulated usage
        throttleServiceManager.recreateThrottles(committedDefs);
        final var usedBefore = 1_000_000_000_000L;
        final var lastDecision = new Timestamp(1_234_567L, 890);
        backendThrottle
                .allActiveThrottles()
                .getFirst()
                .resetUsageTo(new ThrottleUsageSnapshot(usedBefore, lastDecision));
        assertThat(backendThrottle.allActiveThrottles().getFirst().used()).isEqualTo(usedBefore);

        // A malformed 0.0.123 upload made ThrottleParser.parse() throw, so the in-memory throttles were never
        // touched and the file bytes were rolled back to the committed definitions. The applied contents
        // (the malformed bytes) differ from the committed bytes, so recovery runs.
        subject.resyncIfChanged(
                state, new ResyncTarget(Facility.THROTTLE_DEFINITIONS, fileId, Bytes.wrap("NOT_THROTTLE_DEFS")));

        // Nothing about the active throttle definitions changed, so the usage must survive the recovery
        final var throttle = backendThrottle.allActiveThrottles().getFirst();
        assertThat(throttle.usageSnapshot())
                .as("backend throttle usage after recovery")
                .isEqualTo(new ThrottleUsageSnapshot(usedBefore, lastDecision));
    }

    // ---- Derived case: file 0.0.121 ---------------------------------------------------------------------------

    /**
     * Recovery for network properties calls {@code refreshThrottleConfiguration()}, which recreates the gas, bytes and
     * ops-duration throttles from configuration and resets the congestion expectations. The committed properties may
     * not have changed anything throttle-related, yet the gas throttle's accumulated usage is lost.
     */
    @Test
    void networkPropertiesRecoveryPreservesGasThrottleUsage() {
        final var propertiesId = fileIdOf(filesConfig.networkProperties());
        final var permissionsId = fileIdOf(filesConfig.hapiPermissions());
        final var committedProperties = ServicesConfigurationList.PROTOBUF.toBytes(ServicesConfigurationList.DEFAULT);
        files.put(propertiesId, File.newBuilder().contents(committedProperties).build());
        files.put(permissionsId, File.newBuilder().contents(committedProperties).build());
        final var defs = ThrottleDefinitions.PROTOBUF.toBytes(ThrottleDefinitions.newBuilder()
                .throttleBuckets(ThrottleBucket.newBuilder()
                        .name("Transfers")
                        .burstPeriodMs(1000)
                        .throttleGroups(ThrottleGroup.newBuilder()
                                .milliOpsPerSec(10_000)
                                .operations(CRYPTO_TRANSFER)
                                .build())
                        .build())
                .build());
        // The node has been running with these throttles and the gas throttle has accumulated usage
        throttleServiceManager.init(state, defs, true);
        final var usedBefore = 1_000_000L;
        final var lastDecision = new Timestamp(1_234_567L, 890);
        backendThrottle.gasLimitThrottle().resetUsageTo(new ThrottleUsageSnapshot(usedBefore, lastDecision));
        assertThat(backendThrottle.gasLimitThrottle().used()).isEqualTo(usedBefore);

        // A rolled-back 0.0.121 update whose applied bytes differ from the committed file triggers recovery
        subject.resyncIfChanged(
                state, new ResyncTarget(Facility.NETWORK_PROPERTIES, propertiesId, Bytes.wrap("NOT_CONFIG")));

        // The committed properties are unchanged, so the gas throttle usage must survive the recovery
        assertThat(backendThrottle.gasLimitThrottle().usageSnapshot())
                .as("backend gas throttle usage after recovery")
                .isEqualTo(new ThrottleUsageSnapshot(usedBefore, lastDecision));
    }

    /**
     * The forward 0.0.121 update refreshes the throttle configuration, which rebuilds the gas throttle and so discards
     * the accumulated usage. If the dispatch is then rolled back, recovery must restore the usage as it was
     * <i>before</i> the update, not the already-discarded (zeroed) usage current at recovery time. This drives the full
     * capture-before-update then restore-on-recovery path, unlike {@link #networkPropertiesRecoveryPreservesGasThrottleUsage()}
     * which never applies the forward update.
     */
    @Test
    void networkPropertiesRecoveryRestoresGasUsageTheForwardUpdateDiscarded() {
        final var propertiesId = fileIdOf(filesConfig.networkProperties());
        final var permissionsId = fileIdOf(filesConfig.hapiPermissions());
        final var committedProperties = ServicesConfigurationList.PROTOBUF.toBytes(ServicesConfigurationList.DEFAULT);
        final var updatedProperties = ServicesConfigurationList.PROTOBUF.toBytes(ServicesConfigurationList.newBuilder()
                .nameValue(Setting.newBuilder()
                        .name("tokens.maxPerAccount")
                        .value("1000")
                        .build())
                .build());
        files.put(permissionsId, File.newBuilder().contents(committedProperties).build());
        final var defs = ThrottleDefinitions.PROTOBUF.toBytes(ThrottleDefinitions.newBuilder()
                .throttleBuckets(ThrottleBucket.newBuilder()
                        .name("Transfers")
                        .burstPeriodMs(1000)
                        .throttleGroups(ThrottleGroup.newBuilder()
                                .milliOpsPerSec(10_000)
                                .operations(CRYPTO_TRANSFER)
                                .build())
                        .build())
                .build());
        throttleServiceManager.init(state, defs, true);
        final var usedBefore = 1_000_000L;
        final var lastDecision = new Timestamp(1_234_567L, 890);
        backendThrottle.gasLimitThrottle().resetUsageTo(new ThrottleUsageSnapshot(usedBefore, lastDecision));

        // The dispatch applies the update: the file holds the updated bytes, resyncTarget captures the pre-update
        // usage, and the forward handleTxBody refreshes the throttle configuration and so discards the gas usage
        files.put(propertiesId, File.newBuilder().contents(updatedProperties).build());
        final var target =
                subject.resyncTarget(state, fileUpdateTxn(propertiesId)).orElseThrow();
        subject.handleTxBody(state, fileUpdateTxn(propertiesId));
        assertThat(backendThrottle.gasLimitThrottle().used())
                .as("the forward update discarded the gas usage")
                .isNotEqualTo(usedBefore);

        // The dispatch is rolled back, restoring the committed file; recovery must restore the pre-update usage
        files.put(propertiesId, File.newBuilder().contents(committedProperties).build());
        subject.resyncFromState(state, target);

        assertThat(backendThrottle.gasLimitThrottle().usageSnapshot())
                .as("backend gas throttle usage after recovery of a forward-applied then rolled-back update")
                .isEqualTo(new ThrottleUsageSnapshot(usedBefore, lastDecision));
    }

    // ---- Bug 2: file 0.0.112 ------------------------------------------------------------------------------------

    @Test
    void identicalBytesUpdateBySystemAdminMustNotLeakMidnightBaseline() {
        final var fileId = fileIdOf(filesConfig.exchangeRates());
        final var rates100 = ratesWithCentEquiv(100);
        final var rates120 = ratesWithCentEquiv(120);
        final var rates144 = ratesWithCentEquiv(144);
        final var bytes100 = ExchangeRateSet.PROTOBUF.toBytes(rates100);
        final var bytes120 = ExchangeRateSet.PROTOBUF.toBytes(rates120);
        final var bytes144 = ExchangeRateSet.PROTOBUF.toBytes(rates144);

        // Midnight baseline is 100, both in memory and in the committed singleton
        midnightRates.set(rates100);
        files.put(fileId, File.newBuilder().contents(bytes100).build());
        exchangeRateManager.init(state, bytes100, rates100);

        // Control: against the true baseline of 100, a move to 144 (44% > 25% limit) is rejected
        assertThatThrownBy(() -> exchangeRateManager.update(bytes144, RATES_ADMIN))
                .isInstanceOf(HandleException.class)
                .extracting(e -> ((HandleException) e).getStatus())
                .isEqualTo(ResponseCodeEnum.EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED);

        // Normal intraday move to 120 by the exchange-rate admin (20% < 25% limit), committed to the file
        files.put(fileId, File.newBuilder().contents(bytes120).build());
        exchangeRateManager.update(bytes120, RATES_ADMIN);

        // A 0.0.50 update whose bytes are identical to the committed file: no file change, but
        // ExchangeRateManager.update() silently moves the in-memory midnight baseline to 120
        exchangeRateManager.update(bytes120, SYSTEM_ADMIN);

        // The dispatch is then rolled back; the committed bytes equal the applied bytes so recovery is skipped
        subject.resyncIfChanged(state, new ResyncTarget(Facility.EXCHANGE_RATES, fileId, bytes120));

        // The committed midnight baseline is still 100 ...
        assertThat(midnightRates.get()).isEqualTo(rates100);
        // ... so a move to 144 (44% above 100) must be rejected as exceeding the 25% intraday limit
        assertThatThrownBy(() -> exchangeRateManager.update(bytes144, RATES_ADMIN))
                .as("update to centEquiv 144 against committed midnight baseline 100")
                .isInstanceOf(HandleException.class)
                .extracting(e -> ((HandleException) e).getStatus())
                .isEqualTo(ResponseCodeEnum.EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED);
    }

    // ---- Bug 3: file 0.0.113 ------------------------------------------------------------------------------------

    @Test
    void simpleFeeRecoveryRestoresPriorScheduleFromPartialCommittedFile() {
        final var fileId = fileIdOf(filesConfig.simpleFeesSchedules());
        final var bytesA = FeeSchedule.PROTOBUF.toBytes(scheduleWithGasPrice(100));
        final var bytesB = FeeSchedule.PROTOBUF.toBytes(scheduleWithGasPrice(200));

        // Schedule A is the active schedule
        assertThat(feeManager.updateSimpleFees(bytesA, false)).isEqualTo(SUCCESS);
        assertThat(feeManager.getGasPriceInTinyCents(CONSENSUS_NOW)).isEqualTo(100L);

        // A scheduled update completed inside a contract call installs schedule B in memory; the resync target carries
        // schedule A, captured before the update applied, as the prior schedule
        assertThat(feeManager.updateSimpleFees(bytesB, false)).isEqualTo(SUCCESS);
        assertThat(feeManager.getGasPriceInTinyCents(CONSENSUS_NOW)).isEqualTo(200L);
        final var target = new ResyncTarget(Facility.SIMPLE_FEES, fileId, bytesB, null, scheduleWithGasPrice(100));

        // ... but is rolled back to the committed file, which holds the first chunk of a multi-chunk upload
        final var partialBytes = bytesB.slice(0, bytesB.length() - 3);
        files.put(fileId, File.newBuilder().contents(partialBytes).build());

        subject.resyncFromState(state, target);

        // The committed file does not parse, so recovery restores the captured prior schedule A
        assertThat(feeManager.getGasPriceInTinyCents(CONSENSUS_NOW))
                .as("active gas price after recovery")
                .isEqualTo(100L);
    }

    @Test
    void simpleFeeRecoveryKeepsActiveScheduleWhenTheRolledBackUpdateWasItselfAPartialChunk() {
        final var fileId = fileIdOf(filesConfig.simpleFeesSchedules());
        final var bytesA = FeeSchedule.PROTOBUF.toBytes(scheduleWithGasPrice(100));
        final var bytesB = FeeSchedule.PROTOBUF.toBytes(scheduleWithGasPrice(200));

        // Schedule A is active and committed
        assertThat(feeManager.updateSimpleFees(bytesA, false)).isEqualTo(SUCCESS);
        assertThat(feeManager.getGasPriceInTinyCents(CONSENSUS_NOW)).isEqualTo(100L);

        // A dispatch appends a partial chunk of a new schedule. Its update never installs (the chunk does not parse),
        // so the active schedule stays A; the target is captured before the update applies.
        final var partialBytes = bytesB.slice(0, bytesB.length() - 3);
        files.put(fileId, File.newBuilder().contents(partialBytes).build());
        final var target = subject.resyncTarget(state, fileUpdateTxn(fileId)).orElseThrow();
        assertThat(feeManager.updateSimpleFees(partialBytes, false)).isEqualTo(FEE_SCHEDULE_FILE_PART_UPLOADED);

        // The dispatch rolls back, leaving a partial chunk in the committed file
        subject.resyncFromState(state, target);

        // The rolled-back update installed nothing, so schedule A must still be active - not an older schedule or the
        // genesis default
        assertThat(feeManager.getGasPriceInTinyCents(CONSENSUS_NOW))
                .as("active gas price after recovering a rolled-back partial update")
                .isEqualTo(100L);
    }

    private static TransactionBody fileUpdateTxn(final FileID fileId) {
        return TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(SYSTEM_ADMIN).build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(fileId))
                .build();
    }

    private static FileID fileIdOf(final long fileNum) {
        return FileID.newBuilder().fileNum(fileNum).build();
    }

    private static ExchangeRateSet ratesWithCentEquiv(final int centEquiv) {
        final var rate =
                ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(centEquiv).expirationTime(EXPIRY);
        return ExchangeRateSet.newBuilder().currentRate(rate).nextRate(rate).build();
    }

    private static FeeSchedule scheduleWithGasPrice(final long gasPrice) {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.GAS, gasPrice))
                .node(NodeFee.DEFAULT.copyBuilder().baseFee(0).build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService("Crypto", makeServiceFee(CRYPTO_CREATE, 0)))
                .build();
    }
}
