// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.meta;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.bytecodePath;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import java.math.BigInteger;
import java.util.List;

public enum SupportedContract {
    SIMPLE_STORAGE(
            bytecodePath(Constants.SIMPLE_STORAGE1),
            List.of(new ContractCallDetails(
                    getABIFor(FUNCTION, "set", Constants.SIMPLE_STORAGE1), new Object[] {BigInteger.valueOf(1)})),
            List.of(new ContractCallDetails(getABIFor(FUNCTION, "get", Constants.SIMPLE_STORAGE1), new Object[] {})));

    private final String pathToBytecode;
    private final List<ContractCallDetails> callDetails;
    private final List<ContractCallDetails> localCallDetails;

    SupportedContract(
            String pathToBytecode, List<ContractCallDetails> callDetails, List<ContractCallDetails> localCallDetails) {
        this.pathToBytecode = pathToBytecode;
        this.callDetails = callDetails;
        this.localCallDetails = localCallDetails;
    }

    public List<ContractCallDetails> getCallDetails() {
        return callDetails;
    }

    public List<ContractCallDetails> getLocalCallDetails() {
        return localCallDetails;
    }

    public String getPathToBytecode() {
        return pathToBytecode;
    }

    private static class Constants {
        static final String SIMPLE_STORAGE1 = "SimpleStorage";
    }
}
