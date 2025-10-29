package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.*;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.hiero.hapi.support.fees.Extra.*;
import static org.hiero.hapi.support.fees.NetworkFee.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.token.CryptoGetAccountRecordsQuery;
import com.hedera.hapi.node.token.CryptoGetInfoQuery;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.NftRemoveAllowance;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoApproveAllowanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoCreateHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteAllowanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountInfoHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountRecordsHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookCallsFactory;
import com.hedera.node.app.service.token.impl.validators.ApproveAllowanceValidator;
import com.hedera.node.app.service.token.impl.validators.CryptoCreateValidator;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.service.token.impl.validators.DeleteAllowanceValidator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.InstantSource;

import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

@ExtendWith(MockitoExtension.class)
@DisplayName("Crypto Handler Fee Calculation Tests")
class CryptoHandlerFeeCalculationTest {

    @Mock
    private CryptoCreateValidator createValidator;

    @Mock
    private CryptoTransferValidator transferValidator;

    @Mock
    private ApproveAllowanceValidator approveAllowanceValidator;

    @Mock
    private DeleteAllowanceValidator deleteAllowanceValidator;

    @Mock
    private HookCallsFactory hookCallsFactory;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private CryptoOpsUsage cryptoOpsUsage;

    @Mock
    private InstantSource instantSource;

    @Mock
    private RecordCache recordCache;

    private CryptoCreateHandler createHandler;
    private CryptoTransferHandler transferHandler;
    private CryptoDeleteHandler deleteHandler;
    private CryptoApproveAllowanceHandler approveAllowanceHandler;
    private CryptoDeleteAllowanceHandler deleteAllowanceHandler;
    private CryptoGetAccountInfoHandler getAccountInfoHandler;
    private CryptoGetAccountRecordsHandler getAccountRecordsHandler;

    @BeforeEach
    void setUp() {
        createHandler = new CryptoCreateHandler(createValidator, null, entityIdFactory);
        transferHandler = new CryptoTransferHandler(transferValidator, hookCallsFactory, entityIdFactory);
        deleteHandler = new CryptoDeleteHandler();
        approveAllowanceHandler = new CryptoApproveAllowanceHandler(approveAllowanceValidator);
        deleteAllowanceHandler = new CryptoDeleteAllowanceHandler(deleteAllowanceValidator);
        getAccountInfoHandler = new CryptoGetAccountInfoHandler(cryptoOpsUsage, instantSource);
        getAccountRecordsHandler = new CryptoGetAccountRecordsHandler(recordCache);
    }

    @ParameterizedTest(name = "CryptoCreate: {0}")
    @MethodSource("cryptoCreateTestCases")
    @DisplayName("CryptoCreate handler fee calculations")
    void testCryptoCreateFeeCalculation(
            String description,
            CryptoCreateTransactionBody op,
            int numSignatures,
            long expectedFee) {
        // Arrange
        final var txBody = TransactionBody.newBuilder().cryptoCreateAccount(op).build();
        final var feeContext = createMockFeeContext(txBody, numSignatures);

        // Act
        final var result = createHandler.calculateFeeResult(feeContext);

        // Assert
        assertNotNull(result, description);
        assertEquals(expectedFee, result.total(), description);
    }

    @ParameterizedTest(name = "CryptoTransfer: {0}")
    @MethodSource("cryptoTransferTestCases")
    @DisplayName("CryptoTransfer handler fee calculations")
    void testCryptoTransferFeeCalculation(
            String description,
            CryptoTransferTransactionBody op,
            int numSignatures,
            ReadableAccountStore accountStore,
            ReadableTokenStore tokenStore,
            ReadableTokenRelationStore tokenRelStore,
            long expectedFee) {
        // Arrange
        final var txBody = TransactionBody.newBuilder().cryptoTransfer(op).build();
        final var feeContext = createMockFeeContextWithStores(
                txBody, numSignatures, accountStore, tokenStore, tokenRelStore);

        // Act
        final var result = transferHandler.calculateFeeResult(feeContext);

        // Assert
        assertNotNull(result, description);
        assertEquals(expectedFee, result.total(), description);
    }

    @ParameterizedTest(name = "CryptoDelete: {0}")
    @MethodSource("cryptoDeleteTestCases")
    @DisplayName("CryptoDelete handler fee calculations")
    void testCryptoDeleteFeeCalculation(
            String description,
            CryptoDeleteTransactionBody op,
            int numSignatures,
            long expectedFee) {
        // Arrange
        final var txBody = TransactionBody.newBuilder().cryptoDelete(op).build();
        final var feeContext = createMockFeeContext(txBody, numSignatures);

        // Act
        final var result = deleteHandler.calculateFeeResult(feeContext);

        // Assert
        assertNotNull(result, description);
        assertEquals(expectedFee, result.total(), description);
    }

