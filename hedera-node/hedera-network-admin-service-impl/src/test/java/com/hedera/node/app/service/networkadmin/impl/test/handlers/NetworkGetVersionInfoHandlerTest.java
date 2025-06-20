// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.network.NetworkGetVersionInfoQuery;
import com.hedera.hapi.node.network.NetworkGetVersionInfoResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkGetVersionInfoHandler;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NetworkGetVersionInfoHandlerTest {
    @Mock
    private QueryContext context;

    private NetworkGetVersionInfoHandler subject;

    @BeforeEach
    void setUp() {
        subject = new NetworkGetVersionInfoHandler();
    }

    @Test
    @DisplayName("Query header is extracted correctly")
    void extractsHeader() {
        final Query query = validQuery();
        final var header = subject.extractHeader(query);
        final var op = query.networkGetVersionInfoOrThrow();
        assertEquals(op.header(), header);
    }

    @Test
    @DisplayName("Check empty query response is created correctly")
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = subject.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .networkGetVersionInfo(
                        NetworkGetVersionInfoResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    @DisplayName("Check node payment requirement is correct with each response type")
    void requiresPayment() {
        assertTrue(subject.requiresNodePayment(ResponseType.ANSWER_ONLY));
        assertTrue(subject.requiresNodePayment(ResponseType.ANSWER_STATE_PROOF));
        assertFalse(subject.requiresNodePayment(ResponseType.COST_ANSWER));
        assertFalse(subject.requiresNodePayment(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    @DisplayName("Check Answer Only Cost is correct with each response type")
    void needsAnswerOnlyCostForCostAnswer() {
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.ANSWER_ONLY));
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.ANSWER_STATE_PROOF));
        assertTrue(subject.needsAnswerOnlyCost(ResponseType.COST_ANSWER));
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    @DisplayName("Validate query is good")
    void validates() {
        final var query = validQuery();
        given(context.query()).willReturn(query);
        assertDoesNotThrow(() -> subject.validate(context));
    }

    @Test
    @DisplayName("failed response is correctly handled in findResponse")
    void findResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();

        final var query = validQuery();
        given(context.query()).willReturn(query);

        final Configuration config = HederaTestConfigBuilder.createConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        assertEquals(
                ResponseCodeEnum.FAIL_FEE,
                response.networkGetVersionInfoOrThrow().headerOrThrow().nodeTransactionPrecheckCode());
    }

    @Test
    @DisplayName("query happy path succeeds")
    void findResponseSucceeds() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();

        final var query = validQuery();
        given(context.query()).willReturn(query);

        final Configuration config = HederaTestConfigBuilder.createConfig();
        given(context.configuration()).willReturn(config);

        final NetworkGetVersionInfoResponse op =
                subject.findResponse(context, responseHeader).networkGetVersionInfoOrThrow();
        assertDoesNotThrow(op::hederaServicesVersionOrThrow);
        assertDoesNotThrow(op::hapiProtoVersionOrThrow);
    }

    @NonNull
    private static Query validQuery() {
        final var data = NetworkGetVersionInfoQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();
        return Query.newBuilder().networkGetVersionInfo(data).build();
    }
}
