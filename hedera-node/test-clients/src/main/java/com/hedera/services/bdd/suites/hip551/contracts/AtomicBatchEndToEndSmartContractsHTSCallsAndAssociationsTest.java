// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class AtomicBatchEndToEndSmartContractsHTSCallsAndAssociationsTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";
    private static final long GAS_TO_OFFER = 6_000_000L;
    private static final String OWNER = "owner";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_NOT_ASSOCIATED = "receiverNotAssociated";
    private static final String FT_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String CONTRACT_KEY = "contractKey";
    private static final String HTS_CALLS_CONTRACT = "HTSCalls";
    private static final String ASSOCIATE_CONTRACT = "AssociateContract";

    private static final String supplyKey = "supplyKey";
    private static final String adminKey = "adminKey";
    private static final String wipeKey = "wipeKey";

    @HapiTest
    @DisplayName("Call contract for FT token mint and transfer minted tokens success in atomic batch")
    final Stream<DynamicTest> callContractForFTTokenMintAndTransferInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> contractAddress = new AtomicReference<>();

        // Transfer token from treasury to receivers associated account
        final var transferMintedTokenInnerTxn_First = cryptoTransfer(
                        moving(1, FT_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenInnerTxnFirst");

        final var transferMintedTokenInnerTxn_Second = cryptoTransfer(
                        moving(2, FT_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenInnerTxnSecond");

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contract and upload its init code
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(contractAddress::set)
                        .via("contractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN),
                tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_TOKEN),

                // Call the contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callTokenMintContractInnerTxn"),
                                transferMintedTokenInnerTxn_First,
                                transferMintedTokenInnerTxn_Second)
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(FT_TOKEN).hasTotalSupply(10L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 7L),
                // Assert the receiver associated account has the token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_TOKEN, 1L),
                getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_TOKEN, 2L)));
    }

    @HapiTest
    @DisplayName("Call contract for NFT token mint and transfer minted tokens success in atomic batch")
    final Stream<DynamicTest> callContractForNFTTokenMintAndTransferInAtomicBatch() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> contractAddress = new AtomicReference<>();

        // Transfer token from treasury to receivers associated account
        final var transferMintedTokenInnerTxn_First = cryptoTransfer(
                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenInnerTxnFirst");

        final var transferMintedTokenInnerTxn_Second = cryptoTransfer(
                        movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenInnerTxnSecond");

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contract and upload its init code
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(contractAddress::set)
                        .via("contractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NON_FUNGIBLE_TOKEN),

                // Call the contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes(), "nft2".getBytes(), "nft3".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                transferMintedTokenInnerTxn_First,
                                transferMintedTokenInnerTxn_Second)
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(3L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                // Assert the receiver associated account has the token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
    }

    @HapiTest
    @DisplayName("Call contract for FT token mint, associate and transfer minted tokens success in atomic batch")
    final Stream<DynamicTest> callContractForFTTokenMintAssociateAndTransferTokensSuccessInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> contractAddress = new AtomicReference<>();

        // Transfer token from treasury to receiver associated account
        final var transferMintedTokenInnerTxn = cryptoTransfer(
                        moving(1, FT_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenInnerTxn");

        // Associate the fungible token with the receiver associated account
        final var associateFungibleTokenInnerTxn = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN)
                .payingWith(OWNER)
                .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                .via("associateFungibleTokenInnerTxn");

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contract and upload its init code
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(contractAddress::set)
                        .via("contractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, wipeKey, fungibleAddress),

                // Call the contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callTokenMintContractInnerTxn"),
                                associateFungibleTokenInnerTxn,
                                transferMintedTokenInnerTxn)
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the token is minted
                getTokenInfo(FT_TOKEN).hasTotalSupply(10L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 9L),
                // Assert the receiver associated account has the token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_TOKEN, 1L)));
    }

    @HapiTest
    @DisplayName("Call contract for NFT token mint, associate and transfer minted tokens success in atomic batch")
    final Stream<DynamicTest> callContractForNFTTokenMintAssociateAndTransferTokensSuccessInAtomicBatch() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> contractAddress = new AtomicReference<>();

        // Transfer token from treasury to receiver associated account
        final var transferMintedTokenInnerTxn = cryptoTransfer(
                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenInnerTxn");

        // Associate the fungible token with the receiver associated account
        final var associateNonFungibleTokenInnerTxn = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN)
                .payingWith(OWNER)
                .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                .via("associateNonFungibleTokenInnerTxn");

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contract and upload its init code
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(contractAddress::set)
                        .via("contractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),

                // Call the contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes(), "nft2".getBytes(), "nft3".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                associateNonFungibleTokenInnerTxn,
                                transferMintedTokenInnerTxn)
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the token is minted
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(3L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                // Assert the receiver associated account has the token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
    }

    @HapiTest
    @DisplayName("Multiple mint and burn FT and NFT HTS contract calls success in atomic batch")
    final Stream<DynamicTest> multipleMintAndBurnFtAndNFTContractCallsSuccessInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> contractAddress = new AtomicReference<>();

        // Transfer token from treasury to receiver associated account
        final var transferMintedTokenInnerTxn = cryptoTransfer(
                        moving(1, FT_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenInnerTxn");

        // Associate the fungible token with the receiver associated account
        final var associateFungibleTokenInnerTxn = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN)
                .payingWith(OWNER)
                .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                .via("associateFungibleTokenInnerTxn");

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contract and upload its init code
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(contractAddress::set)
                        .via("contractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),

                // Call the contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callTokenMintContractInnerTxn"),
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes(), "nft2".getBytes(), "nft3".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                associateFungibleTokenInnerTxn,
                                transferMintedTokenInnerTxn,
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "burnTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(9L),
                                                new long[] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFtBurnContractInnerTxn"),
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "burnTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.ZERO,
                                                new long[] {1L})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftBurnContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(FT_TOKEN).hasTotalSupply(1L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(2L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                // Assert the receiver associated account has the token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                        .hasTokenBalance(FT_TOKEN, 1L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
    }

    @HapiTest
    @DisplayName("Call contracts for FT and NFT token mint and token associate, transfer to the associated contract"
            + " success in atomic batch")
    final Stream<DynamicTest>
            callContractsForMintBurnAndAssociateAndTransferToAssociatedContractSuccessInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),

                // Mint, Associate and Transfer tokens to contract
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes(), "nft2".getBytes(), "nft3".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Associate FT to the associate contract
                                contractCall(ASSOCIATE_CONTRACT, "associateTokenToThisContract", fungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateFungibleTokenContractInnerTxn"),
                                // Associate NFT to the associate contract
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "associateTokenToThisContract",
                                                nonFungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateNonFungibleTokenContractInnerTxn"),
                                // Transfer FT to contract
                                cryptoTransfer(moving(5, FT_TOKEN).between(OWNER, ASSOCIATE_CONTRACT))
                                        .payingWith(OWNER)
                                        .signedBy(OWNER)
                                        .via("transferFungibleTokenToContractInnerTxn"),
                                // Transfer NFT to contract
                                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, ASSOCIATE_CONTRACT))
                                        .payingWith(OWNER)
                                        .signedBy(OWNER)
                                        .via("transferNftToContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(FT_TOKEN).hasTotalSupply(10L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(3L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 5L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                // Assert the receiver associated account has the token
                getAccountBalance(ASSOCIATE_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 5L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
    }

    @HapiTest
    @DisplayName("Create token with contract for treasury, call contracts for tokens mint, associate, transfer and burn"
            + " success in atomic batch")
    final Stream<DynamicTest>
            updateTreasuryToContractCallContractsForMintAssociateTransferAndBurnSuccessInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN,
                        0L,
                        HTS_CALLS_CONTRACT,
                        adminKey,
                        CONTRACT_KEY,
                        CONTRACT_KEY,
                        nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN, NON_FUNGIBLE_TOKEN),

                // Mint, Associate, Transfer and Burn tokens to contract in atomic bacth
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {
                                                    "nft1".getBytes(),
                                                    "nft2".getBytes(),
                                                    "nft3".getBytes(),
                                                    "nft4".getBytes()
                                                })
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Associate FT to the associate contract
                                contractCall(ASSOCIATE_CONTRACT, "associateTokenToThisContract", fungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateFungibleTokenContractInnerTxn"),
                                // Associate NFT to the associate contract
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "associateTokenToThisContract",
                                                nonFungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateNonFungibleTokenContractInnerTxn"),
                                // Transfer FT to contract with crypto transfer
                                cryptoTransfer(moving(5, FT_TOKEN).between(OWNER, ASSOCIATE_CONTRACT))
                                        .payingWith(OWNER)
                                        .signedBy(OWNER)
                                        .via("transferFungibleTokenToContractInnerTxn"),
                                // Transfer NFT to contract with contract call
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "transferNFTCall",
                                                nonFungibleAddress.get(),
                                                htsCallsAddress.get(),
                                                associateContractAddress.get(),
                                                1L)
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("transferNftToContractInnerTxn"),
                                // Burn FT with contract call
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "burnTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(1L),
                                                new long[] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFtBurnContractInnerTxn"),
                                // Burn NFT with contract call
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "burnTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.ZERO,
                                                new long[] {2L})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftBurnContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(FT_TOKEN).hasTotalSupply(9L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(3L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 4L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the receiver associated account has the token
                getAccountBalance(ASSOCIATE_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 5L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                // Assert the contract has the NFT
                getAccountBalance(HTS_CALLS_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 0L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L)));
    }

    @HapiTest
    @DisplayName("Update token treasury with contract with updated maxAutoAssociations and mint tokens"
            + " success in atomic batch")
    final Stream<DynamicTest> updateTokenTreasuryWithContractAndMintTokensSuccessInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .maxAutomaticTokenAssociations(5)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN, NON_FUNGIBLE_TOKEN),

                // Mint and Associate tokens to contract
                sourcing(() -> atomicBatchDefaultOperator(
                                // Update tokens treasury to be contract address
                                tokenUpdate(FT_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateFungibleTokenTreasuryToContractInnerTxn"),
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateNonFungibleTokenTreasuryToContractInnerTxn"),
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {
                                                    "nft1".getBytes(),
                                                    "nft2".getBytes(),
                                                    "nft3".getBytes(),
                                                    "nft4".getBytes()
                                                })
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(FT_TOKEN).hasTotalSupply(10L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(4L),
                // Assert the owner account has no token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the treasury contract has the tokens
                getAccountBalance(HTS_CALLS_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 10L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L)));
    }

    @HapiTest
    @DisplayName("Update token treasury with contract with updated maxAutoAssociations, mint and associate tokens"
            + " success in atomic batch")
    final Stream<DynamicTest> updateTokenTreasuryWithContractMintAndAssociateTokensSuccessInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .maxAutomaticTokenAssociations(5)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN, NON_FUNGIBLE_TOKEN),

                // Mint and Associate tokens to contract
                sourcing(() -> atomicBatchDefaultOperator(
                                // Update tokens treasury to be contract address
                                tokenUpdate(FT_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateFungibleTokenTreasuryToContractInnerTxn"),
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateNonFungibleTokenTreasuryToContractInnerTxn"),
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {
                                                    "nft1".getBytes(),
                                                    "nft2".getBytes(),
                                                    "nft3".getBytes(),
                                                    "nft4".getBytes()
                                                })
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Associate FT to the associate contract
                                contractCall(ASSOCIATE_CONTRACT, "associateTokenToThisContract", fungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateFungibleTokenContractInnerTxn"),
                                // Associate NFT to the associate contract
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "associateTokenToThisContract",
                                                nonFungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateNonFungibleTokenContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(FT_TOKEN).hasTotalSupply(10L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(4L),
                // Assert the owner account has no tokens
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the receiver associated account does not have tokens but is associated with them
                getAccountBalance(ASSOCIATE_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 0L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the treasury contract has the tokens
                getAccountBalance(HTS_CALLS_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 10L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L)));
    }

    @HapiTest
    @DisplayName("Update token treasury with contract with updated maxAutoAssociations, associate contract, "
            + "mint and transfer success in atomic batch")
    final Stream<DynamicTest>
            updateTokenTreasuryWithContractWithAutoAssociationsAssociateMintAndTransferSuccessInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .maxAutomaticTokenAssociations(5)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN, NON_FUNGIBLE_TOKEN),

                //  Mint, Associate and Transfer tokens to contract
                sourcing(() -> atomicBatchDefaultOperator(
                                // Update tokens treasury to be contract address
                                tokenUpdate(FT_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateFungibleTokenTreasuryToContractInnerTxn"),
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateNonFungibleTokenTreasuryToContractInnerTxn"),
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {
                                                    "nft1".getBytes(),
                                                    "nft2".getBytes(),
                                                    "nft3".getBytes(),
                                                    "nft4".getBytes()
                                                })
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Associate FT to the associate contract
                                contractCall(ASSOCIATE_CONTRACT, "associateTokenToThisContract", fungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateFungibleTokenContractInnerTxn"),
                                // Associate NFT to the associate contract
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "associateTokenToThisContract",
                                                nonFungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateNonFungibleTokenContractInnerTxn"),
                                // Transfer FT to contract with crypto transfer
                                cryptoTransfer(moving(5, FT_TOKEN).between(HTS_CALLS_CONTRACT, ASSOCIATE_CONTRACT))
                                        .payingWith(OWNER)
                                        .signedBy(OWNER, HTS_CALLS_CONTRACT)
                                        .via("transferFungibleTokenToContractInnerTxn"),
                                // Transfer NFT to contract with contract call
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "transferNFTCall",
                                                nonFungibleAddress.get(),
                                                htsCallsAddress.get(),
                                                associateContractAddress.get(),
                                                1L)
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("transferNftToContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(FT_TOKEN).hasTotalSupply(10L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(4L),
                // Assert the owner account has no tokens
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the receiver associated account has the token
                getAccountBalance(ASSOCIATE_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 5L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                // Assert the treasury contract has the tokens
                getAccountBalance(HTS_CALLS_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 5L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L)));
    }

    @HapiTest
    @DisplayName("Call contract for NFT tokens mint, associate to contract and transfer from contract to contract"
            + " and to EOA success in atomic batch")
    final Stream<DynamicTest> callContractsForNFTMintAndTransferFromContractToEOASuccessInAtomicBatch() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .maxAutomaticTokenAssociations(5)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .maxAutomaticTokenAssociations(5)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN,
                        0L,
                        HTS_CALLS_CONTRACT,
                        adminKey,
                        CONTRACT_KEY,
                        CONTRACT_KEY,
                        nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),

                // Mint, Associate and Transfer NFT tokens to contract and EOA in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {
                                                    "nft1".getBytes(),
                                                    "nft2".getBytes(),
                                                    "nft3".getBytes(),
                                                    "nft4".getBytes()
                                                })
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Associate NFT to the associate contract
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "associateTokenToThisContract",
                                                nonFungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateNonFungibleTokenContractInnerTxn"),
                                // Transfer NFT from treasury contract to associated contract
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "transferNFTCall",
                                                nonFungibleAddress.get(),
                                                htsCallsAddress.get(),
                                                associateContractAddress.get(),
                                                1L)
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("transferNftToAssociatedContractInnerTxn"),
                                // transfer from treasury contract to EOA
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "transferNFTCall",
                                                nonFungibleAddress.get(),
                                                htsCallsAddress.get(),
                                                receiverAddressFirst.get(),
                                                2L)
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("transferNftFromContractToEOAInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(4L),
                // Assert the owner account has no token
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the treasury contract has remaining tokens
                getAccountBalance(HTS_CALLS_CONTRACT).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                // Assert the receiver associated account has token
                getAccountBalance(ASSOCIATE_CONTRACT).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                // Assert the EOA has token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
    }

    @HapiTest
    @DisplayName("Call contract for FT token mint and transfer minted tokens to not associated receiver "
            + "fails in atomic batch")
    final Stream<DynamicTest> createContractForTokenMintCallAndTransferTokensToNotAssociatedAccountInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();

        // Transfer token from treasury to associated and not associated receivers
        final var transferMintedTokenToAssociatedReceiverInnerTxn = cryptoTransfer(
                        moving(1, FT_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenToAssociatedReceiverInnerTxn");

        final var transferMintedTokenToNotAssociatedReceiverInnerTxn = cryptoTransfer(
                        moving(1, FT_TOKEN).between(OWNER, RECEIVER_NOT_ASSOCIATED))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenToNotAssociatedReceiverInnerTxn")
                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

        // Associate the fungible token with the receiver associated account
        final var associateFungibleTokenInnerTxn = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN)
                .payingWith(OWNER)
                .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                .via("associateFungibleTokenInnerTxn");

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contract and upload its init code
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, supplyKey, wipeKey, fungibleAddress),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(contractAddress::set)
                        .via("contractCreateTxn"),

                // Make the contract the supply-key of the fungible token so that it can mint tokens
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                tokenUpdate(FT_TOKEN).supplyKey(CONTRACT_KEY).payingWith(OWNER).signedBy(OWNER, adminKey),

                // Call the contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                java.math.BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callTokenMintContractInnerTxn"),
                                associateFungibleTokenInnerTxn,
                                transferMintedTokenToAssociatedReceiverInnerTxn,
                                transferMintedTokenToNotAssociatedReceiverInnerTxn)
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),

                // Assert the token is not minted
                getTokenInfo(FT_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has no token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L),
                // Assert the receiver account has no token relationship
                getAccountInfo(RECEIVER_ASSOCIATED_FIRST).hasNoTokenRelationship(FT_TOKEN)));
    }

    @HapiTest
    @DisplayName("Call contracts for FT and NFT token mint and token associate, transfer to not associated contract"
            + " fails in atomic batch")
    final Stream<DynamicTest>
            callContractsForMintBurnAndAssociateAndTransferToNotAssociatedContractFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),

                // Mint tokens to contract and transfer to not associated contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes(), "nft2".getBytes(), "nft3".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Transfer FT to contract
                                cryptoTransfer(moving(5, FT_TOKEN).between(OWNER, ASSOCIATE_CONTRACT))
                                        .payingWith(OWNER)
                                        .signedBy(OWNER)
                                        .via("transferFungibleTokenToContractInnerTxn")
                                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                                // Transfer NFT to contract
                                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, ASSOCIATE_CONTRACT))
                                        .payingWith(OWNER)
                                        .signedBy(OWNER)
                                        .via("transferNftToContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),

                // Assert the tokens were not minted
                getTokenInfo(FT_TOKEN).hasTotalSupply(0L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has no token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the receiver contract account has no tokens and is not associated with them
                getAccountInfo(ASSOCIATE_CONTRACT)
                        .hasNoTokenRelationship(FT_TOKEN)
                        .hasNoTokenRelationship(NON_FUNGIBLE_TOKEN)));
    }

    @HapiTest
    @DisplayName("Call contract for token mint to invalid address and token transfer fails in atomic batch")
    final Stream<DynamicTest> createContractForTokenMintCallItToInvalidAddressAndTokenTransferFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();

        // Transfer token from treasury to receiver associated account
        final var transferMintedTokenInnerTxn = cryptoTransfer(
                        moving(1, FT_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenInnerTxn")
                .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE);

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contract and upload its init code
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, supplyKey, wipeKey, fungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT).gas(GAS_TO_OFFER).via("contractCreateTxn"),

                // Make the contract the supply-key of the fungible token so that it can mint tokens
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                tokenUpdate(FT_TOKEN).supplyKey(CONTRACT_KEY).payingWith(OWNER).signedBy(OWNER, adminKey),

                // Call the contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                asHeadlongAddress("0x" + "fe".repeat(20)),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callTokenMintContractInnerTxn"),
                                transferMintedTokenInnerTxn)
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),

                // Assert the child transaction record exposes the error code
                childRecordsCheck(
                        "callTokenMintContractInnerTxn",
                        REVERTED_SUCCESS,
                        recordWith().status(INVALID_TOKEN_ID)),

                // Assert the token is not minted
                getTokenInfo(FT_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has no tokens
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L),
                // Assert the receiver associated account has no token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_TOKEN, 0L)));
    }

    @HapiTest
    @DisplayName("Call contract for token mint with insufficient gas and token transfer fails in atomic batch")
    final Stream<DynamicTest> createContractForTokenMintCallWithInsufficientGasAndTokenTransferFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();

        // Transfer token from treasury to receiver associated account
        final var transferMintedTokenInnerTxn = cryptoTransfer(
                        moving(1, FT_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                .payingWith(OWNER)
                .signedBy(OWNER)
                .via("transferTokenInnerTxn");

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contract and upload its init code
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, supplyKey, wipeKey, fungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT).gas(GAS_TO_OFFER).via("contractCreateTxn"),

                // Make the contract the supply-key of the fungible token so that it can mint tokens
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                tokenUpdate(FT_TOKEN).supplyKey(CONTRACT_KEY).payingWith(OWNER).signedBy(OWNER, adminKey),

                // Call the contract in atomic batch with insufficient gas
                sourcing(() -> atomicBatchDefaultOperator(
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(500L) // Insufficient gas
                                        .via("callTokenMintContractInnerTxn"),
                                transferMintedTokenInnerTxn)
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasPrecheck(INSUFFICIENT_GAS)),

                // Assert the token is not minted
                getTokenInfo(FT_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L),
                // Assert the receiver associated account has the token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_TOKEN, 0L)));
    }

    @HapiTest
    @DisplayName("Update token treasury with contract and mint tokens not signed by contract fails in atomic batch")
    final Stream<DynamicTest> updateTokenTreasuryWithContractAndMintTokensNotSignedByContractFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .maxAutomaticTokenAssociations(5)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN, NON_FUNGIBLE_TOKEN),

                // Mint and Associate tokens to contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Update tokens treasury to be contract address
                                tokenUpdate(FT_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateFungibleTokenTreasuryToContractInnerTxn"),
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(OWNER, adminKey)
                                        .via("updateNonFungibleTokenTreasuryToContractInnerTxn")
                                        .hasKnownStatus(INVALID_SIGNATURE),
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {
                                                    "nft1".getBytes(),
                                                    "nft2".getBytes(),
                                                    "nft3".getBytes(),
                                                    "nft4".getBytes()
                                                })
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),

                // Assert the tokens were not minted
                getTokenInfo(FT_TOKEN).hasTotalSupply(0L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has no tokens
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the treasury contract has no tokens
                getAccountBalance(HTS_CALLS_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 0L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
    }

    @HapiTest
    @DisplayName(
            "Update token treasury with contract, mint and associate tokens not signed by contract fails in atomic batch")
    final Stream<DynamicTest>
            updateTokenTreasuryWithContractMintAndAssociateTokensNotSignedByContractFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .maxAutomaticTokenAssociations(5)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN, NON_FUNGIBLE_TOKEN),

                // Mint and Associate tokens to contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Update tokens treasury to be contract address
                                tokenUpdate(FT_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(OWNER, adminKey)
                                        .via("updateFungibleTokenTreasuryToContractInnerTxn")
                                        .hasKnownStatus(INVALID_SIGNATURE),
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateNonFungibleTokenTreasuryToContractInnerTxn"),
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {
                                                    "nft1".getBytes(),
                                                    "nft2".getBytes(),
                                                    "nft3".getBytes(),
                                                    "nft4".getBytes()
                                                })
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Associate FT to the associate contract
                                contractCall(ASSOCIATE_CONTRACT, "associateTokenToThisContract", fungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateFungibleTokenContractInnerTxn"),
                                // Associate NFT to the associate contract
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "associateTokenToThisContract",
                                                nonFungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateNonFungibleTokenContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),

                // Assert the tokens were not minted
                getTokenInfo(FT_TOKEN).hasTotalSupply(0L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has no tokens
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the receiver associated account does not have tokens and is not associated with them
                getAccountInfo(ASSOCIATE_CONTRACT)
                        .hasNoTokenRelationship(FT_TOKEN)
                        .hasNoTokenRelationship(NON_FUNGIBLE_TOKEN),
                // Assert the treasury contract has no tokens
                getAccountBalance(HTS_CALLS_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 0L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
    }

    @HapiTest
    @DisplayName("Update token treasury with contract with maxAutoAssociations = 0, associate contract and mint"
            + " fails in atomic batch")
    final Stream<DynamicTest> updateTokenTreasuryWithContractAssociateAndMintFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN, NON_FUNGIBLE_TOKEN),

                // Mint and Associate tokens to contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Update tokens treasury to be contract address
                                tokenUpdate(FT_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateFungibleTokenTreasuryToContractInnerTxn")
                                        .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS),
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .treasury(HTS_CALLS_CONTRACT)
                                        .payingWith(OWNER)
                                        .signedBy(HTS_CALLS_CONTRACT, OWNER, adminKey)
                                        .via("updateNonFungibleTokenTreasuryToContractInnerTxn"),
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {
                                                    "nft1".getBytes(),
                                                    "nft2".getBytes(),
                                                    "nft3".getBytes(),
                                                    "nft4".getBytes()
                                                })
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Associate FT to the associate contract
                                contractCall(ASSOCIATE_CONTRACT, "associateTokenToThisContract", fungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateFungibleTokenContractInnerTxn"),
                                // Associate NFT to the associate contract
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "associateTokenToThisContract",
                                                nonFungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateNonFungibleTokenContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),

                // Assert the tokens were not minted
                getTokenInfo(FT_TOKEN).hasTotalSupply(0L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the receiver associated account has no token relationship
                getAccountInfo(ASSOCIATE_CONTRACT)
                        .hasNoTokenRelationship(FT_TOKEN)
                        .hasNoTokenRelationship(NON_FUNGIBLE_TOKEN),
                // Assert the treasury contract has no tokens
                getAccountBalance(HTS_CALLS_CONTRACT)
                        .hasTokenBalance(FT_TOKEN, 0L)
                        .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
    }

    @HapiTest
    @DisplayName("Call contract for FT tokens mint, transfer, burn and transfer burned tokens fails in atomic batch")
    final Stream<DynamicTest> callContractsForMintAssociateBurnAndTransferFailsInAtomicBatch() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createFungibleTokenWithAdminKeyAndSaveAddress(
                        FT_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, fungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_TOKEN),

                // Mint, Associate, Burn and Transfer burned tokens to contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint FT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new byte[][] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFTTokenMintContractInnerTxn"),
                                // Associate FT to the associate contract
                                contractCall(ASSOCIATE_CONTRACT, "associateTokenToThisContract", fungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateFungibleTokenContractInnerTxn"),
                                // Burn FT with contract call
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "burnTokenCall",
                                                fungibleAddress.get(),
                                                BigInteger.valueOf(10L),
                                                new long[] {})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callFtBurnContractInnerTxn"),
                                // Transfer FT to contract with crypto transfer
                                cryptoTransfer(moving(5, FT_TOKEN).between(OWNER, ASSOCIATE_CONTRACT))
                                        .payingWith(OWNER)
                                        .signedBy(OWNER)
                                        .via("transferFungibleTokenToContractInnerTxn")
                                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(FT_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(FT_TOKEN, 0L),
                // Assert the receiver associated account has no token relationship
                getAccountInfo(ASSOCIATE_CONTRACT).hasNoTokenRelationship(FT_TOKEN)));
    }

    @HapiTest
    @DisplayName("Call contract for NFT tokens mint, transfer, burn and transfer burned tokens fails in atomic batch")
    final Stream<DynamicTest> callContractsForNFTMintAssociateBurnAndTransferFailsInAtomicBatch() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();
        final AtomicReference<Address> associateContractAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),
                uploadInitCode(ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .exposingAddressTo(associateContractAddress::set)
                        .via("associateContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN,
                        0L,
                        HTS_CALLS_CONTRACT,
                        adminKey,
                        CONTRACT_KEY,
                        CONTRACT_KEY,
                        nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),

                // Mint, Associate, Burn and Transfer burned tokens to contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {
                                                    "nft1".getBytes(),
                                                    "nft2".getBytes(),
                                                    "nft3".getBytes(),
                                                    "nft4".getBytes()
                                                })
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Associate NFT to the associate contract
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "associateTokenToThisContract",
                                                nonFungibleAddress.get())
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("associateNonFungibleTokenContractInnerTxn"),
                                // Burn NFT with contract call
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "burnTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.ZERO,
                                                new long[] {1L, 2L, 3L, 4L})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftBurnContractInnerTxn"),
                                // Transfer NFT to contract
                                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(HTS_CALLS_CONTRACT, ASSOCIATE_CONTRACT))
                                        .hasKnownStatus(INVALID_NFT_ID))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),

                // Assert the token is not minted
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the contract has no token
                getAccountBalance(HTS_CALLS_CONTRACT).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the receiver associated account has no token relationship
                getAccountInfo(ASSOCIATE_CONTRACT).hasNoTokenRelationship(NON_FUNGIBLE_TOKEN)));
    }

    @HapiTest
    @DisplayName("Call contract for NFT tokens mint and transfer token from contract to itself fails in atomic batch")
    final Stream<DynamicTest> callContractsForNFTMintAndTransferFromContractToItselfFailsInAtomicBatch() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .maxAutomaticTokenAssociations(5)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN,
                        0L,
                        HTS_CALLS_CONTRACT,
                        adminKey,
                        CONTRACT_KEY,
                        CONTRACT_KEY,
                        nonFungibleAddress),

                // Mint, Associate and Transfer NFT tokens from treasury to the same treasury contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Transfer NFT from treasury contract to the same treasury contract
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "transferNFTCall",
                                                nonFungibleAddress.get(),
                                                htsCallsAddress.get(),
                                                htsCallsAddress.get(),
                                                1L)
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("transferNftToContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // The contract does not revert and the batch succeeds but the child transaction fails
                // Assert the child transaction record exposes the error code
                childRecordsCheck(
                        "transferNftToContractInnerTxn",
                        SUCCESS,
                        recordWith().status(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS)),

                // Assert the token supply is updated correctly
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(1L),
                // Assert the owner account has no token
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the treasury contract has the token
                getAccountBalance(HTS_CALLS_CONTRACT).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
    }

    @HapiTest
    @DisplayName("Call contract for NFT tokens mint, mint and transfer token from contract that is not treasury to"
            + " EOA fails in atomic batch")
    final Stream<DynamicTest> callContractsForNFTMintAndTransferFromContractThatIsNotTreasuryToEOAFailsInAtomicBatch() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .maxAutomaticTokenAssociations(5)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                tokenAssociate(HTS_CALLS_CONTRACT, NON_FUNGIBLE_TOKEN),

                // Mint, Associate and Transfer NFT tokens from treasury to the same treasury contract in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // Transfer NFT from non-treasury contract to EOA
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "transferNFTCall",
                                                nonFungibleAddress.get(),
                                                htsCallsAddress.get(),
                                                receiverAddressFirst.get(),
                                                1L)
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("transferNftToContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // The contract does not revert and the batch succeeds but the child transaction fails
                // Assert the child transaction record exposes the error code
                childRecordsCheck(
                        "transferNftToContractInnerTxn",
                        SUCCESS,
                        recordWith().status(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO)),

                // Assert the token supply is updated correctly
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(1L),
                // Assert the owner account has the token
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                // Assert the contract has no token
                getAccountBalance(HTS_CALLS_CONTRACT).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the first receiver account has no token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
    }

    @HapiTest
    @DisplayName("Call contract for NFT tokens mint and transfer to accounts with insufficient token"
            + " balance fails in atomic batch")
    final Stream<DynamicTest>
            callContractForNFTMintAndTransferFromContractWithInsufficientTokenBalanceFailsInAtomicBatch() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .maxAutomaticTokenAssociations(5)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),

                // Use the contract as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN,
                        0L,
                        HTS_CALLS_CONTRACT,
                        adminKey,
                        CONTRACT_KEY,
                        CONTRACT_KEY,
                        nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NON_FUNGIBLE_TOKEN),

                // Mint and Transfer NFT tokens to accounts with insufficient token balance in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // transfer from treasury contract to first EOA
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "transferNFTCall",
                                                nonFungibleAddress.get(),
                                                htsCallsAddress.get(),
                                                receiverAddressFirst.get(),
                                                1L)
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("transferNftToContractInnerTxnFirst"),
                                // transfer from treasury contract to second EOA
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "transferNFTCall",
                                                nonFungibleAddress.get(),
                                                htsCallsAddress.get(),
                                                receiverAddressSecond.get(),
                                                1L)
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("transferNftToContractInnerTxnSecond"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // The contract does not revert and the batch succeeds but the child transaction fails
                // Assert the child transaction record exposes the error code
                childRecordsCheck(
                        "transferNftToContractInnerTxnSecond",
                        SUCCESS,
                        recordWith().status(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO)),

                // Assert the tokens supply is updated correctly
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(1L),
                // Assert the owner account has no token
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the treasury contract has no remaining tokens
                getAccountBalance(HTS_CALLS_CONTRACT).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the first receiver account has token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                // Assert the second receiver account has no token
                getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
    }

    @HapiTest
    @DisplayName("Call contract for NFT tokens mint with contract that is not supply key fails in atomic batch")
    final Stream<DynamicTest> callContractForNFTMintAndTransferFromContractThatIsNotSupplyKeyFailsInAtomicBatch() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .maxAutomaticTokenAssociations(5)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),

                // Use the contract as the treasury but not as the supplyKey for minting
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN,
                        0L,
                        HTS_CALLS_CONTRACT,
                        adminKey,
                        supplyKey,
                        CONTRACT_KEY,
                        nonFungibleAddress),
                tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),

                // Mint and Transfer NFT tokens in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                // transfer from treasury contract to first EOA
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "transferNFTCall",
                                                nonFungibleAddress.get(),
                                                htsCallsAddress.get(),
                                                receiverAddressFirst.get(),
                                                1L)
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("transferNftToContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // The contract does not revert and the batch succeeds but the child transaction fails
                // Assert the child transaction record exposes the error code
                childRecordsCheck(
                        "transferNftToContractInnerTxn", SUCCESS, recordWith().status(INVALID_NFT_ID)),

                // Assert the token is not minted
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0L),
                // Assert the owner account has no token
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the treasury contract has no tokens
                getAccountBalance(HTS_CALLS_CONTRACT).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                // Assert the receiver associated account has no token
                getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
    }

    // Contract tries to burn tokens it is not treasury for fails
    // !!!!! The tokens actually got burned, but the contract is not the treasury!!!!!!!!!!!!
    @HapiTest
    @DisplayName("Call contract for NFT tokens mint, edit supply key and burn with contract that is not supply key"
            + " fails in atomic batch")
    final Stream<DynamicTest> callContractForNFTMintAndBurnFromContractThatIsNotSupplyKeyFailsInAtomicBatch() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressFirst = new AtomicReference<>();
        final AtomicReference<Address> receiverAddressSecond = new AtomicReference<>();
        final AtomicReference<Address> htsCallsAddress = new AtomicReference<>();

        return hapiTest(flattened(
                // Create accounts, keys and tokens, create contracts and upload their init codes
                createAccountsAndKeys(receiverAddressFirst, receiverAddressSecond),
                uploadInitCode(HTS_CALLS_CONTRACT),
                contractCreate(HTS_CALLS_CONTRACT)
                        .gas(GAS_TO_OFFER)
                        .maxAutomaticTokenAssociations(5)
                        .exposingAddressTo(htsCallsAddress::set)
                        .via("mintContractCreateTxn"),

                // The contract is the supplyKey but is not the treasury of the token
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS_CONTRACT)),
                createNonFungibleTokenWithAdminKeyAndSaveAddress(
                        NON_FUNGIBLE_TOKEN, 0L, OWNER, adminKey, CONTRACT_KEY, CONTRACT_KEY, nonFungibleAddress),

                // Mint and BURN NFT tokens from contract that is the supply key but is not the treasury in atomic batch
                sourcing(() -> atomicBatchDefaultOperator(
                                // Mint NFT
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "mintTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.valueOf(0L),
                                                new byte[][] {"nft1".getBytes(), "nft2".getBytes()})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftMintContractInnerTxn"),
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .supplyKey(supplyKey)
                                        .payingWith(OWNER)
                                        .signedBy(OWNER, adminKey, supplyKey, CONTRACT_KEY),
                                contractCall(
                                                HTS_CALLS_CONTRACT,
                                                "burnTokenCall",
                                                nonFungibleAddress.get(),
                                                BigInteger.ZERO,
                                                new long[] {1L})
                                        .payingWith(OWNER)
                                        .gas(GAS_TO_OFFER)
                                        .via("callNftBurnContractInnerTxn"))
                        .payingWith(DEFAULT_BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)),

                // The contract does not revert and the batch succeeds but the child transaction fails
                // Assert the child transaction record exposes the error code
                childRecordsCheck(
                        "callNftBurnContractInnerTxn",
                        SUCCESS,
                        recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),

                // Assert the token is not minted
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(2L),
                // Assert the owner account has no token
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                // Assert the treasury contract has remaining tokens
                getAccountBalance(HTS_CALLS_CONTRACT).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
    }

    /* --------------- Helper methods --------------- */

    private HapiAtomicBatch atomicBatchDefaultOperator(HapiTxnOp<?>... ops) {
        return atomicBatch(Arrays.stream(ops)
                        .map(op -> op.batchKey(DEFAULT_BATCH_OPERATOR))
                        .toArray(HapiTxnOp[]::new))
                .payingWith(DEFAULT_BATCH_OPERATOR);
    }

    private HapiTokenCreate createFungibleTokenWithAdminKeyAndSaveAddress(
            String tokenName,
            long supply,
            String treasury,
            String adminKey,
            String supplyKey,
            String wipeKey,
            AtomicReference<Address> fungibleAddress) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .supplyKey(supplyKey)
                .wipeKey(wipeKey)
                .tokenType(FUNGIBLE_COMMON)
                .exposingAddressTo(fungibleAddress::set);
    }

    private HapiTokenCreate createNonFungibleTokenWithAdminKeyAndSaveAddress(
            String tokenName,
            long supply,
            String treasury,
            String adminKey,
            String supplyKey,
            String wipeKey,
            AtomicReference<Address> nonFungibleAddress) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .supplyKey(supplyKey)
                .wipeKey(wipeKey)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .exposingAddressTo(nonFungibleAddress::set);
    }

    private List<SpecOperation> createAccountsAndKeys(
            final AtomicReference<Address> receiverAddressFirst, final AtomicReference<Address> receiverAddressSecond) {
        return List.of(
                cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(OWNER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST)
                        .balance(ONE_HBAR)
                        .exposingCreatedIdTo(
                                id -> receiverAddressFirst.set(asHeadlongAddress(asHexedSolidityAddress(id)))),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND)
                        .balance(ONE_HBAR)
                        .exposingCreatedIdTo(
                                id -> receiverAddressSecond.set(asHeadlongAddress(asHexedSolidityAddress(id)))),
                cryptoCreate(RECEIVER_NOT_ASSOCIATED).balance(ONE_HBAR),
                newKeyNamed(supplyKey),
                newKeyNamed(adminKey),
                newKeyNamed(wipeKey));
    }
}
