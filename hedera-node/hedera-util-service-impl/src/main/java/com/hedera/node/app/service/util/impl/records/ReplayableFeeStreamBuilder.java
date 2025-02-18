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

package com.hedera.node.app.service.util.impl.records;

import com.hedera.hapi.node.base.TransferList;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;

/**
 * A {@code StreamBuilder} specialization that supports updating its transfer list to the result
 * of replaying fee charging events.
 */
public interface ReplayableFeeStreamBuilder extends StreamBuilder {
    /**
     * Sets the transfer list to the result of replaying the fees charged in the transaction.
     * @param transferList the transfer list to set
     * @throws IllegalStateException if the builder has not been rolled back
     */
    void setReplayedFees(TransferList transferList);
}
