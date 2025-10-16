// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts.precompile;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_16C;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.METADATA_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
class AtomicBatchAddress16cTest {

    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // enable atomic batch
        testLifecycle.overrideInClass(Map.of(
                "contracts.throttle.throttleByGas", "false",
                "contracts.systemContract.hts.addresses", "359,364"));
        // create default batch operator
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    /**
     * NumericValidation16c
     */
    @HapiTest
    @DisplayName("when using atomic getTokenKey should return metadata key")
    Stream<DynamicTest> atomicSucceedToGetTokenKey(
            @Contract(contract = "NumericContract16c", creationGas = 1_000_000L, variant = VARIANT_16C)
                    final SpecContract numericContract,
            @NonFungibleToken(
                            numPreMints = 5,
                            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY})
                    final SpecNonFungibleToken nft) {
        return hapiTest(numericContract
                .call("getTokenKey", nft, BigInteger.valueOf(128L))
                .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                .gas(100_000L)
                .andAssert(txn -> txn.hasKnownStatus(SUCCESS)));
    }

    /**
     * TokenMetadataTest
     */
    @HapiTest
    @Tag(MATS)
    Stream<DynamicTest> atomicTestUpdateMetadata(
            @Contract(contract = "CreateTokenVersioned", creationGas = 5_000_000L, variant = VARIANT_16C)
                    final SpecContract contractTarget,
            @FungibleToken(name = "fungibleToken", initialSupply = 1_000L, maxSupply = 1_200L)
                    final SpecFungibleToken fungibleToken,
            @NonFungibleToken(
                            numPreMints = 5,
                            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY})
                    final SpecNonFungibleToken nft) {
        return hapiTest(
                nft.authorizeContracts(contractTarget)
                        .alsoAuthorizing(TokenKeyType.SUPPLY_KEY, TokenKeyType.METADATA_KEY),
                fungibleToken.authorizeContracts(contractTarget),
                contractTarget
                        .call("updateTokenMetadata", fungibleToken, "randomMetaNew777")
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(SUCCESS)),
                fungibleToken.getInfo().andAssert(info -> info.hasMetadata("randomMetaNew777")),
                contractTarget
                        .call("updateTokenMetadata", nft, "randomMetaNew777")
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(SUCCESS)),
                nft.getInfo().andAssert(info -> info.hasMetadata("randomMetaNew777")));
    }
}
