// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.contracts;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import org.apache.tuweni.bytes.Bytes;

public class ErrorMessageResult implements ContractCallResult {
    String exists;

    private ErrorMessageResult(String result) {
        this.exists = result;
    }

    public static ErrorMessageResult errorMessageResult(String result) {
        return new ErrorMessageResult(result);
    }

    @Override
    public Bytes getBytes() {
        Function errFunction = new Function("Error(string)");
        final var result = Tuple.singleton(exists);
        return Bytes.wrap(errFunction.encodeCall(result).array());
    }
}
