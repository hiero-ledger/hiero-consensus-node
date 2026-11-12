// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REMOVABLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.clpr.ClprCompleteChannelTransactionBody;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.impl.PairedStreamBuilder;
import com.hedera.node.app.service.clpr.impl.ClprGasAccounting;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.workflows.ContractGasUsage;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.StreamMode;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClprGasUsageStreamBuilderTest {
    private static final AccountID PAYER =
            AccountID.newBuilder().accountNum(1001).build();

    @ParameterizedTest
    @EnumSource(StreamMode.class)
    void revertedVerifierUsesTheSameActualGasInEveryStreamMode(final StreamMode mode) {
        final var builder = builderFor(mode);
        final var contractBuilder = (ContractCallStreamBuilder) builder;
        contractBuilder.contractCallResult(
                ContractFunctionResult.newBuilder().gasUsed(400L).build());
        contractBuilder.evmCallTransactionResult(
                EvmTransactionResult.newBuilder().gasUsed(400L).build());
        builder.status(CONTRACT_REVERT_EXECUTED);
        final var gasUsage = new ContractGasUsage();
        gasUsage.recordGasUsed(400L);

        builder.nullOutSideEffectFields();

        // The real legacy/paired builder clears its result; BLOCKS retains it. Billing must not depend on this.
        assertThat(builder.hasContractResult()).isEqualTo(mode == StreamMode.BLOCKS);
        final var context = pricedContext();
        final var accounting = ClprGasAccounting.forContext(context);
        assertThat(accounting.prepayVerifier(PAYER, 1000L)).isTrue();
        assertThat(accounting.settleVerifierWithGasUsage(PAYER, 1000L, gasUsage))
                .isEqualTo(800L);
        accounting.refundUnusedGas();

        verify(context).tryToCharge(PAYER, 2000L);
        verify(context).refundHandlerCharge(PAYER, 1200L);
    }

    private static StreamBuilder builderFor(final StreamMode mode) {
        return switch (mode) {
            case BLOCKS -> new BlockStreamBuilder(REMOVABLE, NOOP_SIGNED_TX_CUSTOMIZER, CHILD);
            case RECORDS -> new RecordStreamBuilder(REMOVABLE, NOOP_SIGNED_TX_CUSTOMIZER, CHILD);
            case BOTH -> new PairedStreamBuilder(REMOVABLE, NOOP_SIGNED_TX_CUSTOMIZER, CHILD);
        };
    }

    private static HandleContext pricedContext() {
        final var context = mock(HandleContext.class);
        given(context.payer()).willReturn(PAYER);
        given(context.body())
                .willReturn(TransactionBody.newBuilder()
                        .clprCompleteChannel(ClprCompleteChannelTransactionBody.DEFAULT)
                        .transactionFee(10000L)
                        .build());
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("contracts.maxGasPerTransaction", 1000L)
                        .withValue("clpr.verifierGasLimit", 1000L)
                        .getOrCreateConfig());
        given(context.dispatchMetadata()).willReturn(new DispatchMetadata(new HashMap<>()));
        given(context.getGasPriceInTinycents()).willReturn(2L);
        final var priceCalculator = mock(ResourcePriceCalculator.class);
        given(context.resourcePriceCalculator()).willReturn(priceCalculator);
        given(priceCalculator.resourcePricesFor(CONTRACT_CALL, DEFAULT))
                .willReturn(new FunctionalityResourcePrices(FeeData.DEFAULT, 1L));
        final var rates = mock(ExchangeRateInfo.class);
        given(context.exchangeRateInfo()).willReturn(rates);
        given(context.consensusNow()).willReturn(Instant.EPOCH);
        given(rates.activeRate(Instant.EPOCH))
                .willReturn(ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(1).build());
        final var stack = mock(HandleContext.SavepointStack.class);
        final var parent = mock(StreamBuilder.class);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(StreamBuilder.class)).willReturn(parent);
        given(context.tryToCharge(any(AccountID.class), anyLong())).willReturn(true);
        return context;
    }
}
