// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.file.FileGetContentsQuery;
import com.hedera.hapi.node.file.FileGetContentsResponse;
import com.hedera.hapi.node.file.FileGetContentsResponse.FileContents;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.utils.fee.FileFeeBuilder;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.ReadableFileStoreImpl;
import com.hedera.node.app.service.file.impl.handlers.FileGetContentsHandler;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.file.impl.test.FileTestBase;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileGetContentsHandlerTest extends FileTestBase {

    @Mock
    private QueryContext context;

    @Mock
    private FileFeeBuilder usageEstimator;

    @Mock
    private V0490FileSchema genesisSchema;

    private FileGetContentsHandler subject;

    @BeforeEach
    void setUp() {
        subject = new FileGetContentsHandler(usageEstimator, genesisSchema);
    }

    @Test
    void extractsHeader() {
        final var query = createGetFileContentQuery(fileId);
        final var header = subject.extractHeader(query);
        final var op = query.fileGetContentsOrThrow();
        assertEquals(op.header(), header);
    }

    @Test
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = subject.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .fileGetContents(FileGetContentsResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    void requiresPayment() {
        assertTrue(subject.requiresNodePayment(ResponseType.ANSWER_ONLY));
        assertTrue(subject.requiresNodePayment(ResponseType.ANSWER_STATE_PROOF));
        assertFalse(subject.requiresNodePayment(ResponseType.COST_ANSWER));
        assertFalse(subject.requiresNodePayment(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    void needsAnswerOnlyCostForCostAnswer() {
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.ANSWER_ONLY));
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.ANSWER_STATE_PROOF));
        assertTrue(subject.needsAnswerOnlyCost(ResponseType.COST_ANSWER));
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    void validatesQueryWhenValidFile() {
        givenValidFile();

        final var query = createGetFileContentQuery(fileId);
        given(context.query()).willReturn(query);

        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void validatesQueryIfInvalidFile() {
        final var query = createGetFileContentQuery();
        when(context.query()).thenReturn(query);

        assertThrowsPreCheck(() -> subject.validate(context), INVALID_FILE_ID);
    }

    @Test
    void returnsGenesisExchangeRatesIfMissing() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        given(genesisSchema.genesisExchangeRates(DEFAULT_CONFIG)).willReturn(contentsBytes);

        final var query = createGetFileContentQuery(
                DEFAULT_CONFIG.getConfigData(FilesConfig.class).exchangeRates());
        given(context.query()).willReturn(query);
        when(context.createStore(ReadableFileStore.class)).thenReturn(readableStore);

        final var response = subject.findResponse(context, ResponseHeader.DEFAULT);
        assertSame(
                contentsBytes,
                response.fileGetContentsOrThrow().fileContentsOrThrow().contents());
    }

    @Test
    void returnsGenesisFeeSchedulesIfMissing() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        given(genesisSchema.genesisFeeSchedules(DEFAULT_CONFIG)).willReturn(contentsBytes);

        final var query = createGetFileContentQuery(
                DEFAULT_CONFIG.getConfigData(FilesConfig.class).feeSchedules());
        given(context.query()).willReturn(query);
        when(context.createStore(ReadableFileStore.class)).thenReturn(readableStore);

        final var response = subject.findResponse(context, ResponseHeader.DEFAULT);
        assertSame(
                contentsBytes,
                response.fileGetContentsOrThrow().fileContentsOrThrow().contents());
    }

    @Test
    void returnsGenesisSimpleFeeSchedulesIfMissing() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        given(genesisSchema.genesisSimpleFeesSchedules(DEFAULT_CONFIG)).willReturn(contentsBytes);

        final var query = createGetFileContentQuery(
                DEFAULT_CONFIG.getConfigData(FilesConfig.class).simpleFeesSchedules());
        given(context.query()).willReturn(query);
        when(context.createStore(ReadableFileStore.class)).thenReturn(readableStore);

        final var response = subject.findResponse(context, ResponseHeader.DEFAULT);
        assertSame(
                contentsBytes,
                response.fileGetContentsOrThrow().fileContentsOrThrow().contents());
    }

    @Test
    void validatesQueryEvenWhenFileDeletedInState() {
        givenValidFile(true);
        readableFileState = readableFileState();
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(readableFileState);
        readableStore = new ReadableFileStoreImpl(readableStates, readableEntityCounters);

        final var query = createGetFileContentQuery(fileId);
        when(context.query()).thenReturn(query);

        assertDoesNotThrow(() -> subject.validate(context));
    }

    @Test
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();

        final var query = createGetFileContentQuery(fileId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableFileStore.class)).thenReturn(readableStore);

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.fileGetContentsOrThrow();
        assertEquals(ResponseCodeEnum.FAIL_FEE, op.header().nodeTransactionPrecheckCode());
        assertNull(op.fileContents());
    }

    @Test
    void getsResponseIfOkResponse() {
        givenValidFile();
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedContent = getExpectedContent();
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        final var query = createGetFileContentQueryFromEntityId(fileId.fileNum());
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableFileStore.class)).thenReturn(readableStore);

        final var response = subject.findResponse(context, responseHeader);
        final var fileContentResponse = response.fileGetContentsOrThrow();
        assertEquals(ResponseCodeEnum.OK, fileContentResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedContent, fileContentResponse.fileContents());
    }

    @Test
    void getsResponseIfInvalidFileID() {
        givenValidFile();
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();

        final var query = createGetFileContentQuery(fileIdNotExist);
        when(context.query()).thenReturn(query);
        when(context.configuration()).thenReturn(DEFAULT_CONFIG);
        when(context.createStore(ReadableFileStore.class)).thenReturn(readableStore);

        final var response = subject.findResponse(context, responseHeader);
        final var fileContentResponse = response.fileGetContentsOrThrow();
        assertEquals(
                ResponseCodeEnum.INVALID_FILE_ID, fileContentResponse.header().nodeTransactionPrecheckCode());
    }

    private FileContents getExpectedContent() {
        return FileContents.newBuilder()
                .contents(Bytes.wrap(contents))
                .fileID(fileId)
                .build();
    }

    private Query createGetFileContentQuery(final FileID fileId) {
        return createGetFileContentQuery(fileId.fileNum());
    }

    private Query createGetFileContentQuery(long fileNum) {
        final var fileId = idFactory.newFileId(fileNum);
        final var data = FileGetContentsQuery.newBuilder()
                .fileID(fileId)
                .header(QueryHeader.newBuilder().payment(Transaction.DEFAULT).build())
                .build();

        return Query.newBuilder().fileGetContents(data).build();
    }

    private Query createGetFileContentQueryFromEntityId(long fileNum) {

        final FileID fileID = idFactory.newFileId(fileNum);
        final var data = FileGetContentsQuery.newBuilder()
                .fileID(fileID)
                .header(QueryHeader.newBuilder().payment(Transaction.DEFAULT).build())
                .build();

        return Query.newBuilder().fileGetContents(data).build();
    }

    private Query createGetFileContentQuery() {
        final var data = FileGetContentsQuery.newBuilder()
                .header(QueryHeader.newBuilder().payment(Transaction.DEFAULT).build())
                .build();

        return Query.newBuilder().fileGetContents(data).build();
    }
}
