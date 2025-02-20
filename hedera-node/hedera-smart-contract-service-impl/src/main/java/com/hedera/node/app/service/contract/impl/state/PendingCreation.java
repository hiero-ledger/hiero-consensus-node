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

package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.aliasFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.datatypes.Address;

public record PendingCreation(
        @NonNull Address address, long number, long parentNumber, @Nullable ContractCreateTransactionBody body) {
    public PendingCreation {
        requireNonNull(address);
    }

    public boolean isHapiCreation() {
        return body != null;
    }

    @Nullable
    public Bytes aliasIfApplicable(final long shard, final long realm) {
        return isLongZero(shard, realm, address) ? null : aliasFrom(address);
    }
}
