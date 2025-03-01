// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.address_16c;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_16C;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.METADATA_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_SERIAL_NUMBERS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class NumericValidation16c {

    @Contract(contract = "NumericContract16c", creationGas = 1_000_000L, variant = VARIANT_16C)
    static SpecContract numericContract;

    @NonFungibleToken(
            numPreMints = 5,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY})
    static SpecNonFungibleToken nft;

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("when using updateNFTsMetadata for specific NFT from NFT collection with invalid serial number")
    public Stream<DynamicTest> failToUpdateNFTsMetadata() {
        return Stream.of(new long[] {Long.MAX_VALUE}, new long[] {0}, new long[] {-1, 1}, new long[] {-1})
                .flatMap(invalidSerialNumbers -> hapiTest(numericContract
                        .call("updateNFTsMetadata", nft, invalidSerialNumbers, "tiger".getBytes())
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_NFT_ID))));
    }

    @HapiTest
    @DisplayName("when using updateNFTsMetadata for specific NFT from NFT collection with empty serial numbers")
    public Stream<DynamicTest> failToUpdateNFTsMetadataWithEmptySerialNumbers() {
        return hapiTest(numericContract
                .call("updateNFTsMetadata", nft, new long[] {}, "zebra".getBytes())
                .gas(1_000_000L)
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, MISSING_SERIAL_NUMBERS)));
    }
}
