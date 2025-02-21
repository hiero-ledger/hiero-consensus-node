/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of {@link WritableSingletonState} that delegates to another {@link WritableSingletonState} as
 * though it were the backend data source. Modifications to this {@link WrappedWritableSingletonState} are
 * buffered, along with reads, allowing code to rollback by simply throwing away the wrapper.
 *
 * @param <T> the type of the state
 */
public class WrappedWritableSingletonState<T> extends WritableSingletonStateBase<T> {

    private final WritableSingletonState<T> delegate;

    /**
     * Create a new instance that will treat the given {@code delegate} as the backend data source.
     * Note that the lifecycle of the delegate <b>MUST</b> be as long as, or longer than, the
     * lifecycle of this instance. If the delegate is reset or decommissioned while being used as a
     * delegate, bugs will occur.
     *
     * @param delegate The delegate. Must not be null.
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public WrappedWritableSingletonState(@NonNull final WritableSingletonState<T> delegate) {
        super(delegate.getServiceName(), delegate.getStateKey());
        this.delegate = delegate;
    }

    @Override
    protected void putIntoDataSource(@NonNull T value) {
        delegate.put(value);
    }

    @Override
    protected void removeFromDataSource() {
        delegate.put(null);
    }

    @Override
    protected T readFromDataSource() {
        return delegate.get();
    }
}
