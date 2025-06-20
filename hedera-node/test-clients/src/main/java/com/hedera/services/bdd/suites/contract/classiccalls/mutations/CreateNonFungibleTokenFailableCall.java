// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls.mutations;

import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode.INVALID_ACCOUNT_ID_FAILURE;
import static com.hedera.services.bdd.suites.contract.classiccalls.ClassicInventory.INVALID_ACCOUNT_ADDRESS;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.contract.classiccalls.AbstractFailableNonStaticCall;
import com.hedera.services.bdd.suites.contract.classiccalls.ClassicFailureMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;

public class CreateNonFungibleTokenFailableCall extends AbstractFailableNonStaticCall {
    private static final Function SIGNATURE = new Function(
            "createNonFungibleToken((string,string,address,string,bool,uint32,bool,(uint256,(bool,address,bytes,bytes,address))[],(int64,address,int64)))",
            "(int64,address)");

    public CreateNonFungibleTokenFailableCall() {
        super(EnumSet.of(INVALID_ACCOUNT_ID_FAILURE));
    }

    @Override
    public String name() {
        return "createNonFungibleToken";
    }

    @Override
    public byte[] encodedCall(@NonNull final ClassicFailureMode mode, @NonNull final HapiSpec spec) {
        throwIfUnsupported(mode);
        // Must be INVALID_ACCOUNT_ID_FAILURE
        return SIGNATURE
                .encodeCallWithArgs(Tuple.from(
                        "Name",
                        "SYM",
                        INVALID_ACCOUNT_ADDRESS,
                        "memo",
                        false,
                        123L,
                        false,
                        new Tuple[0],
                        Tuple.of(1L, INVALID_ACCOUNT_ADDRESS, 2L)))
                .array();
    }
}
