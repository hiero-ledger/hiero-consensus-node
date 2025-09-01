// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.networkadmin.ReadableFreezeStore;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableStoreFactoryTest {

    @Mock
    private State state;

    @Mock(strictness = Strictness.LENIENT)
    private ReadableStates readableStates;

    @Mock
    private ReadableKVState<Object, Object> readableKVState;

    @ParameterizedTest
    @ValueSource(
            classes = {
                ReadableAccountStore.class,
                ReadableNftStore.class,
                ReadableStakingInfoStore.class,
                ReadableTokenStore.class,
                ReadableTopicStore.class,
                ReadableScheduleStore.class,
                ReadableFileStore.class,
                ReadableFreezeStore.class,
                ReadableNodeStore.class,
                ReadableTokenRelationStore.class
            })
    void returnCorrectStoreClass(final Class<?> storeClass) {
        // given
        given(readableStates.get(anyInt())).willReturn(readableKVState);
        given(state.getReadableStates(anyString())).willReturn(readableStates);
        final ReadableStoreFactory subject = new ReadableStoreFactory(state);

        // when
        final var store = subject.getStore(storeClass);

        // then
        assertThat(store).isInstanceOf(storeClass);
    }
}
