// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls.views;

import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode.INVALID_NFT_ID_FAILURE;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode.INVALID_TOKEN_ID_FAILURE;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.INVALID_TOKEN_ADDRESS;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.VALID_NON_FUNGIBLE_TOKEN_IDS;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.contract.classiccalls.AbstractFailableNonStaticCall;
import com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.EnumSet;

public class GetApprovedFailableCall extends AbstractFailableNonStaticCall {
    private static final Function SIGNATURE = new Function("getApproved(address,uint256)");

    public GetApprovedFailableCall() {
        super(EnumSet.of(INVALID_TOKEN_ID_FAILURE, INVALID_NFT_ID_FAILURE));
    }

    @Override
    public String name() {
        return "getApproved";
    }

    @Override
    public byte[] encodedCall(@NonNull final ClassicFailureMode mode, @NonNull final HapiSpec spec) {
        throwIfUnsupported(mode);
        if (mode == INVALID_TOKEN_ID_FAILURE) {
            return SIGNATURE
                    .encodeCallWithArgs(INVALID_TOKEN_ADDRESS, BigInteger.ONE)
                    .array();
        } else {
            // Must be INVALID_NFT_ID_FAILURE
            return SIGNATURE
                    .encodeCallWithArgs(
                            idAsHeadlongAddress(spec.registry().getTokenID(VALID_NON_FUNGIBLE_TOKEN_IDS[0])),
                            BigInteger.valueOf(Long.MAX_VALUE))
                    .array();
        }
    }
}
