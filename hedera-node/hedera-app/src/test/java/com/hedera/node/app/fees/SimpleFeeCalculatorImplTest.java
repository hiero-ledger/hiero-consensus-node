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
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
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

    @Mock
    private FeeContext feeContext;

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
                    @NonNull SimpleFeeContext simpleFeeContext,
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
                        makeExtraDef(Extra.STATE_BYTES, 10))
                .services(makeService(
                        "FileService",
                        makeServiceFee(
                                HederaFunctionality.FILE_CREATE,
                                499000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.STATE_BYTES, 1000))))
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
        var simpleFeeContext = createMockSimpleFeeContext();
        when(congestionMultipliers.maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), any(ReadableStoreFactory.class)))
                .thenReturn(1L);

        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);

        var result = calculator.calculateTxFee(createFileCreateTxnBody(), simpleFeeContext);

        // With multiplier of 1, fee components remain unchanged
        // Calculate expected base fee from components
        long expectedTotal =
                result.getNodeTotalTinycents() + result.getNetworkTotalTinycents() + result.getServiceTotalTinycents();
        assertThat(result.totalTinycents()).isEqualTo(expectedTotal);
    }

    @Test
    @DisplayName("With congestion multiplier of 7, fee is multiplied by 7")
    void calculateTxFee_withCongestionMultiplierSeven_returnsSevenXFee() {
        var simpleFeeContext = createMockSimpleFeeContext();

        // First calculate base fee with multiplier returning 1
        var noMultiplierMock = mock(CongestionMultipliers.class);
        when(noMultiplierMock.maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), any(ReadableStoreFactory.class)))
                .thenReturn(1L);
        var calculatorNoMultiplier =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), noMultiplierMock);
        var baseFeeResult = calculatorNoMultiplier.calculateTxFee(createFileCreateTxnBody(), simpleFeeContext);
        long baseFee = baseFeeResult.totalTinycents();

        // Now calculate with congestion multiplier of 7
        when(congestionMultipliers.maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), any(ReadableStoreFactory.class)))
                .thenReturn(7L);
        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);
        var result = calculator.calculateTxFee(createFileCreateTxnBody(), simpleFeeContext);

        // The total fee should be 7x the base fee
        assertThat(result.totalTinycents()).isEqualTo(baseFee * 7);
    }

    @Test
    @DisplayName("With null fee context inside SimpleFeeContext, no congestion multiplier applied")
    void calculateTxFee_withNullFeeContext_noCongestionApplied() {
        // Create a SimpleFeeContext that returns null for feeContext()
        var simpleFeeContext = mock(SimpleFeeContext.class);
        lenient().when(simpleFeeContext.numTxnSignatures()).thenReturn(1);
        lenient().when(simpleFeeContext.numTxnBytes()).thenReturn(100);
        when(simpleFeeContext.feeContext()).thenReturn(null);

        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);

        var result = calculator.calculateTxFee(createFileCreateTxnBody(), simpleFeeContext);

        verify(congestionMultipliers, never())
                .maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), any(ReadableStoreFactory.class));
        assertThat(result.totalTinycents()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Congestion multiplier is correctly passed HederaFunctionality for the transaction type")
    void calculateTxFee_passesCorrectFunctionality() {
        var simpleFeeContext = createMockSimpleFeeContext();
        when(congestionMultipliers.maxCurrentMultiplier(
                        any(TransactionBody.class),
                        eq(HederaFunctionality.FILE_CREATE),
                        any(ReadableStoreFactory.class)))
                .thenReturn(3L);

        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);

        calculator.calculateTxFee(createFileCreateTxnBody(), simpleFeeContext);

        verify(congestionMultipliers)
                .maxCurrentMultiplier(
                        any(TransactionBody.class),
                        eq(HederaFunctionality.FILE_CREATE),
                        any(ReadableStoreFactory.class));
    }

    @Test
    @DisplayName("ReadableStoreFactory is passed to congestion multipliers")
    void calculateTxFee_passesStoreFactory() {
        var simpleFeeContext = createMockSimpleFeeContext();
        when(congestionMultipliers.maxCurrentMultiplier(
                        any(TransactionBody.class), any(HederaFunctionality.class), eq(storeFactory)))
                .thenReturn(2L);

        var calculator =
                new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of(), congestionMultipliers);

        calculator.calculateTxFee(createFileCreateTxnBody(), simpleFeeContext);

        // Verify that the ReadableStoreFactory is passed
        verify(congestionMultipliers)
                .maxCurrentMultiplier(any(TransactionBody.class), any(HederaFunctionality.class), eq(storeFactory));
    }

    @Test
    @DisplayName("With null congestion multipliers, no congestion multiplier applied")
    void calculateTxFee_withNullCongestionMultipliers_noCongestionApplied() {
        var simpleFeeContext = createMockSimpleFeeContext();

        // Use the constructor that passes null for congestionMultipliers
        var calculator = new SimpleFeeCalculatorImpl(testSchedule, serviceFeeCalculators, Set.of());

        var result = calculator.calculateTxFee(createFileCreateTxnBody(), simpleFeeContext);

        // Should not throw and should return a valid fee
        assertThat(result.totalTinycents()).isGreaterThan(0);
    }

    /**
     * Creates a mock SimpleFeeContext with feeContext configured to return storeFactory.
     */
    private SimpleFeeContext createMockSimpleFeeContext() {
        var simpleFeeContext = mock(SimpleFeeContext.class);
        lenient().when(simpleFeeContext.numTxnSignatures()).thenReturn(1);
        lenient().when(simpleFeeContext.numTxnBytes()).thenReturn(100);
        lenient().when(simpleFeeContext.feeContext()).thenReturn(feeContext);
        lenient().when(feeContext.readableStoreFactory()).thenReturn(storeFactory);
        return simpleFeeContext;
    }
}
