// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_SCHEDULE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_METADATA_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression tests for the empty-KeyList token-key authorization bypass.
 *
 * <p>The empty {@link KeyList} (the {@code IMMUTABILITY_SENTINEL_KEY}) is only meaningful as a
 * "remove this key" marker for TokenUpdate (HIP-540). On TokenCreate it must be rejected for every
 * role key: an empty key list is dropped by the signing pipeline, which would otherwise leave the
 * corresponding role function (wipe, mint/burn, freeze, kyc, pause, fee schedule, metadata) requiring
 * no signature — i.e. permissionless for anyone.
 *
 * <p>The admin key is the sole exception: the sentinel is accepted there because it is the documented
 * way to create an immutable token, and such a token is correctly treated as immutable by TokenUpdate.
 */
@Tag(TOKEN)
public class EmptyKeyListTokenKeyTest {

    private static final String EMPTY_KEY = "emptyKeyListSentinel";
    private static final String TREASURY = "treasury";

    // The same value as KeyUtils.IMMUTABILITY_SENTINEL_KEY — duplicated here to avoid a
    // test-clients dependency on hapi-utils internals.
    private static final Key IMMUTABILITY_SENTINEL_KEY =
            Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build();

    @HapiTest
    final Stream<DynamicTest> tokenCreateRejectsEmptyKeyListRoleKeys() {
        return hapiTest(
                // Register the empty-KeyList sentinel under a name so the TokenCreate builder can use it.
                withOpContext((spec, _) -> spec.registry().saveKey(EMPTY_KEY, IMMUTABILITY_SENTINEL_KEY)),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),

                // Each role key set to the empty-KeyList sentinel must be rejected at create time with
                // the corresponding INVALID_*_KEY status, rather than stored as a permissionless key.
                // Sign explicitly (payer + treasury): the sentinel key has no signature control, and role
                // keys are not signing requirements for a creation anyway.
                tokenCreate("wipeT")
                        .treasury(TREASURY)
                        .wipeKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(INVALID_WIPE_KEY),
                tokenCreate("supplyT")
                        .treasury(TREASURY)
                        .supplyKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(INVALID_SUPPLY_KEY),
                tokenCreate("kycT")
                        .treasury(TREASURY)
                        .kycKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(INVALID_KYC_KEY),
                tokenCreate("freezeT")
                        .treasury(TREASURY)
                        .freezeKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(INVALID_FREEZE_KEY),
                tokenCreate("pauseT")
                        .treasury(TREASURY)
                        .pauseKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(INVALID_PAUSE_KEY),
                tokenCreate("feeT")
                        .treasury(TREASURY)
                        .feeScheduleKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(INVALID_CUSTOM_FEE_SCHEDULE_KEY),
                tokenCreate("metaT")
                        .treasury(TREASURY)
                        .metadataKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(INVALID_METADATA_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCreateAllowsEmptyKeyListAdminKeyAsImmutable() {
        return hapiTest(
                withOpContext((spec, opLog) -> spec.registry().saveKey(EMPTY_KEY, IMMUTABILITY_SENTINEL_KEY)),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),

                // An empty-KeyList admin key is the documented way to create an immutable token, so the
                // creation succeeds...
                tokenCreate("immutableT")
                        .treasury(TREASURY)
                        .adminKey(EMPTY_KEY)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),

                // ...and the resulting token is immutable: an admin-gated update is rejected rather than
                // succeeding without any admin signature.
                tokenUpdate("immutableT")
                        .name("renamed")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(TOKEN_IS_IMMUTABLE));
    }
}
