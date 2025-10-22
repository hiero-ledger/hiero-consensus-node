// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.introspector;

import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

public class SingletonIntrospector {

    private final State state;
    private final String serviceName;
    private final int stateId;

    public SingletonIntrospector(@NonNull final State state, @NonNull final String serviceName, int stateId) {
        this.state = state;
        this.serviceName = serviceName;
        this.stateId = stateId;
    }

    public void introspect() {
        final Object singleton =
                state.getReadableStates(serviceName).getSingleton(stateId).get();
        Objects.requireNonNull(singleton);
        //noinspection unchecked
        System.out.println(StateUtils.getCodecFor(singleton).toJSON(singleton));
    }
}
