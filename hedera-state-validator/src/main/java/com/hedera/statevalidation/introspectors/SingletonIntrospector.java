// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.introspectors;

import static com.hedera.statevalidation.introspectors.IntrospectUtils.getCodecFor;

import com.swirlds.state.State;

public class SingletonIntrospector {

    private final State state;
    private final String serviceName;
    private final int stateId;

    public SingletonIntrospector(State state, String serviceName, int stateId) {
        this.state = state;
        this.serviceName = serviceName;
        this.stateId = stateId;
    }

    public void introspect() {
        Object singleton =
                state.getReadableStates(serviceName).getSingleton(stateId).get();
        System.out.println(getCodecFor(singleton).toJSON(singleton));
    }
}
