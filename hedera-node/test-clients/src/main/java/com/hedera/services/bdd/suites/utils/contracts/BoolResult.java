// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.contracts;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import org.apache.tuweni.bytes.Bytes;

public class BoolResult implements ContractCallResult {
    final boolean expected;

    public BoolResult(boolean expected) {
        this.expected = expected;
    }

    public static BoolResult flag(boolean expected) {
        return new BoolResult(expected);
    }

    @Override
    public Bytes getBytes() {
        final var boolType = TupleType.parse("(bool)");
        final var result = Tuple.singleton(expected);
        return Bytes.wrap(boolType.encode(result).array());
    }
}
