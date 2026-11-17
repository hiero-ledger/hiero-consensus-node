// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.node.app.spi.fees.NodeFeeAccumulator;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreFactoryImplTest {

    private static final String SERVICE_NAME = "TokenService";
    private static final Map<Class<?>, ServiceApiProvider<?>> API_PROVIDERS = Map.of();

    @Mock
    private ReadableStoreFactory readableStoreFactory;

    @Mock
    private WritableStoreFactory writableStoreFactory;

    @Mock
    private ServiceApiFactory serviceApiFactory;

    @Mock
    private State state;

    @Mock
    private Configuration configuration;

    @Mock
    private WritableEntityIdStore writableEntityIdStore;

    @Mock
    private NodeFeeAccumulator nodeFeeAccumulator;

    private StoreFactoryImpl subject;

    @BeforeEach
    void setUp() {
        subject = new StoreFactoryImpl(readableStoreFactory, writableStoreFactory, serviceApiFactory);
    }

    @Test
    void testCreateReadableStore() {
        // given
        final var result = mock(ReadableAccountStore.class);
        when(readableStoreFactory.readableStore(ReadableAccountStore.class)).thenReturn(result);

        // when
        final var actual = subject.readableStore(ReadableAccountStore.class);

        // then
        assertThat(actual).isSameAs(result);
    }

    @Test
    void testCreateWritableStore() {
        // given
        final var result = mock(WritableAccountStore.class);
        when(writableStoreFactory.getStore(WritableAccountStore.class)).thenReturn(result);

        // when
        final var actual = subject.writableStore(WritableAccountStore.class);

        // then
        assertThat(actual).isSameAs(result);
    }

    @Test
    void testCreateServiceApi() {
        // given
        final var result = mock(TokenServiceApi.class);
        when(serviceApiFactory.getApi(TokenServiceApi.class)).thenReturn(result);

        // when
        final var actual = subject.serviceApi(TokenServiceApi.class);

        // then
        assertThat(actual).isSameAs(result);
    }

    @Test
    void testAsReadOnly() {
        // when
        final var actual = subject.asReadOnly();

        // then
        assertThat(actual).isSameAs(readableStoreFactory);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testCreateStoreWithInvalidParameters() {
        assertThatThrownBy(() -> subject.readableStore(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.writableStore(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.serviceApi(null)).isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testCreateFromWithInvalidParameters() {
        assertThatThrownBy(() -> StoreFactoryImpl.from(
                        null, SERVICE_NAME, configuration, writableEntityIdStore, API_PROVIDERS, nodeFeeAccumulator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoreFactoryImpl.from(
                        state, null, configuration, writableEntityIdStore, API_PROVIDERS, nodeFeeAccumulator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoreFactoryImpl.from(
                        state, SERVICE_NAME, null, writableEntityIdStore, API_PROVIDERS, nodeFeeAccumulator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoreFactoryImpl.from(
                        state, SERVICE_NAME, configuration, null, API_PROVIDERS, nodeFeeAccumulator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoreFactoryImpl.from(
                        state, SERVICE_NAME, configuration, writableEntityIdStore, null, nodeFeeAccumulator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoreFactoryImpl.from(
                        state, SERVICE_NAME, configuration, writableEntityIdStore, API_PROVIDERS, null))
                .isInstanceOf(NullPointerException.class);
    }
}
