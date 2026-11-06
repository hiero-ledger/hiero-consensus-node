// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_PEER_ENDPOINT_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprEvictPeerEndpointTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprEvictPeerEndpointHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprEvictPeerEndpointHandlerTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(2).build();
    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final ClprServiceEndpoint BAD = serviceEndpoint("203.0.113.66", 50211);
    private static final ClprServiceEndpoint GOOD = serviceEndpoint("203.0.113.7", 50211);

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableStates writableStates;

    @Mock
    private ClprChannelLifecycle channelLifecycle;

    private ClprEvictPeerEndpointHandler subject;
    private WritableChannelStore channelStore;

    @BeforeEach
    void setUp() {
        subject = new ClprEvictPeerEndpointHandler(channelLifecycle);
        final var writableChannels = MapWritableKVState.<ProtoBytes, ClprChannel>builder(
                        CHANNELS_STATE_ID, "ClprService:CHANNELS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(writableChannels);
        channelStore = new WritableChannelStore(writableStates);
    }

    @Test
    @DisplayName("pureChecks rejects a channel_id that is not 32 bytes")
    void rejectsWrongChannelIdLength() {
        given(pureChecksContext.body()).willReturn(txnWith(Bytes.wrap(new byte[16]), BAD));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("pureChecks rejects a missing service endpoint")
    void rejectsMissingServiceEndpoint() {
        given(pureChecksContext.body()).willReturn(txnWith(CHANNEL_ID, null));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("removes every matching endpoint, keeps the manifest version, and reseeds the dial cache")
    void evictsMatchingEndpoints() {
        putChannel(ClprChannelStatus.ACTIVE, List.of(endpoint(BAD), endpoint(GOOD), endpoint(BAD)));
        setupHandleContext(txnWith(CHANNEL_ID, BAD));

        subject.handle(handleContext);

        final var manifest = channelStore.getChannel(CHANNEL_ID).endpointManifestOrThrow();
        assertThat(manifest.endpoints()).containsExactly(endpoint(GOOD));
        assertThat(manifest.version()).isEqualTo(4L);
        then(channelLifecycle).should().seedPeerEndpoints(CHANNEL_ID, List.of(endpoint(GOOD)));
    }

    @Test
    @DisplayName("fails when the endpoint is not in the channel's manifest")
    void failsWhenEndpointNotFound() {
        putChannel(ClprChannelStatus.ACTIVE, List.of(endpoint(GOOD)));
        setupHandleContext(txnWith(CHANNEL_ID, BAD));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_PEER_ENDPOINT_NOT_FOUND));
        then(channelLifecycle).should(never()).seedPeerEndpoints(any(), any());
    }

    @Test
    @DisplayName("fails when the channel does not exist")
    void failsWhenChannelNotFound() {
        setupHandleContext(txnWith(CHANNEL_ID, BAD));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_NOT_FOUND));
    }

    @Test
    @DisplayName("fails when the channel is closed")
    void failsWhenChannelClosed() {
        putChannel(ClprChannelStatus.CLOSED, List.of(endpoint(BAD)));
        setupHandleContext(txnWith(CHANNEL_ID, BAD));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    private void putChannel(final ClprChannelStatus status, final List<ClprEndpoint> endpoints) {
        channelStore.put(ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(status)
                .endpointManifest(ClprEndpointManifest.newBuilder()
                        .version(4L)
                        .endpoints(endpoints)
                        .build())
                .endpointManifestVersion(4L)
                .build());
    }

    private void setupHandleContext(final TransactionBody txn) {
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient()
                .when(handleContext.configuration())
                .thenReturn(HederaTestConfigBuilder.create()
                        .withValue("clpr.enabled", true)
                        .getOrCreateConfig());
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(storeFactory.writableStore(WritableChannelStore.class)).thenReturn(channelStore);
    }

    private static TransactionBody txnWith(final Bytes channelId, final ClprServiceEndpoint serviceEndpoint) {
        final var op = ClprEvictPeerEndpointTransactionBody.newBuilder().channelId(channelId);
        if (serviceEndpoint != null) {
            op.serviceEndpoint(serviceEndpoint);
        }
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprEvictPeerEndpoint(op.build())
                .build();
    }

    private static ClprEndpoint endpoint(final ClprServiceEndpoint serviceEndpoint) {
        return ClprEndpoint.newBuilder().serviceEndpoint(serviceEndpoint).build();
    }

    private static ClprServiceEndpoint serviceEndpoint(final String address, final int port) {
        return ClprServiceEndpoint.newBuilder().ipAddress(address).port(port).build();
    }
}
