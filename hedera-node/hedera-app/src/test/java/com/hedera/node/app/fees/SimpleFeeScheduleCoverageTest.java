// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.fees.FakeGenesisState.NO_OP_METRICS;
import static com.hedera.node.app.spi.AppContext.Gossip.UNAVAILABLE_GOSSIP;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.UNIVERSAL_NOOP_FEE_CHARGING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.AppContextImpl;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.throttle.AppScheduleThrottleFactory;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Pins the shipped genesis simple fee schedule to the fee calculators the services register. */
@ExtendWith(MockitoExtension.class)
class SimpleFeeScheduleCoverageTest {
    /**
     * A generous reserve for what wraps the schedule bytes in an actual FileUpdate: the {@code TransactionBody}
     * envelope (transaction ID, node account ID, fee, valid duration, memo, the FileUpdate field tag) and the
     * {@code SignedTransaction}'s {@code SignatureMap} (comfortably covers a multi-key threshold signature). The
     * pins below measure only the serialized schedule, so this budget is subtracted from the transaction limit
     * rather than measuring a real signed transaction, which this module cannot construct.
     */
    private static final int TRANSACTION_OVERHEAD_BUDGET = 2_000;

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private NodeInfo nodeInfo;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Test
    void genesisSchedulePricesEveryRegisteredTransactionAndQueryType() throws ParseException {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var feeManager = realFeeManager(config);
        final var genesisBytes = new V0490FileSchema().genesisSimpleFeesSchedules(config);
        final var genesis = FeeSchedule.PROTOBUF.parseStrict(genesisBytes);

        assertThat(feeManager.unpricedFunctions(genesis)).isEmpty();
        assertThat(feeManager.missingExtras(genesis)).isEmpty();
        assertEquals(SUCCESS, feeManager.updateSimpleFees(genesisBytes, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "testnet", "previewnet"})
    void shippedUpgradeSchedulesProvideEveryExtraTheCalculatorsCharge(final String network) throws Exception {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var feeManager = realFeeManager(config);
        final var json =
                Files.readAllBytes(Path.of("..", "configuration", network, "upgrade", "simpleFeesSchedules.json"));

        final var schedule = FeeSchedule.JSON.parse(Bytes.wrap(json).toReadableSequentialData());

        assertThat(feeManager.unpricedFunctions(schedule)).isEmpty();
        assertThat(feeManager.missingExtras(schedule)).isEmpty();
        // The two checks above only prove the per-function/per-extra coverage this PR adds; they say nothing about
        // whether every referenced extra is actually defined (isValid's own job). Driving the schedule through the
        // real, strict update path proves the shipped file is accepted end to end, not just by these two helpers.
        assertEquals(SUCCESS, feeManager.updateSimpleFees(FeeSchedule.PROTOBUF.toBytes(schedule), true));
    }

    /**
     * A FileUpdate/FileAppend of the simple fee schedule file is rejected atomically (the file write itself rolls
     * back) only because every real update today is a single transaction: genesis and the three shipped upgrade
     * schedules all fit under one transaction's maximum size. If a shipped schedule ever needed chunking, a
     * multi-chunk upload would have to be validated as it assembles, not atomically per transaction — a different,
     * larger design. This pin fails loudly if growth ever approaches that boundary, instead of silently depending
     * on an assumption nothing else checks.
     */
    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "testnet", "previewnet"})
    void shippedUpgradeSchedulesFitInASingleTransaction(final String network) throws Exception {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var json =
                Files.readAllBytes(Path.of("..", "configuration", network, "upgrade", "simpleFeesSchedules.json"));
        final var schedule = V0490FileSchema.parseSimpleFeesSchedules(json);
        final var serializedSize = FeeSchedule.PROTOBUF.toBytes(schedule).length();

        assertThat(serializedSize).isLessThan(singleTransactionBudget(config));
    }

    @Test
    void genesisScheduleFitsInASingleTransaction() throws ParseException {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var genesisBytes = new V0490FileSchema().genesisSimpleFeesSchedules(config);

        assertThat(genesisBytes.length()).isLessThan(singleTransactionBudget(config));
    }

    /**
     * The configured {@link com.hedera.node.config.data.HederaConfig#transactionMaxBytes()}, less a generous
     * reserve for what wraps the schedule bytes in an actual FileUpdate: the {@code TransactionBody} envelope
     * (transaction ID, node account ID, fee, valid duration, memo, the FileUpdate field tag) and the
     * {@code SignedTransaction}'s {@code SignatureMap} (comfortably covers a multi-key threshold signature). Reads
     * the live configured limit, rather than a hardcoded copy of its default, so this pin does not silently go
     * stale if that default ever changes. The reserve is still an approximation, not a measurement of a real signed
     * transaction, since this module cannot construct one.
     */
    private static int singleTransactionBudget(final Configuration config) {
        final var transactionMaxBytes = config.getConfigData(HederaConfig.class).transactionMaxBytes();
        final var overheadBudget = 2_000;
        return transactionMaxBytes - overheadBudget;
    }

    private FeeManager realFeeManager(final Configuration config) {
        final var state = new FakeState();
        final var appContext = new AppContextImpl(
                InstantSource.system(),
                signatureVerifier,
                UNAVAILABLE_GOSSIP,
                () -> config,
                () -> nodeInfo,
                () -> NO_OP_METRICS,
                new AppScheduleThrottleFactory(
                        () -> config, () -> state, () -> ThrottleDefinitions.DEFAULT, ThrottleAccumulator::new),
                () -> UNIVERSAL_NOOP_FEE_CHARGING,
                new AppEntityIdFactory(config));
        final List<RpcService> services = List.of(
                new ConsensusServiceImpl(),
                new ContractServiceImpl(appContext, NO_OP_METRICS),
                new FileServiceImpl(),
                new NetworkServiceImpl(),
                new ScheduleServiceImpl(appContext),
                new TokenServiceImpl(appContext),
                new UtilServiceImpl(appContext, (signedTxn, cfg) -> null));
        final Set<ServiceFeeCalculator> serviceFeeCalculators = new HashSet<>();
        final Set<QueryFeeCalculator> queryFeeCalculators = new HashSet<>();
        for (final var service : services) {
            serviceFeeCalculators.addAll(service.serviceFeeCalculators());
            queryFeeCalculators.addAll(service.queryFeeCalculators());
        }
        return new FeeManager(exchangeRateManager, congestionMultipliers, serviceFeeCalculators, queryFeeCalculators);
    }
}
