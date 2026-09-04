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
import com.hedera.hapi.node.clpr.ClprGetEndpointManifestQuery;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.utils.blocks.NativeTssVerifier;
import com.hedera.node.app.service.clpr.ReadableEndpointManifestStore;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.handlers.ClprGetEndpointManifestHandler;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprGetEndpointManifestHandlerTest {

    @Mock(strictness = LENIENT)
    private QueryContext queryContext;

    @Mock(strictness = LENIENT)
    private ReadableEndpointManifestStore manifestStore;

    @Mock(strictness = LENIENT)
    private ClprStateProofManager stateProofManager;

    private ClprGetEndpointManifestHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ClprGetEndpointManifestHandler(
                stateProofManager, new NativeTssVerifier(), HederaTestConfigBuilder.createConfigProvider());
    }

    @Test
    @DisplayName("should extract header from query")
    void extractsHeader() {
        final var header =
                QueryHeader.newBuilder().responseType(ResponseType.ANSWER_ONLY).build();
        final var query = Query.newBuilder()
                .clprGetEndpointManifest(
                        ClprGetEndpointManifestQuery.newBuilder().header(header).build())
                .build();

        assertThat(subject.extractHeader(query)).isEqualTo(header);
    }

    @Test
    @DisplayName("should create empty response with header")
    void createsEmptyResponse() {
        final var header = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.BUSY)
                .build();
        final var response = subject.createEmptyResponse(header);

        assertThat(response.clprGetEndpointManifest()).isNotNull();
        assertThat(response.clprGetEndpointManifest().header()).isEqualTo(header);
        assertThat(response.clprGetEndpointManifest().manifest()).isNull();
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
    @DisplayName("should return the manifest from the store when precheck OK")
    void returnsManifestWhenOk() {
        final var manifest = ClprEndpointManifest.newBuilder().version(7L).build();
        given(queryContext.createStore(ReadableEndpointManifestStore.class)).willReturn(manifestStore);
        given(manifestStore.get()).willReturn(manifest);
        given(stateProofManager.buildManifestStateProof()).willReturn(Bytes.EMPTY);
        given(stateProofManager.latestLedgerId()).willReturn(Bytes.EMPTY);

        final var header =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();
        final var response = subject.findResponse(queryContext, header);

        final var returned = response.clprGetEndpointManifest();
        assertThat(returned).isNotNull();
        assertThat(returned.manifest()).isEqualTo(manifest);
        assertThat(returned.manifestStateProof()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("should not populate manifest when precheck code is not OK")
    void doesNotReturnManifestWhenPrecheckFailed() {
        final var header = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.BUSY)
                .build();
        final var response = subject.findResponse(queryContext, header);

        assertThat(response.clprGetEndpointManifest()).isNotNull();
        assertThat(response.clprGetEndpointManifest().manifest()).isNull();
    }
}
