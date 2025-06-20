// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.lifecycle;

/**
 * Type of the entity
 */
public enum EntityType {
    /**
     * If the entity is a crypto account
     */
    Crypto,
    /**
     * If the entity is a FCQueue
     */
    FCQ,
    /**
     * If the entity is an NFT
     */
    NFT,
    /**
     * If the entity is an Virtual Merkle related entity
     */
    VIRTUAL_MERKLE_ACCOUNT
}
