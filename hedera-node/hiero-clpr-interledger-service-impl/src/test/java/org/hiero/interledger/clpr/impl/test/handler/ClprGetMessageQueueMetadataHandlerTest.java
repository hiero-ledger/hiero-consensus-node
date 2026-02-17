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
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import org.hiero.hapi.interledger.clpr.ClprGetMessageQueueMetadataQuery;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.interledger.clpr.ReadableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.handlers.ClprGetMessageQueueMetadataHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprGetMessageQueueMetadataHandlerTest extends ClprHandlerTestBase {

    @Mock
    private ClprStateProofManager stateProofManager;

    @Mock
    private QueryContext queryContext;

    @Mock
    private Query query;

    @Mock
    private ClprGetMessageQueueMetadataQuery clprGetMessageQueueMetadataQuery;

    @Mock
    private ClprMessageQueueMetadata clprMessageQueueMetadata;

    private ClprGetMessageQueueMetadataHandler subject;

    @BeforeEach
    void setUp() {
        setupStates();
        subject = new ClprGetMessageQueueMetadataHandler(stateProofManager);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when stateProofManager is null")
    void constructorThrowsForNullStateProofManager() {
        assertThatThrownBy(() -> new ClprGetMessageQueueMetadataHandler(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("extractHeader extracts query header successfully")
    void extractHeaderWorks() {
        final var header = QueryHeader.newBuilder().build();
        given(query.getClprMessageQueueMetadata()).willReturn(clprGetMessageQueueMetadataQuery);
        given(clprGetMessageQueueMetadataQuery.header()).willReturn(header);

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

        assertThat(response.hasClprMessageQueueMetadata()).isTrue();
        assertThat(response.clprMessageQueueMetadata().hasHeader()).isTrue();
        assertThat(response.clprMessageQueueMetadata().header()).isEqualTo(header);
        assertThat(response.clprMessageQueueMetadata().hasMessageQueueMetadataProof())
                .isFalse();
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
        given(query.getClprMessageQueueMetadataOrThrow()).willReturn(clprGetMessageQueueMetadataQuery);
        given(clprGetMessageQueueMetadataQuery.hasLedgerId()).willReturn(false);

        assertThatThrownBy(() -> subject.validate(queryContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_LEDGER_ID);
    }

    @Test
    @DisplayName("validate throws CLPR_MESSAGE_QUEUE_NOT_AVAILABLE if queue metadata is null")
    void validateThrowsIfQueueMetadataIsNull() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(queryContext.query()).willReturn(query);
        given(query.getClprMessageQueueMetadataOrThrow()).willReturn(clprGetMessageQueueMetadataQuery);
        given(clprGetMessageQueueMetadataQuery.hasLedgerId()).willReturn(true);
        given(clprGetMessageQueueMetadataQuery.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
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
        given(query.getClprMessageQueueMetadataOrThrow()).willReturn(clprGetMessageQueueMetadataQuery);
        given(clprGetMessageQueueMetadataQuery.hasLedgerId()).willReturn(true);
        given(clprGetMessageQueueMetadataQuery.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(queryContext.createStore(ReadableClprMessageQueueMetadataStore.class))
                .willReturn(readableClprMessageQueueMetadataStore);

        writableMessageQueueMetadata.put(MOCK_LEDGER_ID, ClprMessageQueueMetadata.DEFAULT);

        subject.validate(queryContext);
    }

    @Test
    @DisplayName("validate throws NullPointerException when queryContext is null")
    void validateThrowsForNullQueryContext() {
        assertThatThrownBy(() -> subject.validate(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("findResponse returns empty response when metadata is null")
    void findResponseReturnsEmptyWhenMetadataIsNull() {
        final var header = ResponseHeader.newBuilder().build();

        given(queryContext.query()).willReturn(query);
        given(query.getClprMessageQueueMetadata()).willReturn(clprGetMessageQueueMetadataQuery);
        given(clprGetMessageQueueMetadataQuery.ledgerId()).willReturn(MOCK_LEDGER_ID);
        given(queryContext.createStore(ReadableClprMessageQueueMetadataStore.class))
                .willReturn(readableClprMessageQueueMetadataStore);

        final var response = subject.findResponse(queryContext, header);

        assertThat(response.hasClprMessageQueueMetadata()).isTrue();
        assertThat(response.clprMessageQueueMetadata().hasMessageQueueMetadataProof())
                .isFalse();
        assertThat(response.clprMessageQueueMetadata().header()).isEqualTo(header);
        verifyNoInteractions(stateProofManager); // Should not call stateProofManager if metadata is null
    }

    @Test
    @DisplayName("findResponse returns response with metadata proof when metadata is found")
    void findResponseReturnsWithMetadataProof() {
        final var header = ResponseHeader.newBuilder().build();
        final var mockStateProof = mock(StateProof.class);

        given(queryContext.query()).willReturn(query);
        given(query.getClprMessageQueueMetadata()).willReturn(clprGetMessageQueueMetadataQuery);
        given(clprGetMessageQueueMetadataQuery.ledgerId()).willReturn(MOCK_LEDGER_ID);
        given(queryContext.createStore(ReadableClprMessageQueueMetadataStore.class))
                .willReturn(readableClprMessageQueueMetadataStore);
        given(stateProofManager.getMessageQueueMetadata(MOCK_LEDGER_ID)).willReturn(mockStateProof);

        // add queue to fetch
        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, ClprMessageQueueMetadata.DEFAULT);

        final var response = subject.findResponse(queryContext, header);

        assertThat(response.hasClprMessageQueueMetadata()).isTrue();
        assertThat(response.clprMessageQueueMetadata().hasMessageQueueMetadataProof())
                .isTrue();
        assertThat(response.clprMessageQueueMetadata().messageQueueMetadataProof())
                .isEqualTo(mockStateProof);
        assertThat(response.clprMessageQueueMetadata().header()).isEqualTo(header);
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
