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
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import java.time.InstantSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Pins the shipped genesis simple fee schedule to the fee calculators the services register. */
@ExtendWith(MockitoExtension.class)
class SimpleFeeScheduleCoverageTest {
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
        final var feeManager =
                new FeeManager(exchangeRateManager, congestionMultipliers, serviceFeeCalculators, queryFeeCalculators);

        final var genesisBytes = new V0490FileSchema().genesisSimpleFeesSchedules(config);

        assertThat(feeManager.unpricedFunctions(FeeSchedule.PROTOBUF.parseStrict(genesisBytes)))
                .isEmpty();
        assertEquals(SUCCESS, feeManager.updateSimpleFees(genesisBytes));
    }
}
