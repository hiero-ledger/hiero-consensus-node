// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprSubmissionsTest {
    private static final ClprEndpoint TEST_ENDPOINT = ClprEndpoint.newBuilder()
            .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                    .ipAddress("10.0.0.1")
                    .port(50211)
                    .build())
            .tlsCertificate(Bytes.wrap(new byte[] {1, 2, 3}))
            .accountId(Bytes.wrap(new byte[20]))
            .build();

    @Mock
    private ExecutorService executor;

    @Mock
    private AppContext appContext;

    @Mock
    private AppContext.Gossip gossip;

    @Mock
    private NodeInfo selfNodeInfo;

    private ClprSubmissions subject;

    @BeforeEach
    void setUp() {
        subject = new ClprSubmissions(executor, appContext);
    }

    @Test
    void skipsEndpointPublicationWhenClprIsDisabled() {
        given(appContext.configSupplier()).willReturn(() -> HederaTestConfigBuilder.createConfig());

        subject.submitEndpointPublication(TEST_ENDPOINT);

        verifyNoInteractions(gossip);
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesExpectedBodyForEndpointPublication() {
        primeAppContext();

        subject.submitEndpointPublication(TEST_ENDPOINT);

        final var body = captureSubmittedBody();
        assertTrue(body.hasClprEndpointPublication());
        final var op = body.clprEndpointPublicationOrThrow();
        assertTrue(op.hasEndpoint());
        // Endpoint bytes round-trip: the builder consumer carried the full ClprEndpoint value
        // through without dropping fields.
        final var carriedEndpoint = op.endpointOrThrow();
        assertTrue(carriedEndpoint.hasServiceEndpoint());
        assertTrue(carriedEndpoint
                .serviceEndpointOrThrow()
                .ipAddress()
                .equals(TEST_ENDPOINT.serviceEndpointOrThrow().ipAddress()));
    }

    private void primeAppContext() {
        given(gossip.isAvailable()).willReturn(true);
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", true)
                .getOrCreateConfig());
        given(appContext.gossip()).willReturn(gossip);
    }

    @SuppressWarnings("unchecked")
    private TransactionBody captureSubmittedBody() {
        final ArgumentCaptor<Consumer<TransactionBody.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(gossip)
                .submitFuture(
                        eq(AccountID.DEFAULT),
                        eq(Instant.EPOCH),
                        any(),
                        captor.capture(),
                        any(),
                        anyInt(),
                        anyInt(),
                        any(),
                        any());
        final var builder = TransactionBody.newBuilder();
        captor.getValue().accept(builder);
        return builder.build();
    }
}
