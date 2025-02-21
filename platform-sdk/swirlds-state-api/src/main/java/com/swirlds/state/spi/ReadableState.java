/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

public interface ReadableState {

    /**
     * Gets the name of the service to which this state belongs.
     *
     * <p>The call is idempotent, always returning the same value. It must never return null.
     *
     * @return The name of the service. This will never be null, and will always be the same value for an
     *     instance of {@link ReadableState}.
     */
    String getServiceName();

    /**
     * Gets the "state key" that uniquely identifies this {@link ReadableState} within the
     * {@link Schema} which are scoped to the service implementation. The key is therefore not globally
     * unique, only unique within the service implementation itself.
     *
     * <p>The call is idempotent, always returning the same value. It must never return null.
     *
     * @return The state key. This will never be null, and will always be the same value for an
     *     instance of {@link ReadableState}.
     */
    @NonNull
    String getStateKey();
}