    @ParameterizedTest(name = "CryptoApproveAllowance: {0}")
    @MethodSource("cryptoApproveAllowanceTestCases")
    @DisplayName("CryptoApproveAllowance handler fee calculations")
    void testCryptoApproveAllowanceFeeCalculation(
            String description,
            CryptoApproveAllowanceTransactionBody op,
            int numSignatures,
            long expectedFee) {
        // Arrange
        final var txBody = TransactionBody.newBuilder().cryptoApproveAllowance(op).build();
        final var feeContext = createMockFeeContext(txBody, numSignatures);

        // Act
        final var result = approveAllowanceHandler.calculateFeeResult(feeContext);

        // Assert
        assertNotNull(result, description);
        assertEquals(expectedFee, result.total(), description);
    }

    @ParameterizedTest(name = "CryptoDeleteAllowance: {0}")
    @MethodSource("cryptoDeleteAllowanceTestCases")
    @DisplayName("CryptoDeleteAllowance handler fee calculations")
    void testCryptoDeleteAllowanceFeeCalculation(
            String description,
            CryptoDeleteAllowanceTransactionBody op,
            int numSignatures,
            long expectedFee) {
        // Arrange
        final var txBody = TransactionBody.newBuilder().cryptoDeleteAllowance(op).build();
        final var feeContext = createMockFeeContext(txBody, numSignatures);

        // Act
        final var result = deleteAllowanceHandler.calculateFeeResult(feeContext);

        // Assert
        assertNotNull(result, description);
        assertEquals(expectedFee, result.total(), description);
    }

    @ParameterizedTest(name = "CryptoGetAccountInfo: {0}")
    @MethodSource("cryptoGetAccountInfoTestCases")
    @DisplayName("CryptoGetAccountInfo handler fee calculations")
    void testCryptoGetAccountInfoFeeCalculation(
            String description,
            CryptoGetInfoQuery op,
            long expectedFee) {
        // Arrange
        final var query = Query.newBuilder().cryptoGetInfo(op).build();
        final var queryContext = createMockQueryContext(query);

        // Act
        final var result = getAccountInfoHandler.computeFeeResult(queryContext);

        // Assert
        assertNotNull(result, description);
        assertEquals(expectedFee, result.total(), description);
    }

    @ParameterizedTest(name = "CryptoGetAccountRecords: {0}")
    @MethodSource("cryptoGetAccountRecordsTestCases")
    @DisplayName("CryptoGetAccountRecords handler fee calculations")
    void testCryptoGetAccountRecordsFeeCalculation(
            String description,
            CryptoGetAccountRecordsQuery op,
            long expectedFee) {
        // Arrange
        final var query = Query.newBuilder().cryptoGetAccountRecords(op).build();
        final var queryContext = createMockQueryContext(query);

        // Act
        final var result = getAccountRecordsHandler.computeFeeResult(queryContext);

        // Assert
        assertNotNull(result, description);
        assertEquals(expectedFee, result.total(), description);
    }

    static Stream<Arguments> cryptoCreateTestCases() {
        return Stream.of(
                Arguments.of(
                        "Create without key",
                        CryptoCreateTransactionBody.newBuilder()
                                .initialBalance(1000L)
                                .autoRenewPeriod(Duration.newBuilder().seconds(7776000L).build())
                                .build(),
                        1,
                        22L),
                Arguments.of(
                        "Create with key",
                        CryptoCreateTransactionBody.newBuilder()
                                .initialBalance(1000L)
                                .key(Key.newBuilder()
                                        .ed25519(Bytes.wrap(new byte[32]))
                                        .build())
                                .autoRenewPeriod(Duration.newBuilder().seconds(7776000L).build())
                                .build(),
                        1,
                        22L),
                Arguments.of(
                        "Create with 3 signatures",
                        CryptoCreateTransactionBody.newBuilder()
                                .initialBalance(1000L)
                                .key(Key.newBuilder()
                                        .ed25519(Bytes.wrap(new byte[32]))
                                        .build())
                                .autoRenewPeriod(Duration.newBuilder().seconds(7776000L).build())
                                .build(),
                        3,
                        120_000_022L) // 22 + (3-1)*60_000_000
        );
    }

