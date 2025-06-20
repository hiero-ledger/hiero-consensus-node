// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.token.CryptoGetLiveHashQuery;
import com.hedera.hapi.node.token.CryptoGetLiveHashResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetLiveHashHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoGetLiveHashHandlerTest {
    @Mock
    private QueryContext context;

    private CryptoGetLiveHashHandler subject;

    @BeforeEach
    void setUp() {
        subject = new CryptoGetLiveHashHandler();
    }

    @Test
    void extractsHeader() {
        final var data = CryptoGetLiveHashQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();
        final var query = Query.newBuilder().cryptoGetLiveHash(data).build();
        final var header = subject.extractHeader(query);
        final var op = query.cryptoGetLiveHashOrThrow();
        assertThat(op.header()).isEqualTo(header);
    }

    @Test
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder().build();
        final var response = subject.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .cryptoGetLiveHash(CryptoGetLiveHashResponse.newBuilder().header(responseHeader))
                .build();
        assertThat(expectedResponse).isEqualTo(response);
    }

    @Test
    void validateThrowsPreCheck() {
        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(NOT_SUPPORTED));
    }

    @Test
    void findResponseThrowsUnsupported() {
        final var responseHeader = ResponseHeader.newBuilder().build();
        assertThatThrownBy(() -> subject.findResponse(context, responseHeader))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
