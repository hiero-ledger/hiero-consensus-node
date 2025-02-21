// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

/**
 * A record of the number of storage slots used by a contract and the consensus second at which
 * those slots will expire.
 *
 * @param numSlotsUsed the number of storage slots used by the contract
 * @param expiry the consensus second at which the storage slots will expire
 */
public record RentFactors(int numSlotsUsed, long expiry) {}