    static Stream<Arguments> cryptoTransferTestCases() {
        return Stream.of(
                Arguments.of(
                        "Basic HBAR transfer (2 accounts)",
                        CryptoTransferTransactionBody.newBuilder()
                                .transfers(TransferList.newBuilder()
                                        .accountAmounts(
                                                AccountAmount.newBuilder()
                                                        .accountID(AccountID.newBuilder().accountNum(1001L).build())
                                                        .amount(-1000L)
                                                        .build(),
                                                AccountAmount.newBuilder()
                                                        .accountID(AccountID.newBuilder().accountNum(1002L).build())
                                                        .amount(1000L)
                                                        .build())
                                        .build())
                                .build(),
                        1,
                        createEmptyAccountStore(),
                        createEmptyTokenStore(),
                        createEmptyTokenRelStore(),
                        21L), // 18 + (2-1)*3
                Arguments.of(
                        "Transfer with fungible token",
                        CryptoTransferTransactionBody.newBuilder()
                                .transfers(TransferList.newBuilder()
                                        .accountAmounts(
                                                AccountAmount.newBuilder()
                                                        .accountID(AccountID.newBuilder().accountNum(1001L).build())
                                                        .amount(-1000L)
                                                        .build(),
                                                AccountAmount.newBuilder()
                                                        .accountID(AccountID.newBuilder().accountNum(1002L).build())
                                                        .amount(1000L)
                                                        .build())
                                        .build())
                                .tokenTransfers(TokenTransferList.newBuilder()
                                        .token(TokenID.newBuilder().tokenNum(2001L).build())
                                        .transfers(
                                                AccountAmount.newBuilder()
                                                        .accountID(AccountID.newBuilder().accountNum(1001L).build())
                                                        .amount(-100L)
                                                        .build(),
                                                AccountAmount.newBuilder()
                                                        .accountID(AccountID.newBuilder().accountNum(1002L).build())
                                                        .amount(100L)
                                                        .build())
                                        .build())
                                .build(),
                        1,
                        createEmptyAccountStore(),
                        createTokenStoreWithToken(2001L, false),
                        createEmptyTokenRelStore(),
                        21L), // 18 + (2-1)*3 accounts
                Arguments.of(
                        "Transfer with account creation (alias)",
                        CryptoTransferTransactionBody.newBuilder()
                                .transfers(TransferList.newBuilder()
                                        .accountAmounts(
                                                AccountAmount.newBuilder()
                                                        .accountID(AccountID.newBuilder().accountNum(1001L).build())
                                                        .amount(-2000L)
                                                        .build(),
                                                AccountAmount.newBuilder()
                                                        .accountID(AccountID.newBuilder()
                                                                .alias(Bytes.wrap(new byte[]{1, 2, 3}))
                                                                .build())
                                                        .amount(2000L)
                                                        .build())
                                        .build())
                                .build(),
                        1,
                        createAccountStoreWithNewAlias(),
                        createEmptyTokenStore(),
                        createEmptyTokenRelStore(),
                        24L) // 18 + (2-1)*3 accounts + 1*3 created account
        );
    }

    static Stream<Arguments> cryptoDeleteTestCases() {
        return Stream.of(
                Arguments.of(
                        "Delete with 1 signature",
                        CryptoDeleteTransactionBody.newBuilder()
                                .deleteAccountID(AccountID.newBuilder().accountNum(1001L).build())
                                .transferAccountID(AccountID.newBuilder().accountNum(1002L).build())
                                .build(),
                        1,
                        15L),
                Arguments.of(
                        "Delete with 2 signatures",
                        CryptoDeleteTransactionBody.newBuilder()
                                .deleteAccountID(AccountID.newBuilder().accountNum(1001L).build())
                                .transferAccountID(AccountID.newBuilder().accountNum(1002L).build())
                                .build(),
                        2,
                        60_000_015L) // 15 + (2-1)*60_000_000
        );
    }

