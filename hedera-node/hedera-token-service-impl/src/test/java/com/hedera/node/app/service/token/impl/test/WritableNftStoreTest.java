// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.NFTS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.swirlds.state.spi.WritableKVState;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableNftStoreTest extends CryptoTokenHandlerTestBase {
    @BeforeEach
    public void setUp() {
        super.setUp();
        writableNftState = emptyWritableNftStateBuilder().build();
        given(writableStates.<NftID, Nft>get(NFTS_KEY)).willReturn(writableNftState);
        writableNftStore = new WritableNftStore(writableStates, writableEntityCounters);
    }

    @Test
    void throwsIfNullValuesAsArgs() {
        assertThrows(NullPointerException.class, () -> new WritableNftStore(null, writableEntityCounters));
        assertThrows(NullPointerException.class, () -> new WritableNftStore(writableStates, null));
        assertThrows(NullPointerException.class, () -> writableNftStore.put(null));
        assertThrows(NullPointerException.class, () -> writableNftStore.get(null));
        assertThrows(NullPointerException.class, () -> writableNftStore.get(null, 0));
    }

    @Test
    void constructorCreatesTokenState() {
        final var store = new WritableNftStore(writableStates, writableEntityCounters);
        assertNotNull(store);
    }

    @Test
    void getReturnsImmutableToken() {
        final var id =
                NftID.newBuilder().tokenId(fungibleTokenId).serialNumber(1).build();
        final var nft = givenNft(id);
        writableNftStore.put(nft);

        final var readToken = writableNftStore.get(id);
        assertEquals(nft, readToken);

        final var readToken2 = writableNftStore.get(fungibleTokenId, 1);
        assertEquals(nft, readToken2);
    }

    @Test
    void putsTokenChangesToStateInModifications() {
        final var id =
                NftID.newBuilder().tokenId(fungibleTokenId).serialNumber(1).build();
        final var nft = givenNft(id);

        assertFalse(writableNftState.contains(id));

        // put, keeps the token in the modifications
        writableNftStore.put(nft);

        assertTrue(writableNftState.contains(id));
        final var writtenToken = writableNftState.get(id);
        assertEquals(nft, writtenToken);
    }

    @Test
    void getsSizeOfState() {
        final var id =
                NftID.newBuilder().tokenId(fungibleTokenId).serialNumber(1).build();
        final var nft = givenNft(id);

        assertEquals(2, writableNftStore.sizeOfState());
        assertEquals(Collections.EMPTY_SET, writableNftStore.modifiedNfts());
        writableNftStore.putAndIncrementCount(nft);

        assertEquals(3, writableNftStore.sizeOfState());
        assertEquals(Set.of(id), writableNftStore.modifiedNfts());
    }

    @Test
    void removesByNftID() {
        // Set up the NFT state with an existing NFT

        final var ownerId = AccountID.newBuilder().accountNum(12345).build();
        final var nftToRemove =
                NftID.newBuilder().tokenId(fungibleTokenId).serialNumber(1).build();
        writableNftState = emptyWritableNftStateBuilder()
                .value(
                        nftToRemove,
                        Nft.newBuilder().nftId(nftToRemove).ownerId(ownerId).build())
                .build();
        assertTrue(writableNftState.contains(nftToRemove));
        given(writableStates.<NftID, Nft>get(NFTS_KEY)).willReturn(writableNftState);
        writableNftStore = new WritableNftStore(writableStates, writableEntityCounters);
        assertNotNull(writableNftStore.get(nftToRemove));

        writableNftStore.remove(nftToRemove);

        // Assert the NFT is removed
        assertNull(writableNftStore.get(nftToRemove));
        assertNull(writableNftStore.get(fungibleTokenId, 1));
    }

    @Test
    void removesByTokenIdAndSerialNum() {
        // Set up the NFT state with an existing NFT
        final var nftToRemove =
                NftID.newBuilder().tokenId(fungibleTokenId).serialNumber(1).build();
        final var ownerId = asAccount(0L, 0L, 12345);
        writableNftState = emptyWritableNftStateBuilder()
                .value(
                        nftToRemove,
                        Nft.newBuilder().nftId(nftToRemove).ownerId(ownerId).build())
                .build();
        assertTrue(writableNftState.contains(nftToRemove));
        given(writableStates.<NftID, Nft>get(NFTS_KEY)).willReturn(writableNftState);
        writableNftStore = new WritableNftStore(writableStates, writableEntityCounters);
        assertNotNull(writableNftStore.get(nftToRemove));

        writableNftStore.remove(nftToRemove.tokenId(), nftToRemove.serialNumber());

        // Assert the NFT is removed
        assertNull(writableNftStore.get(nftToRemove));
    }

    @Test
    void warmWarmsUnderlyingState(@Mock WritableKVState<NftID, Nft> nfts) {
        given(writableStates.<NftID, Nft>get(NFTS_KEY)).willReturn(nfts);
        final var nftStore = new WritableNftStore(writableStates, writableEntityCounters);
        final var id =
                NftID.newBuilder().tokenId(fungibleTokenId).serialNumber(1).build();
        nftStore.warm(id);
        verify(nfts).warm(id);
    }
}
