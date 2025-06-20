// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.singleton;

import com.swirlds.state.spi.ReadableSingletonStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ReadableSingletonStateImpl<T> extends ReadableSingletonStateBase<T> {
    public ReadableSingletonStateImpl(@NonNull final String stateKey, @NonNull final SingletonNode<T> node) {
        super(stateKey, node::getValue);
    }
}
