// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.util.HapiUtils.functionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.file.FileAppendTransactionBody;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.file.FileDeleteTransactionBody;
import com.hedera.hapi.node.file.FileGetContentsQuery;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.file.SystemDeleteTransactionBody;
import com.hedera.hapi.node.file.SystemUndeleteTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.SimpleFeeContextImpl;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.ReadableFileStoreImpl;
import com.hedera.node.app.service.file.impl.calculator.FileAppendFeeCalculator;
import com.hedera.node.app.service.file.impl.calculator.FileCreateFeeCalculator;
import com.hedera.node.app.service.file.impl.calculator.FileDeleteFeeCalculator;
import com.hedera.node.app.service.file.impl.calculator.FileGetContentsFeeCalculator;
import com.hedera.node.app.service.file.impl.calculator.FileGetInfoFeeCalculator;
import com.hedera.node.app.service.file.impl.calculator.FileSystemDeleteFeeCalculator;
import com.hedera.node.app.service.file.impl.calculator.FileSystemUndeleteFeeCalculator;
import com.hedera.node.app.service.file.impl.calculator.FileUpdateFeeCalculator;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileServiceFeeCalculatorsTest {

    @Mock
    private FeeContext feeContext;

    @Mock
    private Authorizer authorizer;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule,
                Set.of(
                        new FileCreateFeeCalculator(),
                        new FileAppendFeeCalculator(),
                        new FileUpdateFeeCalculator(),
                        new FileDeleteFeeCalculator(),
                        new FileSystemDeleteFeeCalculator(),
                        new FileSystemUndeleteFeeCalculator()),
                Set.of(new FileGetInfoFeeCalculator(), new FileGetContentsFeeCalculator()));
    }

    static Stream<TestCase> provideTestCases() {
        final var transactionID = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(1001).build())
                .build();
        return Stream.of(
                new TestCase(
                        new FileCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .transactionID(transactionID)
                                .fileCreate(
                                        FileCreateTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        100000L,
                        499000000L,
                        200000L),
                new TestCase(
                        new FileDeleteFeeCalculator(),
                        TransactionBody.newBuilder()
                                .transactionID(transactionID)
                                .fileDelete(
                                        FileDeleteTransactionBody.newBuilder().build())
                                .build(),
                        2,
                        1100000L,
                        69000000L,
                        2200000L),
                new TestCase(
                        new FileCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .transactionID(transactionID)
                                .fileCreate(FileCreateTransactionBody.newBuilder()
                                        .keys(KeyList.newBuilder()
                                                .keys(
                                                        Key.newBuilder()
                                                                .ed25519(Bytes.wrap(new byte[32]))
                                                                .build(),
                                                        Key.newBuilder()
                                                                .ecdsaSecp256k1(Bytes.wrap(new byte[33]))
                                                                .build())
                                                .build())
                                        .build())
                                .build(),
                        1,
                        100000L,
                        509000000L,
                        200000L),
                new TestCase(
                        new FileUpdateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .transactionID(transactionID)
                                .fileUpdate(FileUpdateTransactionBody.newBuilder()
                                        .keys(KeyList.newBuilder()
                                                .keys(
                                                        Key.newBuilder()
                                                                .ed25519(Bytes.wrap(new byte[32]))
                                                                .build(),
                                                        Key.newBuilder()
                                                                .ecdsaSecp256k1(Bytes.wrap(new byte[33]))
                                                                .build())
                                                .build())
                                        .build())
                                .build(),
                        3,
                        2100000L,
                        509000000L,
                        4200000L),
                new TestCase(
                        new FileAppendFeeCalculator(),
                        TransactionBody.newBuilder()
                                .transactionID(transactionID)
                                .fileAppend(
                                        FileAppendTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        100000L,
                        499000000L,
                        200000L),
                new TestCase(
                        new FileSystemDeleteFeeCalculator(),
                        TransactionBody.newBuilder()
                                .transactionID(transactionID)
                                .systemDelete(
                                        SystemDeleteTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        0,
                        0,
                        0),
                new TestCase(
                        new FileSystemUndeleteFeeCalculator(),
                        TransactionBody.newBuilder()
                                .transactionID(transactionID)
                                .systemUndelete(SystemUndeleteTransactionBody.newBuilder()
                                        .build())
                                .build(),
                        1,
                        0,
                        0,
                        0));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Fee calculation for all ScheduleFeeCalculators")
    void testFeeCalculators(TestCase testCase) throws UnknownHederaFunctionality {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(testCase.numSignatures);
        when(feeContext.functionality()).thenReturn(functionOf(testCase.body()));
        lenient().when(feeContext.authorizer()).thenReturn(authorizer);
        lenient().when(feeContext.body()).thenReturn(testCase.body());
        lenient()
                .when(authorizer.hasPrivilegedAuthorization(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(SystemPrivilege.UNNECESSARY);

        final var result = feeCalculator.calculateTxFee(testCase.body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getNodeTotalTinycents()).isEqualTo(testCase.expectedNodeFee);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(testCase.expectedServiceFee);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(testCase.expectedNetworkFee);
    }

    @Test
    @DisplayName("FileUpdateFeeCalculator with AUTHORIZED privilege clears fees")
    void testFileUpdateWithPrivilegedAuthorizationClearsFees() throws UnknownHederaFunctionality {
        final var transactionID = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(1001).build())
                .build();
        final var fileUpdateBody = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .fileUpdate(FileUpdateTransactionBody.newBuilder()
                        .keys(KeyList.newBuilder()
                                .keys(Key.newBuilder()
                                        .ed25519(Bytes.wrap(new byte[32]))
                                        .build())
                                .build())
                        .build())
                .build();

        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
        when(feeContext.functionality()).thenReturn(HederaFunctionality.FILE_UPDATE);
        lenient().when(feeContext.authorizer()).thenReturn(authorizer);
        lenient().when(feeContext.body()).thenReturn(fileUpdateBody);
        lenient()
                .when(authorizer.hasPrivilegedAuthorization(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(SystemPrivilege.AUTHORIZED);

        final var result = feeCalculator.calculateTxFee(fileUpdateBody, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(0L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(0L);
    }

    @Test
    @DisplayName("FileUpdateFeeCalculator with UNAUTHORIZED privilege clears fees")
    void testFileUpdateWithUnauthorizedPrivilegedAuthorizationClearsFees() throws UnknownHederaFunctionality {
        final var transactionID = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(1001).build())
                .build();
        final var fileUpdateBody = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .fileUpdate(FileUpdateTransactionBody.newBuilder().build())
                .build();

        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
        when(feeContext.functionality()).thenReturn(HederaFunctionality.FILE_UPDATE);
        lenient().when(feeContext.authorizer()).thenReturn(authorizer);
        lenient().when(feeContext.body()).thenReturn(fileUpdateBody);
        lenient()
                .when(authorizer.hasPrivilegedAuthorization(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(SystemPrivilege.UNAUTHORIZED);

        final var result = feeCalculator.calculateTxFee(fileUpdateBody, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(0L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(0L);
    }

    @Test
    void testGetInfoQueryCalculator() {
        final var mockQueryContext = mock(QueryContext.class);
        final var query = Query.newBuilder().build();
        final var fileGetInfoFeeCalculator = new FileGetInfoFeeCalculator();
        final var feeResult = new FeeResult();

        fileGetInfoFeeCalculator.accumulateNodePayment(
                query, new SimpleFeeContextImpl(null, mockQueryContext), feeResult, createTestFeeSchedule());

        assertThat(feeResult.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getNetworkTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(6L);
    }

    @Test
    void testGetContentQueryCalculator() {
        final var mockQueryContext = mock(QueryContext.class);
        final var mockFileStore = mock(ReadableFileStoreImpl.class);
        when(mockFileStore.getFileLeaf(FileID.DEFAULT))
                .thenReturn(File.newBuilder()
                        .contents(Bytes.wrap(bytesWithLength(1234)))
                        .build());
        when(mockQueryContext.createStore(ReadableFileStore.class)).thenReturn(mockFileStore);

        final var query = Query.newBuilder()
                .fileGetContents(
                        FileGetContentsQuery.newBuilder().fileID(FileID.DEFAULT).build())
                .build();
        final var fileGetContentsFeeCalculator = new FileGetContentsFeeCalculator();
        final var feeResult = new FeeResult();

        fileGetContentsFeeCalculator.accumulateNodePayment(
                query, new SimpleFeeContextImpl(null, mockQueryContext), feeResult, createTestFeeSchedule());

        assertThat(feeResult.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getNetworkTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(2347L);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000),
                        makeExtraDef(Extra.KEYS, 10000000),
                        makeExtraDef(Extra.STATE_BYTES, 10))
                .services(makeService(
                        "ScheduleService",
                        makeServiceFee(
                                FILE_CREATE,
                                499000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.STATE_BYTES, 1000)),
                        makeServiceFee(
                                HederaFunctionality.FILE_APPEND,
                                499000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.STATE_BYTES, 1000)),
                        makeServiceFee(
                                HederaFunctionality.FILE_UPDATE,
                                499000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.STATE_BYTES, 1000)),
                        makeServiceFee(HederaFunctionality.FILE_DELETE, 69000000),
                        makeServiceFee(HederaFunctionality.FILE_GET_INFO, 6),
                        makeServiceFee(
                                HederaFunctionality.FILE_GET_CONTENTS, 7, makeExtraIncluded(Extra.STATE_BYTES, 1000)),
                        makeServiceFee(HederaFunctionality.SYSTEM_DELETE, 50000000),
                        makeServiceFee(HederaFunctionality.SYSTEM_UNDELETE, 50000000)))
                .build();
    }

    record TestCase(
            ServiceFeeCalculator calculator,
            TransactionBody body,
            int numSignatures,
            long expectedNodeFee,
            long expectedServiceFee,
            long expectedNetworkFee) {

        @Override
        public @NonNull String toString() {
            return calculator.getClass().getSimpleName() + " with " + numSignatures + " signatures";
        }
    }

    private static byte[] bytesWithLength(int length) {
        final var result = new byte[length];
        Arrays.fill(result, (byte) 'a');
        return result;
    }
}
