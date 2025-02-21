// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls.mutations;

import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode.INVALID_TOKEN_ID_FAILURE;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.INVALID_TOKEN_ADDRESS;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.contract.classiccalls.AbstractFailableNonStaticCall;
import com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;

public class BurnTokenFailableCall extends AbstractFailableNonStaticCall {
    private static final Function SIGNATURE = new Function("burnToken(address,int64,int64[])", "(int64)");

    public BurnTokenFailableCall() {
        super(EnumSet.of(INVALID_TOKEN_ID_FAILURE));
    }

    @Override
    public String name() {
        return "burnToken";
    }

    @Override
    public byte[] encodedCall(@NonNull final ClassicFailureMode mode, @NonNull final HapiSpec spec) {
        throwIfUnsupported(mode);
        return SIGNATURE
                .encodeCallWithArgs(INVALID_TOKEN_ADDRESS, 1L, new long[0])
                .array();
    }
}
