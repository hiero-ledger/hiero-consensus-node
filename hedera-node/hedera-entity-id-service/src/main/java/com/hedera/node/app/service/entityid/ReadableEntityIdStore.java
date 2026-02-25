// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

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
