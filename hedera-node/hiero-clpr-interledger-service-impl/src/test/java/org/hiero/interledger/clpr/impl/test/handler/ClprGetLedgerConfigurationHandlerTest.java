// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.clpr.ClprGetLedgerConfigurationQuery;
import org.hiero.hapi.interledger.clpr.ClprGetLedgerConfigurationResponse;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.ReadableClprLedgerConfigurationStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.handlers.ClprGetLedgerConfigurationHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ClprGetLedgerConfigurationHandlerTest extends ClprHandlerTestBase {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private QueryContext context;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ClprStateProofManager stateProofManager;

    @Mock
    private com.hedera.node.config.VersionedConfiguration configuration;

    private ClprGetLedgerConfigurationHandler subject;

    @BeforeEach
    public void setUp() {
        setupHandlerBase();
        subject = new ClprGetLedgerConfigurationHandler(stateProofManager);

        given(context.createStore(ReadableClprLedgerConfigurationStore.class)).willReturn(readableLedgerConfigStore);
        given(context.createStore(ReadableHistoryStore.class)).willReturn(readableHistoryStore);
        given(stateProofManager.clprEnabled()).willReturn(true);
    }

    @Test
    void extractHeader() throws PreCheckException {
        final var query = createClprGetLedgerConfigurationQuery(localClprLedgerId);
        final var header = subject.extractHeader(query);
        final var op = query.getClprLedgerConfigurationOrThrow();
        assertEquals(op.header(), header);
    }

    @Test
    void createsEmptyResponse() {
        final var header = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.INVALID_QUERY_RANGE)
                .build();
        final var response = subject.createEmptyResponse(header);
        final var expectedResponse = Response.newBuilder()
                .clprLedgerConfiguration(
                        ClprGetLedgerConfigurationResponse.newBuilder().header(header))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    void validatesQueryWhenValidNonEmptyLedgerId() {
        final var query = createClprGetLedgerConfigurationQuery(remoteClprLedgerId);
        given(context.query()).willReturn(query);
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.getLedgerConfiguration(remoteClprLedgerId))
                .willReturn(buildLocalClprStateProofWrapper(remoteClprConfig));
        given(stateProofManager.verifyProof(any(StateProof.class), any(Bytes.class)))
                .willReturn(true);
        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void validatesQueryWhenValidEmptyLedgerId() {
        final var query = createClprGetLedgerConfigurationQuery(null);
        given(context.query()).willReturn(query);
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.getLedgerConfiguration(localClprLedgerId))
                .willReturn(buildLocalClprStateProofWrapper(localClprConfig));
        given(stateProofManager.verifyProof(any(StateProof.class), any(Bytes.class)))
                .willReturn(true);
        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void validatesQueryWhenInvalidLedgerId() {
        final var query = createClprGetLedgerConfigurationQuery(ClprLedgerId.newBuilder()
                .ledgerId(Bytes.wrap("invalidledgerid".getBytes()))
                .build());
        given(context.query()).willReturn(query);
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.getLedgerConfiguration(any(ClprLedgerId.class))).willReturn(null);
        assertThatCode(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.CLPR_LEDGER_CONFIGURATION_NOT_AVAILABLE.name());
    }

    @Test
    void validatesQueryWhenWaitingForLedgerId() {
        final var query =
                createClprGetLedgerConfigurationQuery(ClprLedgerId.newBuilder().build());
        given(context.query()).willReturn(query);
        given(stateProofManager.getLocalLedgerId()).willReturn(null);
        assertThatCode(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.WAITING_FOR_LEDGER_ID.name());
    }

    @Test
    void getResponseIfLedgerIdIsValidRemote() {
        final var query = createClprGetLedgerConfigurationQuery(remoteClprLedgerId);
        given(context.query()).willReturn(query);
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.getLedgerConfiguration(remoteClprLedgerId))
                .willReturn(buildLocalClprStateProofWrapper(remoteClprConfig));
        checkResponse(remoteClprConfig);
    }

    @Test
    void getResponseIfLedgerIdIsEmpty() {
        final var query = createClprGetLedgerConfigurationQuery(null);
        given(context.query()).willReturn(query);
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.getLedgerConfiguration(localClprLedgerId))
                .willReturn(buildLocalClprStateProofWrapper(localClprConfig));
        checkResponse(localClprConfig);
    }

    private void checkResponse(@NonNull final ClprLedgerConfiguration expectedLedgerConfig) {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .responseType(ResponseType.ANSWER_ONLY)
                .build();
        final var response = subject.findResponse(context, responseHeader);
        final var clprResponse = response.clprLedgerConfigurationOrThrow();
        assertEquals(ResponseCodeEnum.OK, requireNonNull(clprResponse.header()).nodeTransactionPrecheckCode());
        // Extract configuration from state proof
        final var proof = clprResponse.ledgerConfigurationProof();
        assertNotNull(proof, "State proof should not be null");
        final var actualConfig = org.hiero.interledger.clpr.ClprStateProofUtils.extractConfiguration(proof);
        assertEquals(expectedLedgerConfig, actualConfig);
    }

    /**
     * Creates a {@link Query} for the given {@link ClprLedgerId} to retrieve the ledger configuration.
     * If the {@code clprLedgerId} is {@code null}, it will create a query without a ledger id,
     * which is used to retrieve the current ledger configuration.
     *
     * @param clprLedgerId the {@link ClprLedgerId} to retrieve the configuration for.
     * @return a {@link Query} to retrieve the ledger configuration.
     */
    private Query createClprGetLedgerConfigurationQuery(@Nullable final ClprLedgerId clprLedgerId) {
        return Query.newBuilder()
                .getClprLedgerConfiguration(ClprGetLedgerConfigurationQuery.newBuilder()
                        .ledgerId(clprLedgerId)
                        .header(QueryHeader.newBuilder().build()))
                .build();
    }
}
