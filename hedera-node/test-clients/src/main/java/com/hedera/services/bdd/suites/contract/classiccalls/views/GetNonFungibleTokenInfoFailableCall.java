// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls.views;

import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode.INVALID_NFT_ID_FAILURE;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode.INVALID_TOKEN_ID_FAILURE;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.INVALID_TOKEN_ADDRESS;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.contract.classiccalls.AbstractFailableStaticCall;
import com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode;
import com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;

public class GetNonFungibleTokenInfoFailableCall extends AbstractFailableStaticCall {
    private static final Function SIGNATURE = new Function("getNonFungibleTokenInfo(address,int64)");

    public GetNonFungibleTokenInfoFailableCall() {
        super(EnumSet.of(INVALID_TOKEN_ID_FAILURE, INVALID_NFT_ID_FAILURE));
    }

    @Override
    public String name() {
        return "getNonFungibleTokenInfo";
    }

    @Override
    public byte[] encodedCall(@NonNull final ClassicFailureMode mode, @NonNull final HapiSpec spec) {
        throwIfUnsupported(mode);
        if (mode == INVALID_TOKEN_ID_FAILURE) {
            return SIGNATURE.encodeCallWithArgs(INVALID_TOKEN_ADDRESS, 1L).array();
        } else {
            // Must be INVALID_NFT_ID_FAILURE
            return SIGNATURE
                    .encodeCallWithArgs(
                            idAsHeadlongAddress(
                                    spec.registry().getTokenID(ClassicInventory.VALID_NON_FUNGIBLE_TOKEN_IDS[0])),
                            Long.MAX_VALUE)
                    .array();
        }
    }
}
