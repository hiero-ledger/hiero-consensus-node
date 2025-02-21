/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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
@OrderedInIsolation
@HapiTestLifecycle
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
