// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.signedstate;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

public class SignedStateListContainer implements SignedStateHolder {

    final List<ReservedSignedState> collectedSignedStates = new ArrayList<>();

    @Override
    public void interceptReservedSignedState(@NonNull final ReservedSignedState signedState) {
        collectedSignedStates.add(signedState);
    }

    @Override
    public void clear(@NonNull final Object ignored) {
        collectedSignedStates.clear();
    }
}
