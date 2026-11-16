// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.hints.HintsLibrary;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Real hinTS services for tests outside this package. */
public final class HintsServiceFixtures {
    private HintsServiceFixtures() {}

    /** Returns a real service that installs signing into the given context. */
    public static @NonNull HintsServiceImpl serviceOver(@NonNull final HintsContext context) {
        final var component = mock(HintsServiceComponent.class);
        when(component.signingContext()).thenReturn(context);
        when(component.controllers()).thenReturn(mock(HintsControllers.class));
        return new HintsServiceImpl(component, mock(HintsLibrary.class));
    }
}
