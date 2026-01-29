// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.store.ReadableStoreFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SimpleFeeCalculatorImpl} focusing on congestion multiplier logic.
 */
@ExtendWith(MockitoExtension.class)
class SimpleFeeCalculatorImplTest {

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Mock
    private ReadableStoreFactory storeFactory;

    private FeeSchedule testSchedule;
    private Set<ServiceFeeCalculator> serviceFeeCalculators;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
        // Create a mock service fee calculator for FILE_CREATE
        ServiceFeeCalculator mockFileCreateCalculator = new ServiceFeeCalculator() {
            @Override
            public void accumulateServiceFee(
                    @NonNull TransactionBody txnBody,
                    @Nullable FeeContext feeContext,
                    @NonNull FeeResult feeResult,
                    @NonNull org.hiero.hapi.support.fees.FeeSchedule feeSchedule) {
                // Set a fixed service fee for testing
                feeResult.setServiceBaseFeeTinycents(499000000L);
            }

            @Override
            public TransactionBody.DataOneOfType getTransactionType() {
                return TransactionBody.DataOneOfType.FILE_CREATE;
            }
        };
        serviceFeeCalculators = Set.of(mockFileCreateCalculator);
    }

    private FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(makeExtraIncluded(Extra.SIGNATURES, 1))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000),
                        makeExtraDef(Extra.KEYS, 10000000),
                        makeExtraDef(Extra.BYTES, 10))
                .services(makeService(
                        "FileService",
                        makeServiceFee(
                                HederaFunctionality.FILE_CREATE,
                                499000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 1000))))
                .build();
    }

    private TransactionBody createFileCreateTxnBody() {
        return TransactionBody.newBuilder()
                .fileCreate(FileCreateTransactionBody.newBuilder().build())
                .build();
    }

    @Test
    @DisplayName("With congestion multiplier of 1, total fee equals base fee")
    void calculateTxFee_withCongestionMultiplierOne_returnsBaseFee() {
        var feeContext = createMockFeeContextImpl();
        when(congestionMultipliers.maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), any(ReadableStoreFactory.class)))
                .thenReturn(1L);

        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);

        var result = calculator.calculateTxFee(createFileCreateTxnBody(), feeContext);

        // With multiplier of 1, fee components remain unchanged
        // Calculate expected base fee from components
        long expectedTotal =
                result.getNodeTotalTinycents() + result.getNetworkTotalTinycents() + result.getServiceTotalTinycents();
        assertThat(result.totalTinycents()).isEqualTo(expectedTotal);
    }

    @Test
    @DisplayName("With congestion multiplier of 7, fee is multiplied by 7")
    void calculateTxFee_withCongestionMultiplierSeven_returnsSevenXFee() {
        var feeContext = createMockFeeContextImpl();

        // First calculate base fee with multiplier returning 1
        var noMultiplierMock = mock(CongestionMultipliers.class);
        when(noMultiplierMock.maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), any(ReadableStoreFactory.class)))
                .thenReturn(1L);
        var calculatorNoMultiplier =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), noMultiplierMock);
        var baseFeeResult = calculatorNoMultiplier.calculateTxFee(createFileCreateTxnBody(), feeContext);
        long baseFee = baseFeeResult.totalTinycents();

        // Now calculate with congestion multiplier of 7
        when(congestionMultipliers.maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), any(ReadableStoreFactory.class)))
                .thenReturn(7L);
        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);
        var result = calculator.calculateTxFee(createFileCreateTxnBody(), feeContext);

        // The total fee should be 7x the base fee
        assertThat(result.totalTinycents()).isEqualTo(baseFee * 7);
    }

    @Test
    @DisplayName("With null fee context, no congestion multiplier applied")
    void calculateTxFee_withNullFeeContext_noCongestionApplied() {
        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);

        var result = calculator.calculateTxFee(createFileCreateTxnBody(), null);

        verify(congestionMultipliers, never())
                .maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), any(ReadableStoreFactory.class));
        assertThat(result.totalTinycents()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Congestion multiplier is correctly passed HederaFunctionality for the transaction type")
    void calculateTxFee_passesCorrectFunctionality() {
        var feeContext = createMockFeeContextImpl();
        when(congestionMultipliers.maxCurrentMultiplier(
                        any(TransactionBody.class),
                        eq(HederaFunctionality.FILE_CREATE),
                        any(ReadableStoreFactory.class)))
                .thenReturn(3L);

        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);

        calculator.calculateTxFee(createFileCreateTxnBody(), feeContext);

        verify(congestionMultipliers)
                .maxCurrentMultiplier(
                        any(TransactionBody.class),
                        eq(HederaFunctionality.FILE_CREATE),
                        any(ReadableStoreFactory.class));
    }

    @Test
    @DisplayName("ReadableStoreFactory is passed to congestion multipliers")
    void calculateTxFee_passesStoreFactory() {
        var feeContext = createMockFeeContextImpl();
        when(congestionMultipliers.maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), eq(storeFactory)))
                .thenReturn(2L);

        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);

        calculator.calculateTxFee(createFileCreateTxnBody(), feeContext);

        // Verify that the ReadableStoreFactory is passed
        verify(congestionMultipliers)
                .maxCurrentMultiplier(any(TransactionBody.class), any(HederaFunctionality.class), eq(storeFactory));
    }

    @Test
    @DisplayName("With non-FeeContextImpl context, no congestion multiplier applied")
    void calculateTxFee_withGenericFeeContext_noCongestionApplied() {
        // Use a generic mock that doesn't extend FeeContextImpl or ChildFeeContextImpl
        var feeContext = mock(FeeContext.class);
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
        lenient().when(feeContext.numTxnBytes()).thenReturn(100);

        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);

        var result = calculator.calculateTxFee(createFileCreateTxnBody(), feeContext);

        // No congestion multiplier should be called since we can't get store factory
        verify(congestionMultipliers, never())
                .maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), any(ReadableStoreFactory.class));
        assertThat(result.totalTinycents()).isGreaterThan(0);
    }

    /**
     * Creates a mock FeeContextImpl with storeFactory configured.
     */
    private FeeContextImpl createMockFeeContextImpl() {
        var feeContext = mock(FeeContextImpl.class);
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
        lenient().when(feeContext.numTxnBytes()).thenReturn(100);
        lenient().when(feeContext.storeFactory()).thenReturn(storeFactory);
        return feeContext;
    }
}
