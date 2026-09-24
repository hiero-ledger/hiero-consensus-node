// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.clpr.ClprGetLedgerConfigurationQuery;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.utils.blocks.NativeTssVerifier;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.handlers.ClprGetLedgerConfigurationHandler;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprGetLedgerConfigurationHandlerTest {

    @Mock(strictness = LENIENT)
    private QueryContext queryContext;

    @Mock(strictness = LENIENT)
    private ReadableLedgerConfigurationStore configStore;

    @Mock(strictness = LENIENT)
    private ClprStateProofManager stateProofManager;

    private ClprGetLedgerConfigurationHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ClprGetLedgerConfigurationHandler(
                stateProofManager, new NativeTssVerifier(), HederaTestConfigBuilder.createConfigProvider());
    }

    @Test
    @DisplayName("should extract header from query")
    void extractsHeader() {
        final var header =
                QueryHeader.newBuilder().responseType(ResponseType.ANSWER_ONLY).build();
        final var query = Query.newBuilder()
                .clprGetLedgerConfiguration(ClprGetLedgerConfigurationQuery.newBuilder()
                        .header(header)
                        .build())
                .build();

        final var extracted = subject.extractHeader(query);
        assertThat(extracted).isEqualTo(header);
    }

    @Test
    @DisplayName("should create empty response with header")
    void createsEmptyResponse() {
        final var header = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.BUSY)
                .build();
        final var response = subject.createEmptyResponse(header);

        assertThat(response.clprGetLedgerConfiguration()).isNotNull();
        assertThat(response.clprGetLedgerConfiguration().header()).isEqualTo(header);
        assertThat(response.clprGetLedgerConfiguration().configuration()).isNull();
    }

    @Test
    @DisplayName("should not require node payment (free query)")
    void doesNotRequireNodePayment() {
        assertThat(subject.requiresNodePayment(ResponseType.ANSWER_ONLY)).isFalse();
        assertThat(subject.requiresNodePayment(ResponseType.COST_ANSWER)).isFalse();
    }

    @Test
    @DisplayName("should reject when CLPR is disabled")
    void rejectsWhenClprDisabled() {
        given(queryContext.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("clpr.enabled", false)
                        .getOrCreateConfig());

        assertThatThrownBy(() -> subject.validate(queryContext))
                .isInstanceOf(com.hedera.node.app.spi.workflows.PreCheckException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should return configuration with expected field values")
    void returnsConfigurationWithExpectedValues() {
        final var throttles = ClprThrottles.newBuilder()
                .maxMessagesPerBundle(100)
                .maxMessagePayloadBytes(65536)
                .maxGasPerMessage(1_000_000L)
                .maxQueueDepth(1000)
                .maxSyncBytes(1_048_576L)
                .build();
        final var config = ClprLedgerConfiguration.newBuilder()
                .protocolVersion(1)
                .chainId("hiero:unit")
                .throttles(throttles)
                .build();
        given(queryContext.createStore(ReadableLedgerConfigurationStore.class)).willReturn(configStore);
        given(configStore.getConfiguration()).willReturn(config);

        final var header =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();
        final var response = subject.findResponse(queryContext, header);

        final var returned = response.clprGetLedgerConfiguration().configuration();
        assertThat(returned).isNotNull();
        assertThat(returned.protocolVersion()).isEqualTo(1);
        assertThat(returned.chainId()).isEqualTo("hiero:unit");
        assertThat(returned.throttles().maxMessagesPerBundle()).isEqualTo(100);
        assertThat(returned.throttles().maxMessagePayloadBytes()).isEqualTo(65536);
        assertThat(returned.throttles().maxGasPerMessage()).isEqualTo(1_000_000L);
        assertThat(returned.throttles().maxQueueDepth()).isEqualTo(1000);
        assertThat(returned.throttles().maxSyncBytes()).isEqualTo(1_048_576L);
    }

    @Test
    @DisplayName("should return default zero throttle values when throttles not set")
    void returnsDefaultThrottleValues() {
        final var config = ClprLedgerConfiguration.newBuilder()
                .protocolVersion(1)
                .chainId("hiero:unit")
                .build();
        given(queryContext.createStore(ReadableLedgerConfigurationStore.class)).willReturn(configStore);
        given(configStore.getConfiguration()).willReturn(config);

        final var header =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();
        final var response = subject.findResponse(queryContext, header);

        final var returned = response.clprGetLedgerConfiguration().configuration();
        assertThat(returned).isNotNull();
        final var throttles = returned.throttlesOrElse(ClprThrottles.DEFAULT);
        assertThat(throttles.maxMessagesPerBundle()).isZero();
        assertThat(throttles.maxMessagePayloadBytes()).isZero();
        assertThat(throttles.maxGasPerMessage()).isZero();
        assertThat(throttles.maxQueueDepth()).isZero();
        assertThat(throttles.maxSyncBytes()).isZero();
    }

    @Test
    @DisplayName("should not return configuration when precheck code is not OK")
    void doesNotReturnConfigWhenPrecheckFailed() {
        final var header = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.BUSY)
                .build();
        final var response = subject.findResponse(queryContext, header);

        assertThat(response.clprGetLedgerConfiguration()).isNotNull();
        assertThat(response.clprGetLedgerConfiguration().configuration()).isNull();
    }
}
