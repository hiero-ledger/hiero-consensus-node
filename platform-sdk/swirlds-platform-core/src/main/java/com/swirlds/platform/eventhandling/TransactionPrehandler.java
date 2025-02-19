/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.eventhandling;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.event.PlatformEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Queue;

/**
 * Performs the prehandling of transactions
 */
public interface TransactionPrehandler {
    /**
     * Prehandles application transactions
     *
     * @param event the event to prehandle
     */
    @InputWireLabel("PlatformEvent")
    Queue<ScopedSystemTransaction<StateSignatureTransaction>> prehandleApplicationTransactions(
            @NonNull PlatformEvent event);
}
