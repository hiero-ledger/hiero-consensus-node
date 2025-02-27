// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls.mutations;

import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode.INVALID_TOKEN_ID_FAILURE;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.INVALID_TOKEN_ADDRESS;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.contract.classiccalls.AbstractFailableNonStaticCall;
import com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;

public class UpdateTokenExpiryInfoFailableCall extends AbstractFailableNonStaticCall {
    private static final Function SIGNATURE =
            new Function("updateTokenExpiryInfo(address,(int64,address,int64))", "(int64)");

    public UpdateTokenExpiryInfoFailableCall() {
        super(EnumSet.of(INVALID_TOKEN_ID_FAILURE));
    }

    @Override
    public String name() {
        return "updateTokenExpiryInfo";
    }

    @Override
    public byte[] encodedCall(@NonNull final ClassicFailureMode mode, @NonNull final HapiSpec spec) {
        throwIfUnsupported(mode);
        // We only support the INVALID_TOKEN_ID_FAILURE mode
        return SIGNATURE
                .encodeCallWithArgs(INVALID_TOKEN_ADDRESS, Tuple.of(1L, INVALID_TOKEN_ADDRESS, 2L))
                .array();
    }
}
