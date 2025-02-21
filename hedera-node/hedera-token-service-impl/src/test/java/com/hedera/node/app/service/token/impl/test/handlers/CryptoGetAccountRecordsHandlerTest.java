// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.file.FileGetInfoQuery;
import com.hedera.hapi.node.token.CryptoGetAccountRecordsQuery;
import com.hedera.hapi.node.token.CryptoGetAccountRecordsResponse;
import com.hedera.hapi.node.token.CryptoGetStakersQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountRecordsHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.fees.FakeFeeCalculator;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoGetAccountRecordsHandlerTest extends CryptoHandlerTestBase {
    private static final int COST_25 = 25;

    @Mock(strictness = LENIENT)
    private QueryContext context;

    @Mock(strictness = LENIENT)
    private RecordCache recordCache;

    private CryptoGetAccountRecordsHandler subject;

    @BeforeEach
    public void setUp() {
        super.setUp();
        subject = new CryptoGetAccountRecordsHandler(recordCache);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void extractHeaderThrowsOnNullArg() {
        Assertions.assertThatThrownBy(() -> subject.extractHeader(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void extractHeaderHasAccountRecordsQuery() {
        final var queryHeader = QueryHeader.newBuilder()
                .payment((Transaction) null)
                .responseType(COST_ANSWER)
                .build();
        final var acctRecordsQuery = CryptoGetAccountRecordsQuery.newBuilder()
                .accountID(id)
                .header(queryHeader)
                .build();

        final var result = subject.extractHeader(
                Query.newBuilder().cryptoGetAccountRecords(acctRecordsQuery).build());
        Assertions.assertThat(result).isNotNull().isEqualTo(queryHeader);
    }

    @Test
    void extractHeaderHasNoAccountRecordsQuery() {
        final var query = Query.newBuilder()
                .cryptoGetProxyStakers(CryptoGetStakersQuery.DEFAULT)
                .build();
        Assertions.assertThatThrownBy(() -> subject.extractHeader(query)).isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void createEmptyResponseThrowsForNullArg() {
        Assertions.assertThatThrownBy(() -> subject.createEmptyResponse(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void createEmptyResponseSuccess() {
        final var headerInput = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.DUPLICATE_TRANSACTION)
                .cost(10)
                .build();

        final var result = subject.createEmptyResponse(headerInput);
        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.cryptoGetAccountRecords())
                .isEqualTo(CryptoGetAccountRecordsResponse.newBuilder()
                        .header(headerInput)
                        .build());
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void validatesThrowsOnNullArg() {
        Assertions.assertThatThrownBy(() -> subject.validate(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void validatesNoAccountRecordsQuery() {
        given(context.query())
                .willReturn(Query.newBuilder()
                        // Intentionally the wrong query type
                        .fileGetInfo(FileGetInfoQuery.DEFAULT)
                        .build());

        Assertions.assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void validatesAccountDoesntExist() {
        refreshStoresWithCurrentTokenOnlyInReadable();
        mockQueryContext(
                idFactory.newAccountId(987),
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build());

        Assertions.assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    void validatesAccountIsDeleted() {
        deleteAccount = deleteAccount.copyBuilder().deleted(true).build();
        refreshStoresWithCurrentTokenOnlyInReadable();
        mockQueryContext(
                deleteAccountId,
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build());

        Assertions.assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.ACCOUNT_DELETED));
    }

    @Test
    void validatesAccountIsSmartContract() {
        account = account.copyBuilder().smartContract(true).build();
        refreshStoresWithCurrentTokenOnlyInReadable();
        mockQueryContext(
                id, QueryHeader.newBuilder().responseType(ANSWER_STATE_PROOF).build());

        Assertions.assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    void validateSucceeds() {
        refreshStoresWithCurrentTokenOnlyInReadable();
        mockQueryContext(
                id,
                QueryHeader.newBuilder()
                        .responseType(ResponseType.COST_ANSWER_STATE_PROOF)
                        .build());

        Assertions.assertThatNoException().isThrownBy(() -> subject.validate(context));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void findResponseThrowsOnNullArgs() {
        Assertions.assertThatThrownBy(() -> subject.findResponse(null, ResponseHeader.DEFAULT))
                .isInstanceOf(NullPointerException.class);

        Assertions.assertThatThrownBy(() -> subject.findResponse(context, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findResponseHasNoGetAccountRecordsQuery() {
        given(context.query())
                .willReturn(Query.newBuilder()
                        .cryptoGetProxyStakers(CryptoGetStakersQuery.DEFAULT)
                        .build());
        final var headerInput = okResponseHeader();

        Assertions.assertThatThrownBy(() -> subject.findResponse(context, headerInput))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findResponseHasNonOkResponseHeader() {
        mockQueryContext(id, QueryHeader.newBuilder().responseType(ANSWER_ONLY).build());
        mockNonEmptyRecords();
        final var notOkResponseInput = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(INVALID_TRANSACTION)
                .cost(COST_25)
                .responseType(ANSWER_ONLY)
                .build();

        final var result = subject.findResponse(context, notOkResponseInput);
        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.cryptoGetAccountRecords()).isNotNull();
        // Check the returned header
        final var header = result.cryptoGetAccountRecords().header();
        Assertions.assertThat(header).isNotNull();
        Assertions.assertThat(header.cost()).isEqualTo(COST_25);
        Assertions.assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(INVALID_TRANSACTION);
        // Make sure no data is populated
        Assertions.assertThat(result.cryptoGetAccountRecords().accountID()).isNull();
        Assertions.assertThat(result.cryptoGetAccountRecords().records()).isEmpty();
    }

    @Test
    void findResponseForCostOnly() {
        mockQueryContext(id, QueryHeader.newBuilder().responseType(COST_ANSWER).build());
        mockNonEmptyRecords();
        final var costHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(OK)
                .cost(COST_25)
                .responseType(COST_ANSWER)
                .build();

        final var result = subject.findResponse(context, costHeader);
        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.cryptoGetAccountRecords()).isNotNull();
        // Check the returned header
        final var header = result.cryptoGetAccountRecords().header();
        Assertions.assertThat(header).isNotNull();
        Assertions.assertThat(header.responseType()).isEqualTo(COST_ANSWER);
        Assertions.assertThat(header.cost()).isEqualTo(COST_25);
        Assertions.assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(OK);
        Assertions.assertThat(result.cryptoGetAccountRecords().accountID()).isEqualTo(id);
        // Make sure no records are returned
        Assertions.assertThat(result.cryptoGetAccountRecords().records()).isEmpty();
    }

    @Test
    void verifyFeeComputation() {
        mockQueryContext(id, QueryHeader.newBuilder().responseType(COST_ANSWER).build());
        // setup the readable store
        given(context.createStore(ReadableAccountStore.class)).willReturn(readableStore);
        final ResponseHeader.Builder testHeaderBuilder = ResponseHeader.newBuilder();
        testHeaderBuilder.nodeTransactionPrecheckCode(ResponseCodeEnum.OK);
        testHeaderBuilder.responseType(ResponseType.COST_ANSWER);

        final FeeCalculator feeSpy = Mockito.spy(new FakeFeeCalculator());
        given(context.feeCalculator()).willReturn(feeSpy);

        // validate a schedule that is present in state
        given(context.query())
                .willReturn(Query.newBuilder()
                        .cryptoGetProxyStakers(CryptoGetStakersQuery.DEFAULT)
                        .cryptoGetAccountRecords(newAcctRecordsQuery(id).build())
                        .build());
        Fees actual = subject.computeFees(context);
        assertThat(actual.networkFee()).isEqualTo(0L);
        assertThat(actual.nodeFee()).isEqualTo(0L);
        assertThat(actual.serviceFee()).isEqualTo(0L);
        assertThat(actual.totalFee()).isEqualTo(0L);
        verify(feeSpy).legacyCalculate(any());
    }

    @CsvSource({"ANSWER_ONLY", "COST_ANSWER_STATE_PROOF", "ANSWER_STATE_PROOF"})
    @ParameterizedTest()
    void findResponseWithNonCostResponseTypes(String responseType) {
        // This is a success case (which is the same for every response type except COST_ANSWER)

        final var cost50 = 50;
        final var parsedResponseType = ResponseType.fromString(responseType);
        mockQueryContext(
                id, QueryHeader.newBuilder().responseType(parsedResponseType).build());
        mockNonEmptyRecords();
        // Inject the response type param into the header here
        final var costHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(OK)
                .cost(cost50)
                .responseType(parsedResponseType)
                .build();

        final var result = subject.findResponse(context, costHeader);
        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.cryptoGetAccountRecords()).isNotNull();
        // Check the returned header
        final var header = result.cryptoGetAccountRecords().header();
        Assertions.assertThat(header).isNotNull();
        Assertions.assertThat(header.responseType()).isEqualTo(parsedResponseType);
        Assertions.assertThat(header.cost()).isEqualTo(cost50);
        Assertions.assertThat(header.nodeTransactionPrecheckCode()).isEqualTo(OK);
        Assertions.assertThat(header.stateProof()).isEqualTo(Bytes.EMPTY); // not supported
        Assertions.assertThat(result.cryptoGetAccountRecords().accountID()).isEqualTo(id);
        // Make sure the appropriate records are returned
        Assertions.assertThat(result.cryptoGetAccountRecords().records()).hasSize(2);
    }

    private ResponseHeader okResponseHeader() {
        return ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(OK)
                .cost(25)
                .responseType(ANSWER_ONLY)
                .build();
    }

    private void mockNonEmptyRecords() {
        given(recordCache.getRecords(id))
                .willReturn(List.of(mock(TransactionRecord.class), mock(TransactionRecord.class)));
    }

    private void mockQueryContext(final AccountID accountId, final QueryHeader header) {
        given(context.createStore(ReadableAccountStore.class)).willReturn(readableStore);

        given(context.query())
                .willReturn(Query.newBuilder()
                        .cryptoGetAccountRecords(
                                newAcctRecordsQuery(accountId).header(header).build())
                        .build());
    }

    private CryptoGetAccountRecordsQuery.Builder newAcctRecordsQuery(final AccountID accountId) {
        return CryptoGetAccountRecordsQuery.newBuilder().accountID(accountId);
    }
}
