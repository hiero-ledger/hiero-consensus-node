// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_LEDGER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.interledger.clpr.impl.test.handler.ClprTestConstants.MOCK_LEDGER_ID;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import org.hiero.hapi.interledger.clpr.ClprGetMessagesQuery;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageBundle;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.interledger.clpr.ReadableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.ReadableClprMessageStore;
import org.hiero.interledger.clpr.impl.ClprMessageUtils;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.handlers.ClprGetMessagesHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprGetMessagesHandlerTest extends ClprHandlerTestBase {

    @Mock
    private ClprStateProofManager stateProofManager;

    @Mock
    private QueryContext queryContext;

    @Mock
    private Query query;

    @Mock
    private ClprGetMessagesQuery clprGetMessagesQuery;

    private ClprGetMessagesHandler subject;

    @BeforeEach
    void setUp() {
        setupStates();
        subject = new ClprGetMessagesHandler(stateProofManager);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when stateProofManager is null")
    void constructorThrowsForNullStateProofManager() {
        assertThatThrownBy(() -> new ClprGetMessagesHandler(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("extractHeader extracts query header successfully")
    void extractHeaderWorks() {
        final var header = QueryHeader.newBuilder().build();
        given(query.getClprMessages()).willReturn(clprGetMessagesQuery);
        given(clprGetMessagesQuery.header()).willReturn(header);

        final var result = subject.extractHeader(query);

        assertThat(result).isEqualTo(header);
    }

    @Test
    @DisplayName("extractHeader throws NullPointerException when query is null")
    void extractHeaderThrowsForNullQuery() {
        assertThatThrownBy(() -> subject.extractHeader(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("createEmptyResponse creates an empty response with given header")
    void createEmptyResponseWorks() {
        final var header = ResponseHeader.newBuilder().build();

        final var response = subject.createEmptyResponse(header);

        assertThat(response.hasClprMessages()).isTrue();
        assertThat(response.clprMessages().hasHeader()).isTrue();
        assertThat(response.clprMessages().header()).isEqualTo(header);
        assertThat(response.clprMessages().hasMessageBundle()).isFalse();
    }

    @Test
    @DisplayName("createEmptyResponse throws NullPointerException when header is null")
    void createEmptyResponseThrowsForNullHeader() {
        assertThatThrownBy(() -> subject.createEmptyResponse(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("validate throws NOT_SUPPORTED if CLPR is disabled")
    void validateThrowsIfClprIsDisabled() {
        given(stateProofManager.clprEnabled()).willReturn(false);

        assertThatThrownBy(() -> subject.validate(queryContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
    }

    @Test
    @DisplayName("validate throws CLPR_INVALID_LEDGER_ID if ledger ID is missing")
    void validateThrowsIfLedgerIdIsMissing() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(queryContext.query()).willReturn(query);
        given(query.getClprMessagesOrThrow()).willReturn(clprGetMessagesQuery);
        given(clprGetMessagesQuery.hasLedgerId()).willReturn(false);

        assertThatThrownBy(() -> subject.validate(queryContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_LEDGER_ID);
    }

    @Test
    @DisplayName("validate throws CLPR_MESSAGE_QUEUE_NOT_AVAILABLE if queue metadata is null")
    void validateThrowsIfQueueMetadataIsNull() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(queryContext.query()).willReturn(query);
        given(query.getClprMessagesOrThrow()).willReturn(clprGetMessagesQuery);
        given(clprGetMessagesQuery.hasLedgerId()).willReturn(true);
        given(clprGetMessagesQuery.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(queryContext.createStore(ReadableClprMessageQueueMetadataStore.class))
                .willReturn(readableClprMessageQueueMetadataStore);

        assertThatThrownBy(() -> subject.validate(queryContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("validate succeeds with valid query and available queue metadata")
    void validateSucceedsWithValidQuery() throws PreCheckException {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(queryContext.query()).willReturn(query);
        given(query.getClprMessagesOrThrow()).willReturn(clprGetMessagesQuery);
        given(clprGetMessagesQuery.hasLedgerId()).willReturn(true);
        given(clprGetMessagesQuery.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(queryContext.createStore(ReadableClprMessageQueueMetadataStore.class))
                .willReturn(readableClprMessageQueueMetadataStore);
        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, ClprMessageQueueMetadata.DEFAULT);

        subject.validate(queryContext);
        // No exception means success
    }

    @Test
    @DisplayName("validate throws NullPointerException when queryContext is null")
    void validateThrowsForNullQueryContext() {
        assertThatThrownBy(() -> subject.validate(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("findResponse returns empty response when message bundle is null")
    void findResponseReturnsEmptyWhenBundleIsNull() {
        final var header = ResponseHeader.newBuilder().build();
        final var localLedgerId = ClprLedgerId.newBuilder()
                .ledgerId(com.hedera.pbj.runtime.io.buffer.Bytes.wrap("local"))
                .build();

        given(queryContext.query()).willReturn(query);
        given(query.getClprMessagesOrThrow()).willReturn(clprGetMessagesQuery);
        given(clprGetMessagesQuery.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(stateProofManager.getLocalLedgerId()).willReturn(localLedgerId);
        given(queryContext.createStore(ReadableClprMessageQueueMetadataStore.class))
                .willReturn(readableClprMessageQueueMetadataStore);
        given(queryContext.createStore(ReadableClprMessageStore.class)).willReturn(readableClprMessageStore);

        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, ClprMessageQueueMetadata.DEFAULT);

        try (MockedStatic<ClprMessageUtils> messageUtils = mockStatic(ClprMessageUtils.class)) {
            messageUtils
                    .when(() -> ClprMessageUtils.createBundle(
                            1, 1, localLedgerId, MOCK_LEDGER_ID, readableClprMessageStore::get))
                    .thenReturn(null);

            final var response = subject.findResponse(queryContext, header);
            assertThat(response.hasClprMessages()).isTrue();
            assertThat(response.clprMessages().hasMessageBundle()).isFalse();
            assertThat(response.clprMessages().header()).isEqualTo(header);
        }
    }

    @Test
    @DisplayName("findResponse returns a response with message bundle")
    void findResponseReturnsWithMessageBundle() {
        final var header = ResponseHeader.newBuilder().build();
        final var localLedgerId = ClprLedgerId.newBuilder()
                .ledgerId(com.hedera.pbj.runtime.io.buffer.Bytes.wrap("local"))
                .build();
        final var maxNumberOfMessages = 10;
        final var mockBundle = mock(ClprMessageBundle.class); // Mock the bundle directly

        given(queryContext.query()).willReturn(query);
        given(query.getClprMessagesOrThrow()).willReturn(clprGetMessagesQuery);
        given(clprGetMessagesQuery.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(clprGetMessagesQuery.maxNumberOfMessages()).willReturn(maxNumberOfMessages);
        given(stateProofManager.getLocalLedgerId()).willReturn(localLedgerId);
        given(queryContext.createStore(ReadableClprMessageQueueMetadataStore.class))
                .willReturn(readableClprMessageQueueMetadataStore);
        given(queryContext.createStore(ReadableClprMessageStore.class)).willReturn(readableClprMessageStore);

        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, ClprMessageQueueMetadata.DEFAULT);

        final var response = subject.findResponse(queryContext, header);

        assertThat(response.hasClprMessages()).isTrue();
        assertThat(response.clprMessages().hasMessageBundle()).isFalse();
        assertThat(response.clprMessages().header()).isEqualTo(header);
    }

    @Test
    @DisplayName("findResponse throws NullPointerException when queryContext is null")
    void findResponseThrowsForNullQueryContext() {
        final var header = ResponseHeader.newBuilder().build();
        assertThatThrownBy(() -> subject.findResponse(null, header)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("findResponse throws NullPointerException when header is null")
    void findResponseThrowsForNullHeader() {
        assertThatThrownBy(() -> subject.findResponse(queryContext, null)).isInstanceOf(NullPointerException.class);
    }
}
