// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.test.handlers.util.TokenHandlerTestBase;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableTokenStoreTest extends TokenHandlerTestBase {
    private Token token;

    @Test
    void throwsIfNullValuesAsArgs() {
        assertThrows(NullPointerException.class, () -> new WritableTokenStore(null, writableEntityCounters));
        assertThrows(NullPointerException.class, () -> new WritableTokenStore(writableStates, null));
        assertThrows(NullPointerException.class, () -> writableTokenStore.put(null));
    }

    @Test
    void constructorCreatesTokenState() {
        final var store = new WritableTokenStore(writableStates, writableEntityCounters);
        assertNotNull(store);
    }

    @Test
    void getReturnsImmutableToken() {
        token = createToken();
        writableTokenStore.put(token);

        final var readToken = writableTokenStore.get(tokenId);

        assertEquals(token, readToken);
    }

    @Test
    void putsTokenChangesToStateInModifications() {
        token = createToken();
        assertFalse(writableTokenState.contains(tokenId));

        // put, keeps the token in the modifications
        writableTokenStore.put(token);

        assertTrue(writableTokenState.contains(tokenId));
        final var writtenToken = writableTokenState.get(tokenId);
        assertEquals(token, writtenToken);
    }

    @Test
    void getsSizeOfState() {
        token = createToken();
        assertEquals(0, writableTokenStore.sizeOfState());
        assertEquals(Collections.EMPTY_SET, writableTokenStore.modifiedTokens());
        writableTokenStore.putAndIncrementCount(token);
        given(writableEntityCounters.getCounterFor(EntityType.TOKEN)).willReturn(1L);

        assertEquals(1, writableTokenStore.sizeOfState());
        assertEquals(Set.of(tokenId), writableTokenStore.modifiedTokens());
    }
}
