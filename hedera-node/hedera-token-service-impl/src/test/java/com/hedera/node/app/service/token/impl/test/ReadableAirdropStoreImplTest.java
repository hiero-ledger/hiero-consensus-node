// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.PendingAirdropValue;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.impl.ReadableAirdropStoreImpl;
import com.hedera.node.app.service.token.impl.test.handlers.util.StateBuilderUtil;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableAirdropStoreImplTest extends StateBuilderUtil {

    @Mock
    private ReadableStates readableStates;

    private ReadableKVState<PendingAirdropId, AccountPendingAirdrop> airdrops;

    private ReadableAirdropStoreImpl subject;

    @Mock
    private ReadableEntityCounters entityCounters;

    @BeforeEach
    public void setUp() {
        given(readableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_KEY))
                .willReturn(airdrops);
        subject = new ReadableAirdropStoreImpl(readableStates, entityCounters);
    }

    @Test
    void getsValueIfAirdropContainsFungibleToken() {
        var fungibleAirdrop = getFungibleAirdrop();
        var airdropValue = airdropWithValue(5);
        var accountAirdrop = accountAirdropWith(airdropValue);

        airdrops = emptyReadableAirdropStateBuilder()
                .value(fungibleAirdrop, accountAirdrop)
                .build();
        given(readableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_KEY))
                .willReturn(airdrops);
        subject = new ReadableAirdropStoreImpl(readableStates, entityCounters);

        assertThat(subject.get(fungibleAirdrop)).isNotNull();
        assertThat(Objects.requireNonNull(subject.get(fungibleAirdrop)).pendingAirdropValue())
                .isEqualTo(airdropValue);
    }

    @Test
    void getsFungibleThatDoesNotExist() {
        var fungibleAirdrop = getFungibleAirdrop();

        airdrops = emptyReadableAirdropStateBuilder().build();
        given(readableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_KEY))
                .willReturn(airdrops);
        subject = new ReadableAirdropStoreImpl(readableStates, entityCounters);

        assertThat(subject.get(fungibleAirdrop)).isNull();
        assertThat(subject.get(fungibleAirdrop)).isNull();
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void getsFungibleWithNullParam() {
        assertThatThrownBy(() -> subject.get(null)).isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testConstructorCallWithNull() {
        assertThatThrownBy(() -> subject = new ReadableAirdropStoreImpl(null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testSizeOfState() {
        airdrops = emptyReadableAirdropStateBuilder()
                .value(getNonFungibleAirDrop(), accountAirdropWith(null))
                .build();
        given(readableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_KEY))
                .willReturn(airdrops);
        given(entityCounters.getCounterFor(EntityType.AIRDROP)).willReturn(10L);
        subject = new ReadableAirdropStoreImpl(readableStates, entityCounters);
        assertThat(entityCounters.getCounterFor(EntityType.AIRDROP)).isEqualTo(subject.sizeOfState());
    }

    @Test
    void testExists() {
        var fungibleAirdrop = getFungibleAirdrop();
        var fungibleValue = airdropWithValue(10);
        var accountAirdrop = accountAirdropWith(fungibleValue);

        airdrops = emptyReadableAirdropStateBuilder()
                .value(fungibleAirdrop, accountAirdrop)
                .build();
        given(readableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_KEY))
                .willReturn(airdrops);
        subject = new ReadableAirdropStoreImpl(readableStates, entityCounters);

        final var store = new ReadableAirdropStoreImpl(readableStates, entityCounters);
        assertThat(readableStates.get(AIRDROPS_KEY).contains(fungibleAirdrop)).isEqualTo(store.exists(fungibleAirdrop));
    }

    @Test
    void testDoesNotExists() {
        var fungibleAirdrop = getFungibleAirdrop();

        airdrops = emptyReadableAirdropStateBuilder().build();
        given(readableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_KEY))
                .willReturn(airdrops);
        subject = new ReadableAirdropStoreImpl(readableStates, entityCounters);

        final var store = new ReadableAirdropStoreImpl(readableStates, entityCounters);
        assertThat(readableStates.get(AIRDROPS_KEY).contains(fungibleAirdrop)).isEqualTo(store.exists(fungibleAirdrop));
    }

    private PendingAirdropId getNonFungibleAirDrop() {
        return PendingAirdropId.newBuilder()
                .nonFungibleToken(NftID.newBuilder()
                        .serialNumber(123_456)
                        .tokenId(TokenID.newBuilder()
                                .tokenNum(1)
                                .shardNum(2)
                                .realmNum(3)
                                .build())
                        .build())
                .build();
    }

    private PendingAirdropId getFungibleAirdrop() {
        return PendingAirdropId.newBuilder()
                .fungibleTokenType(
                        TokenID.newBuilder().realmNum(1).shardNum(2).tokenNum(3).build())
                .build();
    }

    private PendingAirdropValue airdropWithValue(long value) {
        return PendingAirdropValue.newBuilder().amount(value).build();
    }

    private AccountPendingAirdrop accountAirdropWith(PendingAirdropValue pendingAirdropValue) {
        return AccountPendingAirdrop.newBuilder()
                .pendingAirdropValue(pendingAirdropValue)
                .build();
    }
}
