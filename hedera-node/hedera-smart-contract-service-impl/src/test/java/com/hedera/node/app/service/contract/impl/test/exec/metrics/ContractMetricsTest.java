// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.wipe.WipeTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.Executors;
import org.hiero.consensus.model.node.NodeId;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ContractMetricsTest {

    private final Metrics metrics = fakeMetrics();

    @Mock
    private ContractsConfig contractsConfig;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    public @NonNull ContractMetrics getSubject() {
        final var contractMetrics = new ContractMetrics(metrics, () -> contractsConfig, systemContractMethodRegistry);
        contractMetrics.createContractPrimaryMetrics();
        contractMetrics.createContractSecondaryMetrics();
        return contractMetrics;
    }

    @Test
    public void rejectedTxCountersGetBumpedWhenEnabled() {
        given(contractsConfig.metricsSmartContractPrimaryEnabled()).willReturn(true);

        final var subject = getSubject();

        subject.incrementRejectedTx(HederaFunctionality.CONTRACT_CALL);
        subject.bumpRejectedTx(HederaFunctionality.CONTRACT_CREATE, 2);
        subject.bumpRejectedTx(HederaFunctionality.ETHEREUM_TRANSACTION, 4);

        subject.bumpRejectedForGasTx(HederaFunctionality.CONTRACT_CALL, 10);
        subject.bumpRejectedForGasTx(HederaFunctionality.CONTRACT_CREATE, 12);
        subject.bumpRejectedForGasTx(HederaFunctionality.ETHEREUM_TRANSACTION, 14);

        subject.bumpRejectedType3EthTx(20);

        assertThat(subject.getAllP1CounterNames())
                .containsExactlyInAnyOrder(
                        "SmartContractService:Rejected_ethereumTxDueToIntrinsicGas_total",
                        "SmartContractService:Rejected_ethereumTx_total",
                        "SmartContractService:Rejected_contractCallTxDueToIntrinsicGas_total",
                        "SmartContractService:Rejected_contractCallTx_total",
                        "SmartContractService:Rejected_contractCreateTxDueToIntrinsicGas_total",
                        "SmartContractService:Rejected_contractCreateTx_total",
                        "SmartContractService:Rejected_ethType3BlobTransaction_total");
        assertThat(subject.getAllP1CounterValues())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "SmartContractService:Rejected_ethereumTxDueToIntrinsicGas_total",
                        14L,
                        "SmartContractService:Rejected_ethereumTx_total",
                        4L,
                        "SmartContractService:Rejected_contractCallTxDueToIntrinsicGas_total",
                        10L,
                        "SmartContractService:Rejected_contractCallTx_total",
                        1L,
                        "SmartContractService:Rejected_contractCreateTxDueToIntrinsicGas_total",
                        12L,
                        "SmartContractService:Rejected_contractCreateTx_total",
                        2L,
                        "SmartContractService:Rejected_ethType3BlobTransaction_total",
                        20L));

        // And there is no counter for this functionality
        assertThrows(NullPointerException.class, () -> {
            subject.bumpRejectedTx(HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE, 22);
        });
    }

    @Test
    public void countersGetIgnoredWhenDisabled() {
        given(contractsConfig.metricsSmartContractPrimaryEnabled()).willReturn(false);
        given(contractsConfig.metricsSmartContractSecondaryEnabled()).willReturn(false);

        final var subject = getSubject();

        subject.incrementRejectedTx(HederaFunctionality.CONTRACT_CALL);
        subject.bumpRejectedTx(HederaFunctionality.CONTRACT_CREATE, 2);
        subject.bumpRejectedTx(HederaFunctionality.ETHEREUM_TRANSACTION, 4);

        subject.bumpRejectedForGasTx(HederaFunctionality.CONTRACT_CALL, 10);
        subject.bumpRejectedForGasTx(HederaFunctionality.CONTRACT_CREATE, 12);
        subject.bumpRejectedForGasTx(HederaFunctionality.ETHEREUM_TRANSACTION, 14);

        subject.bumpRejectedType3EthTx(20);

        subject.incrementSystemMethodCall(WipeTranslator.WIPE_FUNGIBLE_V1, State.EXCEPTIONAL_HALT);

        assertThat(subject.getAllCounterNames()).isEmpty();
        assertThat(subject.getAllCounterValues()).isEmpty();
    }

    private static final long DEFAULT_NODE_ID = 3;

    public static Metrics fakeMetrics() {
        final MetricsConfig metricsConfig =
                HederaTestConfigBuilder.createConfig().getConfigData(MetricsConfig.class);

        return new DefaultPlatformMetrics(
                NodeId.of(DEFAULT_NODE_ID),
                new MetricKeyRegistry(),
                Executors.newSingleThreadScheduledExecutor(),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }
}
