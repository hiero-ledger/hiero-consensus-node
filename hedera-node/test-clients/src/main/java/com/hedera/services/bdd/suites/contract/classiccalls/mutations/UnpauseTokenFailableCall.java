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

public class UnpauseTokenFailableCall extends AbstractFailableNonStaticCall {
    private static final Function SIGNATURE = new Function("unpauseToken(address)", "(int64)");

    public UnpauseTokenFailableCall() {
        super(EnumSet.of(INVALID_TOKEN_ID_FAILURE));
    }

    @Override
    public String name() {
        return "unpauseToken";
    }

    @Override
    public byte[] encodedCall(@NonNull final ClassicFailureMode mode, @NonNull final HapiSpec spec) {
        throwIfUnsupported(mode);
        return SIGNATURE.encodeCallWithArgs(INVALID_TOKEN_ADDRESS).array();
    }
}
