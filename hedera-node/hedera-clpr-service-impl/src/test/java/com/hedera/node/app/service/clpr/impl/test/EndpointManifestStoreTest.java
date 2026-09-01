// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.node.app.service.clpr.impl.ReadableEndpointManifestStoreImpl;
import com.hedera.node.app.service.clpr.impl.WritableEndpointManifestStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EndpointManifestStoreTest {

    private static ClprEndpoint endpoint(final String ip, final int port) {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(ip)
                        .port(port)
                        .build())
                .tlsCertificate(Bytes.wrap(new byte[] {0x01, 0x02}))
                .accountId(Bytes.wrap(new byte[] {0x03}))
                .build();
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Readable {
        @Mock
        private ReadableStates states;

        @Mock
        private ReadableSingletonState<ClprEndpointManifest> singletonState;

        @Test
        @DisplayName("returns the seeded manifest")
        void returnsSeededManifest() {
            given(states.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID))
                    .willReturn(singletonState);
            final var manifest = ClprEndpointManifest.newBuilder()
                    .version(1L)
                    .serviceAddress(CLPR_EVM_ADDRESS_BYTES)
                    .build();
            given(singletonState.get()).willReturn(manifest);

            final var subject = new ReadableEndpointManifestStoreImpl(states);
            assertThat(subject.get()).isEqualTo(manifest);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Writable {
        @Mock
        private WritableStates states;

        @Mock
        private WritableSingletonState<ClprEndpointManifest> singletonState;

        @Test
        @DisplayName("write then read returns identical content")
        void roundTripThreeEndpoints() {
            given(states.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID))
                    .willReturn(singletonState);
            final var subject = new WritableEndpointManifestStore(states);

            final var manifest = ClprEndpointManifest.newBuilder()
                    .version(5L)
                    .serviceAddress(CLPR_EVM_ADDRESS_BYTES)
                    .endpoints(List.of(
                            endpoint("10.0.0.1", 50211), endpoint("10.0.0.2", 50212), endpoint("10.0.0.3", 50213)))
                    .build();
            subject.put(manifest);

            final var captor = ArgumentCaptor.forClass(ClprEndpointManifest.class);
            verify(singletonState).put(captor.capture());
            final var written = captor.getValue();

            assertThat(written).isEqualTo(manifest);
            assertThat(written.endpoints()).hasSize(3);
            assertThat(written.version()).isEqualTo(5L);
        }
    }
}
