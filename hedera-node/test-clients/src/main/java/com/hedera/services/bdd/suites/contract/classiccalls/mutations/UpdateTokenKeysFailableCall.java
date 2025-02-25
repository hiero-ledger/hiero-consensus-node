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

public class UpdateTokenKeysFailableCall extends AbstractFailableNonStaticCall {
    private static final Function SIGNATURE =
            new Function("updateTokenKeys(address,(uint256,(bool,address,bytes,bytes,address))[])", "(int64)");

    public UpdateTokenKeysFailableCall() {
        super(EnumSet.of(INVALID_TOKEN_ID_FAILURE));
    }

    @Override
    public String name() {
        return "updateTokenKeys";
    }

    @Override
    public byte[] encodedCall(@NonNull final ClassicFailureMode mode, @NonNull final HapiSpec spec) {
        throwIfUnsupported(mode);
        // We only support the INVALID_TOKEN_ID_FAILURE mode
        return SIGNATURE.encodeCallWithArgs(INVALID_TOKEN_ADDRESS, new Tuple[0]).array();
    }
}
