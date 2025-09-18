// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import edu.umd.cs.findbugs.annotations.NonNull;

public class StateIds {

    private StateIds() {}

    public static int stateIdFor(@NonNull final String serviceName, @NonNull final String stateKey) {
        int stateId = singletonStateIdFor(serviceName, stateKey);
        if (stateId > 0) {
            return stateId;
        }
        return stateIdFromStateKey(serviceName, stateKey);
    }

    private static int singletonStateIdFor(@NonNull final String serviceName, @NonNull final String stateKey) {
        for (final SingletonType singleton : SingletonType.values()) {
            final String name = singleton.name();
            if (name.equals(serviceName.toUpperCase() + "_I_" + stateKey.toUpperCase())) {
                return singleton.protoOrdinal();
            }
        }
        return -1;
    }

    private static int stateIdFromStateKey(@NonNull final String serviceName, @NonNull final String stateKey) {
        for (final StateKey.KeyOneOfType key : StateKey.KeyOneOfType.values()) {
            final String name = key.name();
            if (name.equals(serviceName.toUpperCase() + "_I_" + stateKey.toUpperCase())) {
                return key.protoOrdinal();
            }
        }
        return -1;
    }
}
