// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.introspectors;

import static com.hedera.statevalidation.introspectors.IntrospectUtils.getCodecFor;

import com.swirlds.state.State;

public class SingletonIntrospector {

    private final State state;
    private final String serviceName;
    private final String stateName;

    public SingletonIntrospector(State state, String serviceName, String stateName) {
        this.state = state;
        this.serviceName = serviceName;
        this.stateName = stateName;
    }

    public void introspect() {
        Object singleton =
                state.getReadableStates(serviceName).getSingleton(stateName).get();
        System.out.println(getCodecFor(singleton).toJSON(singleton));
    }
}
