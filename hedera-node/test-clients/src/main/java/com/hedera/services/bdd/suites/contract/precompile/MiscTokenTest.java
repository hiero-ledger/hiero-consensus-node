// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("token")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
public class MiscTokenTest {

    @Contract(contract = "InternalCall", creationGas = 1_000_000L)
    static SpecContract internalCall;

    @FungibleToken(name = "fungibleToken")
    static SpecFungibleToken fungibleToken;

    @HapiTest
    @DisplayName("cannot transfer value to HTS")
    @Tag(MATS)
    public Stream<DynamicTest> cannotTransferValueToHts() {
        return hapiTest(internalCall
                .call("isATokenWithCall", fungibleToken)
                .sending(100L)
                .andAssert(txn -> txn.hasKnownStatus(INVALID_CONTRACT_ID)));
    }
}
