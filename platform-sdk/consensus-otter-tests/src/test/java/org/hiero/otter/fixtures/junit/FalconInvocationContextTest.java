// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;

/**
 * Tests the display names and extensions of {@link FalconInvocationContext}.
 */
@DisplayName("Falcon invocation context Test")
class FalconInvocationContextTest {

    private static final ArgumentSet ARGUMENTS = ArgumentSet.of(2, Named.of("Balanced Weight", new Object()));

    @Test
    @DisplayName("A repetition of a sweep without arguments shows its position and its seed")
    void sweepWithoutArguments() {
        final FalconInvocationContext context = sweep(ArgumentSet.EMPTY);

        assertThat(context.getDisplayName(3)).isEqualTo("[3/5] seed=-42L");
        assertThat(context.getAdditionalExtensions()).noneMatch(ArgumentSetExtension.class::isInstance);
    }

    @Test
    @DisplayName("A repetition of a sweep with arguments also shows the argument set")
    void sweepWithArguments() {
        final FalconInvocationContext context = sweep(ARGUMENTS);

        assertThat(context.getDisplayName(3)).isEqualTo("[2, Balanced Weight] [3/5] seed=-42L");
        assertThat(context.getAdditionalExtensions()).anyMatch(ArgumentSetExtension.class::isInstance);
    }

    @Test
    @DisplayName("The position shown is the one within the sweep of the argument set, not the invocation index")
    void positionIsCountedPerArgumentSet() {
        // With several argument sets, JUnit's invocation index keeps counting across all of them
        assertThat(sweep(ARGUMENTS).getDisplayName(13)).isEqualTo("[2, Balanced Weight] [3/5] seed=-42L");
    }

    @Test
    @DisplayName("A replay shows the argument set, if any, and the seed")
    void replay() {
        assertThat(FalconInvocationContext.replay(ArgumentSet.EMPTY, 42L).getDisplayName(1))
                .isEqualTo("[replay] seed=42L");
        assertThat(FalconInvocationContext.replay(ARGUMENTS, 42L).getDisplayName(2))
                .isEqualTo("[2, Balanced Weight] [replay] seed=42L");
    }

    private static FalconInvocationContext sweep(final ArgumentSet argumentSet) {
        return FalconInvocationContext.sweep(argumentSet, 3, 5, -42L, new AtomicInteger(), 1);
    }
}
