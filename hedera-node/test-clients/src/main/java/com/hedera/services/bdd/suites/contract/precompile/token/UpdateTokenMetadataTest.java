// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_16C;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.METADATA_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.METADATA_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("metadataUpdateTests")
@SuppressWarnings("java:S1192") // literals are duplicated for readability
@HapiTestLifecycle
public class UpdateTokenMetadataTest {

    @Contract(contract = "UpdateTokenMetadata", creationGas = 4_000_000L, variant = VARIANT_16C)
    static SpecContract updateTokenMetadata;

    @NonFungibleToken(
            numPreMints = 10,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY})
    static SpecNonFungibleToken nft;

    @Account(maxAutoAssociations = 100, tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount treasury;

    @Nested
    @DisplayName("use TokenUpdateNFTs HAPI operation, to update metadata of individual NFTs")
    class TokenUpdateNFTsTests {

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
            nft.setTreasury(treasury);
            testLifecycle.doAdhoc(nft.authorizeContracts(updateTokenMetadata)
                    .alsoAuthorizing(TokenKeyType.METADATA_KEY, TokenKeyType.SUPPLY_KEY));
        }

        @HapiTest
        @DisplayName("use updateMetadataForNFTs to correctly update metadata for 1 NFT")
        @Tag(MATS)
        public Stream<DynamicTest> usingUpdateMetadataForNFTsWorksForSingleNFT() {
            final int serialNumber = 1;
            return hapiTest(
                    nft.getInfo(serialNumber).andAssert(info -> info.hasMetadata(metadata("SN#" + serialNumber))),
                    updateTokenMetadata
                            .call("callUpdateNFTsMetadata", nft, new long[] {serialNumber}, "The Lion King".getBytes())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(SUCCESS)),
                    nft.getInfo(serialNumber).andAssert(info -> info.hasMetadata(metadata("The Lion King"))));
        }

        @HapiTest
        @DisplayName("use updateMetadataForNFTs to correctly update metadata for multiple individual NFTs")
        public Stream<DynamicTest> usingUpdateMetadataForNFTsWorksForMultipleNFTs() {
            final long[] serialNumbers = new long[] {2, 3};
            return hapiTest(
                    updateTokenMetadata
                            .call("callUpdateNFTsMetadata", nft, serialNumbers, "Nemo".getBytes())
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(SUCCESS)),
                    nft.getInfo(2).andAssert(info -> info.hasMetadata(metadata("Nemo"))),
                    nft.getInfo(3).andAssert(info -> info.hasMetadata(metadata("Nemo"))));
        }

        @HapiTest
        @DisplayName("use updateMetadataForNFTs with empty metadata to update multiple NFTs")
        @Tag(MATS)
        public Stream<DynamicTest> usingUpdateMetadataForNFTsWorksWithEmptyMetadata() {
            final long[] serialNumbers = new long[] {4, 5};
            return hapiTest(
                    updateTokenMetadata
                            .call("callUpdateNFTsMetadata", nft, serialNumbers, new byte[] {})
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(SUCCESS)),
                    nft.getInfo(4).andAssert(info -> info.hasMetadata(ByteString.EMPTY)),
                    nft.getInfo(5).andAssert(info -> info.hasMetadata(ByteString.EMPTY)));
        }

        @HapiTest
        @DisplayName("use updateMetadataForNFTs to update metadata for NFTs with large metadata")
        public Stream<DynamicTest> failToUseUpdateMetadataForNFTsLargeMetadata() {
            final byte[] largeMetadata = new byte[1_000];
            return hapiTest(updateTokenMetadata
                    .call("callUpdateNFTsMetadata", nft, new long[] {1}, largeMetadata)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, METADATA_TOO_LONG)));
        }
    }
}
