// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.meta;

public class ContractCallDetails {
    private final String abi;
    private final Object[] exampleArgs;

    public ContractCallDetails(String abi, Object[] exampleArgs) {
        this.abi = abi;
        this.exampleArgs = exampleArgs;
    }

    public String getAbi() {
        return abi;
    }

    public Object[] getExampleArgs() {
        return exampleArgs;
    }
}
