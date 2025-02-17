/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

public class CreateFungibleTokenFailableCall extends AbstractFailableNonStaticCall {
    private static final Function SIGNATURE = new Function(
            "createFungibleToken((string,string,address,string,bool,uint32,bool,(uint256,(bool,address,bytes,bytes,address))[],(int64,address,int64)),int64,int32)",
            "(int64,address)");

    public CreateFungibleTokenFailableCall() {
        super(EnumSet.of(INVALID_ACCOUNT_ID_FAILURE));
    }

    @Override
    public String name() {
        return "createFungibleToken";
    }

    @Override
    public byte[] encodedCall(@NonNull final ClassicFailureMode mode, @NonNull final HapiSpec spec) {
        throwIfUnsupported(mode);
        // Must be INVALID_ACCOUNT_ID_FAILURE
        return SIGNATURE
                .encodeCallWithArgs(
                        Tuple.from(
                                "Name",
                                "SYM",
                                INVALID_ACCOUNT_ADDRESS,
                                "memo",
                                false,
                                123L,
                                false,
                                new Tuple[0],
                                Tuple.of(1L, INVALID_ACCOUNT_ADDRESS, 2L)),
                        1L,
                        2)
                .array();
    }
}
