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

package com.hedera.services.bdd.suites.contract.precompile.address_16c;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_16C;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FREEZE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.KYC_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.WIPE_KEY;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;

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
@DisplayName("tokenType")
@HapiTestLifecycle
public class TokenTypeTest {
    @Contract(contract = "TokenAndTypeCheck", creationGas = 4_000_000L, variant = VARIANT_16C)
    static SpecContract tokenTypeCheckContract;

    @FungibleToken(
            name = "immutableToken",
            keys = {FEE_SCHEDULE_KEY, SUPPLY_KEY, WIPE_KEY, PAUSE_KEY, FREEZE_KEY, KYC_KEY})
    static SpecFungibleToken immutableToken;

    @HapiTest
    @DisplayName("get token type")
    public Stream<DynamicTest> cannotUpdateMissingToken() {
        return hapiTest(
                overriding("contracts.systemContract.hts.addresses", "359,364"),
                tokenTypeCheckContract.call("getType", immutableToken));
    }
}
