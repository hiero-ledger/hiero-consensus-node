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

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides stateful access to a singleton type. Most state in Hedera is k/v state, represented by
 * {@link ReadableKVState}. But some state is not based on a map, but is rather a single instance,
 * such as the AddressBook information. This type can be used to access that state.
 *
 * @param <T> The type of the state, such as an AddressBook or NetworkData.
 */
public interface ReadableSingletonState<T> extends ReadableState {

    /**
     * Gets the singleton value.
     *
     * @return The value, or null if there is no value.
     */
    @Nullable
    T get();

    /**
     * Gets whether the value of this {@link ReadableSingletonState} has been read.
     *
     * @return true if {@link #get()} has been called on this instance
     */
    boolean isRead();
}
