// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
@OrderedInIsolation
public class DisabledPrecompileTest {
    @Contract(contract = "PrecompileCaller", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @FungibleToken(name = "fungibleToken")
    static SpecFungibleToken ft;

    @HapiTest
    @DisplayName("Calling a disabled precompile reverts")
    public Stream<DynamicTest> callDisabledPrecompile() {
        return hapiTest(
                overriding("contracts.precompile.disabled", "2"),
                contract.call("callSha256AndIsToken", "submit".getBytes(), ft)
                        .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
    }

    @HapiTest
    @DisplayName("Calling a enabled precompile is successful")
    public Stream<DynamicTest> callEnabledPrecompile() {
        return hapiTest(
                overriding("contracts.precompile.disabled", ""),
                contract.call("callSha256AndIsToken", "submit".getBytes(), ft)
                        .andAssert(txn -> txn.hasKnownStatus(SUCCESS)));
    }
}