    static Stream<Arguments> cryptoApproveAllowanceTestCases() {
        return Stream.of(
                Arguments.of(
                        "Approve 1 allowance",
                        CryptoApproveAllowanceTransactionBody.newBuilder()
                                .cryptoAllowances(CryptoAllowance.newBuilder()
                                        .owner(AccountID.newBuilder().accountNum(1001L).build())
                                        .spender(AccountID.newBuilder().accountNum(1002L).build())
                                        .amount(1000L)
                                        .build())
                                .build(),
                        1,
                        20L),
                Arguments.of(
                        "Approve 3 allowances",
                        CryptoApproveAllowanceTransactionBody.newBuilder()
                                .cryptoAllowances(
                                        CryptoAllowance.newBuilder()
                                                .owner(AccountID.newBuilder().accountNum(1001L).build())
                                                .spender(AccountID.newBuilder().accountNum(1002L).build())
                                                .amount(1000L)
                                                .build(),
                                        CryptoAllowance.newBuilder()
                                                .owner(AccountID.newBuilder().accountNum(1001L).build())
                                                .spender(AccountID.newBuilder().accountNum(1003L).build())
                                                .amount(2000L)
                                                .build(),
                                        CryptoAllowance.newBuilder()
                                                .owner(AccountID.newBuilder().accountNum(1001L).build())
                                                .spender(AccountID.newBuilder().accountNum(1004L).build())
                                                .amount(3000L)
                                                .build())
                                .build(),
                        1,
                        20L + 4000L) // 20 + (3-1)*2000
        );
    }

    static Stream<Arguments> cryptoDeleteAllowanceTestCases() {
        return Stream.of(
                Arguments.of(
                        "Delete 1 allowance",
                        CryptoDeleteAllowanceTransactionBody.newBuilder()
                                .nftAllowances(NftRemoveAllowance.newBuilder()
                                        .owner(AccountID.newBuilder().accountNum(1001L).build())
                                        .serialNumbers(1L)
                                        .build())
                                .build(),
                        1,
                        15L),
                Arguments.of(
                        "Delete 2 allowances",
                        CryptoDeleteAllowanceTransactionBody.newBuilder()
                                .nftAllowances(
                                        NftRemoveAllowance.newBuilder()
                                                .owner(AccountID.newBuilder().accountNum(1001L).build())
                                                .serialNumbers(1L)
                                                .build(),
                                        NftRemoveAllowance.newBuilder()
                                                .owner(AccountID.newBuilder().accountNum(1001L).build())
                                                .serialNumbers(2L)
                                                .build())
                                .build(),
                        1,
                        15L + 2000L) // 15 + (2-1)*2000
        );
    }

    static Stream<Arguments> cryptoGetAccountInfoTestCases() {
        return Stream.of(
                Arguments.of(
                        "Get account info",
                        CryptoGetInfoQuery.newBuilder()
                                .accountID(AccountID.newBuilder().accountNum(1001L).build())
                                .header(QueryHeader.DEFAULT)
                                .build(),
                        10L)
        );
    }

    static Stream<Arguments> cryptoGetAccountRecordsTestCases() {
        return Stream.of(
                Arguments.of(
                        "Get account records",
                        CryptoGetAccountRecordsQuery.newBuilder()
                                .accountID(AccountID.newBuilder().accountNum(1001L).build())
                                .header(QueryHeader.DEFAULT)
                                .build(),
                        15L)
        );
    }

    // ========== Helper Methods ==========

    private FeeContext createMockFeeContext(TransactionBody txBody, int numSignatures) {
        final var feeContext = mock(FeeContext.class);
        final var feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        final var feeCalculator = mock(FeeCalculator.class);
        final var configuration = mock(Configuration.class);
        final var hederaConfig = mock(HederaConfig.class);
        final var entitiesConfig = mock(EntitiesConfig.class);
        lenient().when(feeContext.body()).thenReturn(txBody);
        lenient().when(feeContext.numTxnSignatures()).thenReturn(numSignatures);
        lenient().when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        lenient().when(feeContext.configuration()).thenReturn(configuration);
        lenient().when(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).thenReturn(feeCalculator);
        lenient().when(feeCalculator.getSimpleFeesSchedule()).thenReturn(createTestFeeSchedule());
        lenient().when(configuration.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
        lenient().when(configuration.getConfigData(EntitiesConfig.class)).thenReturn(entitiesConfig);
        lenient().when(hederaConfig.shard()).thenReturn(0L);
        lenient().when(hederaConfig.realm()).thenReturn(0L);
        lenient().when(entitiesConfig.unlimitedAutoAssociationsEnabled()).thenReturn(false);

        return feeContext;
    }

    private FeeContext createMockFeeContextWithStores(
            TransactionBody txBody,
            int numSignatures,
            ReadableAccountStore accountStore,
            ReadableTokenStore tokenStore,
            ReadableTokenRelationStore tokenRelStore) {
        final var feeContext = createMockFeeContext(txBody, numSignatures);
        lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
        lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);
        return feeContext;
    }

