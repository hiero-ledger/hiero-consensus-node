// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.contracts;

/**
 * Enumerates the contracts that a Hedera native token "implements" by redirecting to a well-known
 * system contract address.
 */
public enum TokenRedirectContract {
    HRC("HRC"),
    HRC904("HRC904"),
    ERC20("ERC20ABI"),
    ERC721("ERC721ABI");

    TokenRedirectContract(String abiResource) {
        this.abiResource = abiResource;
    }

    /**
     * Returns the name of the resource with the ABI for this redirect contract.
     * @return the resource name
     */
    public String abiResource() {
        return abiResource;
    }

    private final String abiResource;
}
