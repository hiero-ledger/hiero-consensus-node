// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.meta;

public class ActionableContractCall {
    private final String contract;
    private final ContractCallDetails details;

    public ActionableContractCall(String contract, ContractCallDetails details) {
        this.details = details;
        this.contract = contract;
    }

    public String getContract() {
        return contract;
    }

    public ContractCallDetails getDetails() {
        return details;
    }
}