    private QueryContext createMockQueryContext(Query query) {
        final var queryContext = mock(QueryContext.class);
        final var feeCalculator = mock(FeeCalculator.class);
        final var feeSchedule = createTestFeeSchedule();

        lenient().when(queryContext.query()).thenReturn(query);
        lenient().when(queryContext.feeCalculator()).thenReturn(feeCalculator);
        lenient().when(feeCalculator.getSimpleFeesSchedule()).thenReturn(feeSchedule);

        return queryContext;
    }

    private static ReadableAccountStore createEmptyAccountStore() {
        final var store = mock(ReadableAccountStore.class);
        return store;
    }

    private static ReadableAccountStore createAccountStoreWithNewAlias() {
        final var store = mock(ReadableAccountStore.class);
        // Return null for alias lookup, simulating a new account
        when(store.getAccountIDByAlias(anyLong(), anyLong(), any())).thenReturn(null);
        return store;
    }

    private static ReadableTokenStore createEmptyTokenStore() {
        return mock(ReadableTokenStore.class);
    }

    private static ReadableTokenStore createTokenStoreWithToken(long tokenNum, boolean hasCustomFees) {
        final var store = mock(ReadableTokenStore.class);
        final var token = Token.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(tokenNum).build())
                .build();
        when(store.get(any(TokenID.class))).thenReturn(token);
        return store;
    }

    private static ReadableTokenRelationStore createEmptyTokenRelStore() {
        return mock(ReadableTokenRelationStore.class);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(SIGNATURES, 60_000_000),
                        makeExtraDef(KEYS, 2_200_000),
                        makeExtraDef(ACCOUNTS, 3),
                        makeExtraDef(STANDARD_FUNGIBLE_TOKENS, 3),
                        makeExtraDef(STANDARD_NON_FUNGIBLE_TOKENS, 3),
                        makeExtraDef(NFT_SERIALS, 1),
                        makeExtraDef(CUSTOM_FEE_FUNGIBLE_TOKENS, 3),
                        makeExtraDef(CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 3),
                        makeExtraDef(CREATED_AUTO_ASSOCIATIONS, 3),
                        makeExtraDef(CREATED_ACCOUNTS, 3),
                        makeExtraDef(ALLOWANCES, 2000),
                        makeExtraDef(BYTES, 300))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(0)
                        .extras(makeExtraIncluded(SIGNATURES, 10))
                        .build())
                .network(DEFAULT.copyBuilder().multiplier(2).build())
                .services(makeService(
                        "Crypto",
                        makeServiceFee(
                                CRYPTO_CREATE,
                                22,
                                makeExtraIncluded(SIGNATURES, 1),
                                makeExtraIncluded(KEYS, 1)),
                        makeServiceFee(
                                CRYPTO_UPDATE,
                                22,
                                makeExtraIncluded(SIGNATURES, 1),
                                makeExtraIncluded(KEYS, 1)),
                        makeServiceFee(
                                CRYPTO_TRANSFER,
                                18,
                                makeExtraIncluded(SIGNATURES, 1),
                                makeExtraIncluded(ACCOUNTS, 1),
                                makeExtraIncluded(STANDARD_FUNGIBLE_TOKENS, 1),
                                makeExtraIncluded(STANDARD_NON_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(NFT_SERIALS, 0),
                                makeExtraIncluded(CUSTOM_FEE_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(CREATED_AUTO_ASSOCIATIONS, 0),
                                makeExtraIncluded(CREATED_ACCOUNTS, 0)),
                        makeServiceFee(
                                CRYPTO_DELETE,
                                15,
                                makeExtraIncluded(SIGNATURES, 1)),
                        makeServiceFee(
                                CRYPTO_APPROVE_ALLOWANCE,
                                20,
                                makeExtraIncluded(SIGNATURES, 1),
                                makeExtraIncluded(ALLOWANCES, 1)),
                        makeServiceFee(
                                CRYPTO_DELETE_ALLOWANCE,
                                15,
                                makeExtraIncluded(SIGNATURES, 1),
                                makeExtraIncluded(ALLOWANCES, 1)),
                        makeServiceFee(
                                CRYPTO_GET_INFO,
                                10,
                                makeExtraIncluded(SIGNATURES, 1)),
                        makeServiceFee(
                                CRYPTO_GET_ACCOUNT_RECORDS,
                                15,
                                makeExtraIncluded(SIGNATURES, 1))))
                .build();
    }
}
