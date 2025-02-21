/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
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

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides mutable access to singleton state.
 *
 * @param <T> The type of the state
 */
public interface WritableSingletonState<T> extends ReadableSingletonState<T> {

    /**
     * Sets the given value on this state.
     *
     * @param value The value. May be null.
     */
    void put(@Nullable T value);

    /**
     * Gets whether the {@link #put(Object)} method has been called on this instance.
     *
     * @return True if the {@link #put(Object)} method has been called
     */
    boolean isModified();
}
