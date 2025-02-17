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

package com.hedera.node.app.spi.api;

import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A factory for creating an API scoped to a specific service.
 *
 * @param <T> the type of the service API
 */
public interface ServiceApiProvider<T> {
    /**
     * Returns the name of the service whose writable state this API is scoped to.
     *
     * @return the name of the service
     */
    String serviceName();

    /**
     * Creates a new instance of the service API.
     *
     * @param configuration  the node configuration
     * @param writableStates the writable state of the service
     * @param entityCounters
     * @return the new API instance
     */
    T newInstance(
            @NonNull Configuration configuration,
            @NonNull WritableStates writableStates,
            @NonNull final WritableEntityCounters entityCounters);
}
