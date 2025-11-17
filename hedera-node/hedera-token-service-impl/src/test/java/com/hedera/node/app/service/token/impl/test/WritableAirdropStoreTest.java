// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.PendingAirdropValue;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.node.app.service.entityid.ReadableEntityCounters;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.service.token.impl.WritableAirdropStore;
import com.hedera.node.app.service.token.impl.test.handlers.util.StateBuilderUtil;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableAirdropStoreTest extends StateBuilderUtil {

    private final Configuration configuration = HederaTestConfigBuilder.createConfig();

    @Mock
    private WritableStates writableStates;

    @Mock
    protected ReadableEntityCounters readableEntityCounters;

    @Mock
    protected WritableEntityCounters writableEntityCounters;

    private MapWritableKVState<PendingAirdropId, AccountPendingAirdrop> writableAirdropState;

    private WritableAirdropStore subject;

    @BeforeEach
    public void setUp() {
        writableAirdropState = emptyWritableAirdropStateBuilder().build();
        given(writableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_STATE_ID))
                .willReturn(writableAirdropState);
        subject = new WritableAirdropStore(writableStates, writableEntityCounters);
    }

    @Test
    void putsAirdropsToState() {
        final var airdropId = getFungibleAirdrop();
        final var airdropValue = airdropWithValue(20);
        final var accountAirdrop = accountAirdropWith(airdropValue);

        assertThat(writableAirdropState.contains(airdropId)).isFalse();

        subject.put(airdropId, accountAirdrop);

        assertThat(writableAirdropState.contains(airdropId)).isTrue();
        assertThat(writableAirdropState.get(airdropId)).isNotNull();
        final var tokenValue =
                Objects.requireNonNull(writableAirdropState.get(airdropId)).pendingAirdropValue();
        assertThat(airdropValue).isEqualTo(tokenValue);
    }

    @Test
    void putsDoesNotUpdateNftIfExists() {
        final var nftId = getNonFungibleAirDrop();
        final var accountAirdrop = accountAirdropWith(null);
        var stateSpy = Mockito.spy(
                writableAirdropState = emptyWritableAirdropStateBuilder()
                        .value(nftId, accountAirdrop)
                        .build());
        given(writableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_STATE_ID))
                .willReturn(writableAirdropState);
        subject = new WritableAirdropStore(writableStates, writableEntityCounters);

        assertThat(writableAirdropState.contains(nftId)).isTrue();

        subject.put(nftId, accountAirdrop);

        assertThat(writableAirdropState.contains(nftId)).isTrue();
        final var tokenValue =
                Objects.requireNonNull(writableAirdropState.get(nftId)).pendingAirdropValue();
        assertThat(tokenValue).isNull();
        verify(stateSpy, times(0)).put(any(), any());
    }

    @Test
    void removesAirdropById() {
        final var fungibleAirdropToRemove = getFungibleAirdrop();
        final var fungibleAirdropValue = airdropWithValue(15);
        final var fungibleAccountAirdrop = accountAirdropWith(fungibleAirdropValue);
        final var nftToRemove = getNonFungibleAirDrop();
        final var nftAccountAirdrop = accountAirdropWith(null);
        writableAirdropState = emptyWritableAirdropStateBuilder()
                .value(fungibleAirdropToRemove, fungibleAccountAirdrop)
                .value(nftToRemove, nftAccountAirdrop)
                .build();

        assertThat(writableAirdropState.contains(fungibleAirdropToRemove)).isTrue();
        assertThat(writableAirdropState.contains(nftToRemove)).isTrue();

        given(writableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_STATE_ID))
                .willReturn(writableAirdropState);
        subject = new WritableAirdropStore(writableStates, writableEntityCounters);

        assertThat(subject.exists(fungibleAirdropToRemove)).isTrue();
        assertThat(subject.exists(nftToRemove)).isTrue();
        subject.remove(fungibleAirdropToRemove);
        subject.remove(nftToRemove);
        assertThat(subject.exists(fungibleAirdropToRemove)).isFalse();
        assertThat(subject.exists(nftToRemove)).isFalse();
    }

    @Test
    void getReturnsImmutableAirDrop() {
        final var airdropId = getFungibleAirdrop();
        final var airdropValue = airdropWithValue(255);
        final var accountAirdrop = accountAirdropWith(airdropValue);
        final var nftAirdropId = getNonFungibleAirDrop();
        final var nftAccountAirdrop = accountAirdropWith(null);

        subject.put(airdropId, accountAirdrop);
        subject.put(nftAirdropId, nftAccountAirdrop);

        final var readAirdrop = subject.get(airdropId);
        assertThat(readAirdrop).isNotNull();
        assertThat(airdropValue).isEqualTo(readAirdrop.pendingAirdropValue());

        final var readNft = subject.get(nftAirdropId);
        assertThat(nftAirdropId).isNotNull();
        assertThat(readNft).isNotNull();
        assertThat(readNft.pendingAirdropValue()).isNull();
    }

    @Test
    void getNonExisting() {
        final var nonExistingAirdropId = getFungibleAirdrop();
        final var nonExistingNftAirdropId = getNonFungibleAirDrop();

        assertThat(subject.exists(nonExistingAirdropId)).isFalse();
        assertThat(subject.exists(nonExistingNftAirdropId)).isFalse();

        final var readAirdrop = subject.get(nonExistingAirdropId);
        final var readNft = subject.get(nonExistingNftAirdropId);

        assertThat(readAirdrop).isNull();
        assertThat(readNft).isNull();
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testConstructorCallWithNull() {
        assertThatThrownBy(() -> subject = new WritableAirdropStore(null, writableEntityCounters))
                .isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testGetWithNullParam() {
        assertThatThrownBy(() -> subject.get(null)).isInstanceOf(NullPointerException.class);
    }

    private PendingAirdropId getNonFungibleAirDrop() {
        return PendingAirdropId.newBuilder()
                .nonFungibleToken(NftID.newBuilder()
                        .serialNumber(123456)
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
