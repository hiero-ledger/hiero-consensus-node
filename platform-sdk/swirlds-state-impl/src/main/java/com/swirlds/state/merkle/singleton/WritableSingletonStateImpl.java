// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.singleton;

import com.swirlds.state.spi.WritableSingletonStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;

public class WritableSingletonStateImpl<T> extends WritableSingletonStateBase<T> {
    public WritableSingletonStateImpl(@NonNull final String stateKey, @NonNull final SingletonNode<T> node) {
        super(stateKey, node::getValue, node::setValue);
    }
}
