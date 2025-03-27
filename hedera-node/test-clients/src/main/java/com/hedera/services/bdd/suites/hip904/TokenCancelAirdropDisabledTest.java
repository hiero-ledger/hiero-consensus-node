// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCancelAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenCancelAirdrop.pendingAirdrop;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
@HapiTestLifecycle
@DisplayName("Disabled token cancel airdrop")
public class TokenCancelAirdropDisabledTest extends TokenAirdropBase {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("tokens.airdrops.cancel.enabled", "false"));
        lifecycle.doAdhoc(setUpTokensAndAllReceivers());
    }

    @HapiTest
    @DisplayName("not supported")
    final Stream<DynamicTest> notSupported() {
        return hapiTest(tokenCancelAirdrop(pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                .hasPrecheck(ResponseCodeEnum.NOT_SUPPORTED));
    }
}
