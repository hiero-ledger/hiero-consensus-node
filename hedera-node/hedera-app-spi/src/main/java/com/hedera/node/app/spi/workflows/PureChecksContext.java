/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the context of a single {@code pureChecks()}-call.
 */
@SuppressWarnings("UnusedReturnValue")
public interface PureChecksContext {

    /**
     * Gets the {@link TransactionBody}
     *
     * @return the {@link TransactionBody} in this context
     */
    @NonNull
    TransactionBody body();

    /**
     * Returns the current {@link Configuration}.
     *
     * @return the {@link Configuration}
     */
    @NonNull
    Configuration configuration();
}
