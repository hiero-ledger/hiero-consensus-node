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

public interface ReadableEntityIdStore extends ReadableEntityCounters {
    /**
     * Returns the next entity number that will be used.
     *
     * @return the next entity number that will be used
     */
    long peekAtNextNumber();

    /**
     * Returns the number of accounts in the store.
     *
     * @return the number of accounts in the store
     */
    long numAccounts();

    /**
     * Returns the number of tokens in the store.
     *
     * @return the number of tokens in the store
     */
    long numTokens();

    /**
     * Returns the number of files in the store.
     *
     * @return the number of files in the store
     */
    long numFiles();

    /**
     * Returns the number of topics in the store.
     *
     * @return the number of topics in the store
     */
    long numTopics();

    /**
     * Returns the number of contracts in the store.
     *
     * @return the number of contracts in the store
     */
    long numContractBytecodes();

    /**
     * Returns the number of contract storage slots in the store.
     *
     * @return the number of contract storage slots in the store
     */
    long numContractStorageSlots();

    /**
     * Returns the number of NFTs in the store.
     *
     * @return the number of NFTs in the store
     */
    long numNfts();

    /**
     * Returns the number of token relations in the store.
     *
     * @return the number of token relations in the store
     */
    long numTokenRelations();

    /**
     * Returns the number of aliases in the store.
     *
     * @return the number of aliases in the store
     */
    long numAliases();

    /**
     * Returns the number of schedules in the store.
     *
     * @return the number of schedules in the store
     */
    long numSchedules();

    /**
     * Returns the number of airdrops in the store.
     *
     * @return the number of airdrops in the store
     */
    long numAirdrops();

    /**
     * Returns the number of nodes in the store.
     *
     * @return the number of nodes in the store
     */
    long numNodes();
}
