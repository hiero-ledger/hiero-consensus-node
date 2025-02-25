// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreFactoryImplTest {

    @Mock
    private ReadableStoreFactory readableStoreFactory;

    @Mock
    private WritableStoreFactory writableStoreFactory;

    @Mock
    private ServiceApiFactory serviceApiFactory;

    private StoreFactoryImpl subject;

    @BeforeEach
    void setUp() {
        subject = new StoreFactoryImpl(readableStoreFactory, writableStoreFactory, serviceApiFactory);
    }

    @Test
    void testCreateReadableStore() {
        // given
        final var result = mock(ReadableAccountStore.class);
        when(readableStoreFactory.getStore(ReadableAccountStore.class)).thenReturn(result);

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

    @SuppressWarnings("ConstantConditions")
    @Test
    void testCreateStoreWithInvalidParameters() {
        assertThatThrownBy(() -> subject.readableStore(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.writableStore(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.serviceApi(null)).isInstanceOf(NullPointerException.class);
    }
}
