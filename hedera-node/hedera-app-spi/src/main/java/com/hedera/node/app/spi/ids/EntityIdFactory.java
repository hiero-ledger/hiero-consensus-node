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

package com.hedera.node.app.spi.ids;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * A strategy for creating entity ids.
 */
public interface EntityIdFactory {
    /**
     * Returns a token id for the given number.
     * @param number the number
     */
    TokenID newTokenId(long number);

    /**
     * Returns a topic id for the given number.
     * @param number the number
     */
    TopicID newTopicId(long number);

    /**
     * Returns a schedule id for the given number.
     * @param number the number
     */
    ScheduleID newScheduleId(long number);

    /**
     * Returns a contract id for the given number.
     * @param number the number
     * @return a new ContractID instance
     */
    ContractID newContractId(long number);

    /**
     * Returns an account id for the given number.
     * @param number the number
     * @return a new AccountID instance
     */
    AccountID newAccountId(long number);

    /**
     * Returns an account id for the given alias.
     * @param alias the alias
     * @return a new AccountID instance
     */
    AccountID newAccountId(Bytes alias);

    /**
     * Returns a hexadecimal string representation of the given number,
     * including current shard and realm, zero-padded to 20 bytes.
     * @param number the number
     * @return a hexadecimal string representation of the number
     */
    String hexLongZero(long number);
}
