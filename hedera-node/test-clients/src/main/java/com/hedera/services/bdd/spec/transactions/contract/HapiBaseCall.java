// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.contract;

import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.encodeParametersForCall;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;

import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import java.util.Optional;
import java.util.function.Supplier;
import org.hiero.base.utility.CommonUtils;

public abstract class HapiBaseCall<T extends HapiTxnOp<T>> extends HapiTxnOp<T> {

    public static final int HEXED_EVM_ADDRESS_LEN = 40;
    public static final String FALLBACK_ABI = "<empty>";
    protected boolean tryAsHexedAddressIfLenMatches = true;
    protected Optional<Object[]> params = Optional.empty();
    protected Optional<String> abi = Optional.empty();
    protected String contract;
    protected Optional<Long> gas = Optional.empty();
    protected Optional<Supplier<String>> explicitHexedParams = Optional.empty();
    protected String privateKeyRef = SECP_256K1_SOURCE_KEY;

    protected byte[] initializeCallData() {
        byte[] callData;
        if (explicitHexedParams.isPresent()) {
            callData = explicitHexedParams
                    .map(Supplier::get)
                    .map(CommonUtils::unhex)
                    .orElseThrow();
        } else {
            callData = encodeParametersForCall(params.orElse(null), abi.orElse(null));
        }

        return callData;
    }
}
