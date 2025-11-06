// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls.views;

import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode.INVALID_TOKEN_ID_FAILURE;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode.NO_REASON_TO_FAIL;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.INVALID_TOKEN_ADDRESS;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.contract.classiccalls.AbstractFailableStaticCall;
import com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode;
import com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;

public class GetTokenTypeFailableCall extends AbstractFailableStaticCall {
    private static final Function SIGNATURE = new Function("getTokenType(address)", "(int64,int32)");

    public GetTokenTypeFailableCall() {
        super(EnumSet.of(INVALID_TOKEN_ID_FAILURE, NO_REASON_TO_FAIL));
    }

    @Override
    public String name() {
        return "getTokenType";
    }

    @Override
    public byte[] encodedCall(@NonNull final ClassicFailureMode mode, @NonNull final HapiSpec spec) {
        throwIfUnsupported(mode);
        if (mode == NO_REASON_TO_FAIL) {
            final var tokenAddress =
                    idAsHeadlongAddress(spec.registry().getTokenID(ClassicInventory.VALID_FUNGIBLE_TOKEN_IDS[0]));
            return SIGNATURE.encodeCallWithArgs(tokenAddress).array();
        } else {
            return SIGNATURE.encodeCallWithArgs(INVALID_TOKEN_ADDRESS).array();
        }
    }
}
