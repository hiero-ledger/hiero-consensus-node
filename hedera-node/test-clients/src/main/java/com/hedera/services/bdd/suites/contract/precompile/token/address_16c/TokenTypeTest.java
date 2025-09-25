// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token.address_16c;

import static com.hedera.services.bdd.junit.TestTags.MATS;
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
    @Tag(MATS)
    public Stream<DynamicTest> cannotUpdateMissingToken() {
        return hapiTest(
                overriding("contracts.systemContract.hts.addresses", "359,364"),
                tokenTypeCheckContract.call("getType", immutableToken));
    }
}
