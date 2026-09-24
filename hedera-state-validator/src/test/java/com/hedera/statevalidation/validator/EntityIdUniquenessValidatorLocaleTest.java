// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.LOCALE)
class EntityIdUniquenessValidatorLocaleTest {

    /** Arabic (Egypt) renders {@code %d} with Arabic-Indic digits. */
    private static final Locale ARABIC_DIGITS = Locale.forLanguageTag("ar-EG");

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    @DisplayName("validate() reports the issue count in ASCII digits under any default locale")
    void validateReportsAsciiDigitsUnderAnyDefaultLocale() {
        final var validator = new EntityIdUniquenessValidator();
        validator.initialize(stateWhereNoEntityIsFound());

        // A token leaf whose ID resolves to no entity at all, so exactly one issue is recorded
        validator.processLeafBytes(1L, leafBytesFor(new TokenID(0, 0, 1234)));

        Locale.setDefault(ARABIC_DIGITS);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ValidationException.class)
                .hasMessage("[entityIdUniqueness] Validation failed: Expected <0> but was <1>");
    }

    @SuppressWarnings("unchecked")
    private static VirtualMapState stateWhereNoEntityIsFound() {
        final ReadableKVState<Object, Object> emptyState = mock(ReadableKVState.class);
        when(emptyState.get(any())).thenReturn(null);

        final ReadableStates states = mock(ReadableStates.class);
        when(states.get(anyInt())).thenReturn((ReadableKVState) emptyState);

        final VirtualMapState state = mock(VirtualMapState.class);
        when(state.getReadableStates(anyString())).thenReturn(states);
        return state;
    }

    private static VirtualLeafBytes<?> leafBytesFor(final TokenID tokenId) {
        final var key = StateKey.newBuilder().tokenServiceITokens(tokenId).build();
        return new VirtualLeafBytes<>(1L, StateKey.PROTOBUF.toBytes(key), null);
    }
}
