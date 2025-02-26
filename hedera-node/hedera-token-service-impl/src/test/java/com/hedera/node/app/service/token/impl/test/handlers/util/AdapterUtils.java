// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.util;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.CURRENTLY_UNUSED_ALIAS;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.FIRST_TOKEN_SENDER;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.FIRST_TOKEN_SENDER_LITERAL_ALIAS;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.NO_RECEIVER_SIG;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.NO_RECEIVER_SIG_ALIAS;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.RECEIVER_SIG;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.RECEIVER_SIG_ALIAS;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.util.Map;
import org.mockito.Mockito;

/**
 * Utility class for creating {@link ReadableAccountStore} objects.
 */
public class AdapterUtils {
    private static final String ALIASES_KEY = "ALIASES";

    private AdapterUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns a {@link ReadableStates} object that contains the given key-value pairs.
     * @param keysToMock the key-value pairs
     * @return the {@link ReadableStates} object
     */
    public static ReadableStates mockStates(final Map<String, ReadableKVState> keysToMock) {
        final var mockStates = Mockito.mock(ReadableStates.class);
        keysToMock.forEach((key, state) -> given(mockStates.get(key)).willReturn(state));
        return mockStates;
    }

    /**
     * Returns a {@link WritableStates} object that contains the given key-value pairs.
     * @param keysToMock the key-value pairs
     * @return the {@link WritableStates} object
     */
    public static WritableStates mockWritableStates(final Map<String, WritableKVState> keysToMock) {
        final var mockStates = Mockito.mock(WritableStates.class);
        keysToMock.forEach((key, state) -> given(mockStates.get(key)).willReturn(state));
        return mockStates;
    }

    /**
     * Returns a {@link WritableKVState} object that contains the well-known aliases used in a {@code SigRequirementsTest}
     * @return the well-known aliases state
     */
    public static MapWritableKVState<ProtoBytes, AccountID> wellKnownAliasState() {
        final Map<ProtoBytes, AccountID> wellKnownAliases = Map.ofEntries(
                Map.entry(new ProtoBytes(Bytes.wrap(CURRENTLY_UNUSED_ALIAS)), asAccount(0L, 0L, 0L)),
                Map.entry(new ProtoBytes(Bytes.wrap(NO_RECEIVER_SIG_ALIAS)), toPbj(NO_RECEIVER_SIG)),
                Map.entry(new ProtoBytes(Bytes.wrap(RECEIVER_SIG_ALIAS)), toPbj(RECEIVER_SIG)),
                Map.entry(
                        new ProtoBytes(Bytes.wrap(FIRST_TOKEN_SENDER_LITERAL_ALIAS.toByteArray())),
                        toPbj(FIRST_TOKEN_SENDER)));
        return new MapWritableKVState<>(TokenService.NAME, ALIASES_KEY, wellKnownAliases);
    }
}
