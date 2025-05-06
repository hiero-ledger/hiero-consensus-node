// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.idAsHeadlongAddress;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.SECP256K1;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordedChildBodyWithId;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.PAY_RECEIVABLE_CONTRACT;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.MULTI_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractKeysStillWorkAsExpectedSuite {
    @HapiTest
    final Stream<DynamicTest> approvalFallbacksRequiredWithoutTopLevelSigAccess() {
        final AtomicReference<Address> fungibleTokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<Address> nonFungibleTokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<Address> aSenderAddr = new AtomicReference<>();
        final AtomicReference<Address> aReceiverAddr = new AtomicReference<>();
        final AtomicReference<Address> bSenderAddr = new AtomicReference<>();
        final AtomicReference<Address> bReceiverAddr = new AtomicReference<>();

        return hapiTest(
                recordStreamMustIncludePassFrom(
                        recordedChildBodyWithId(TOKEN_UNIT_FROM_TO_OTHERS_TXN, 1, (spec, txn) -> {
                            if (txn.hasNodeStakeUpdate()) {
                                // Avoid asserting something about an end-of-staking-period NodeStakeUpdate in CI
                                return;
                            }
                            final var tokenTransfers = txn.getCryptoTransfer().getTokenTransfersList();
                            assertEquals(1, tokenTransfers.size());
                            final var tokenTransfer = tokenTransfers.getFirst();
                            for (final var adjust : tokenTransfer.getTransfersList()) {
                                if (adjust.getAmount() < 0) {
                                    // The debit should have been automatically converted to an approval
                                    assertTrue(adjust.getIsApproval());
                                }
                            }
                        })),
                someWellKnownTokensAndAccounts(
                        fungibleTokenMirrorAddr,
                        nonFungibleTokenMirrorAddr,
                        aSenderAddr,
                        aReceiverAddr,
                        bSenderAddr,
                        bReceiverAddr,
                        true),
                // Nothing works without approvals now
                someWellKnownOperationsWithAllNeededSigsInSigMap(
                        fungibleTokenMirrorAddr,
                        nonFungibleTokenMirrorAddr,
                        aSenderAddr,
                        aReceiverAddr,
                        bSenderAddr,
                        bReceiverAddr,
                        CONTRACT_REVERT_EXECUTED),
                // So grant all the approvals we need
                cryptoApproveAllowance()
                        .payingWith(A_WELL_KNOWN_SENDER)
                        .addNftAllowance(
                                A_WELL_KNOWN_SENDER,
                                WELL_KNOWN_NON_FUNGIBLE_TOKEN,
                                WELL_KNOWN_TREASURY_CONTRACT,
                                false,
                                List.of(1L, 2L))
                        .addTokenAllowance(
                                A_WELL_KNOWN_SENDER,
                                WELL_KNOWN_FUNGIBLE_TOKEN,
                                WELL_KNOWN_TREASURY_CONTRACT,
                                Long.MAX_VALUE)
                        .fee(ONE_HBAR),
                cryptoApproveAllowance()
                        .payingWith(B_WELL_KNOWN_SENDER)
                        .addNftAllowance(
                                B_WELL_KNOWN_SENDER,
                                WELL_KNOWN_NON_FUNGIBLE_TOKEN,
                                WELL_KNOWN_TREASURY_CONTRACT,
                                false,
                                List.of(3L))
                        .addTokenAllowance(
                                B_WELL_KNOWN_SENDER,
                                WELL_KNOWN_FUNGIBLE_TOKEN,
                                WELL_KNOWN_TREASURY_CONTRACT,
                                Long.MAX_VALUE)
                        .fee(ONE_HBAR),
                // Everything should have the needed approvals
                someWellKnownOperationsWithAllNeededSigsInSigMap(
                        fungibleTokenMirrorAddr,
                        nonFungibleTokenMirrorAddr,
                        aSenderAddr,
                        aReceiverAddr,
                        bSenderAddr,
                        bReceiverAddr,
                        SUCCESS),
                someWellKnownAssertions());
    }

    private static final String A_WELL_KNOWN_SENDER = "A_SENDER";
    private static final String B_WELL_KNOWN_SENDER = "B_SENDER";
    private static final String A_WELL_KNOWN_RECEIVER = "A_RECEIVER";
    private static final String B_WELL_KNOWN_RECEIVER = "B_RECEIVER";
    private static final String WELL_KNOWN_FUNGIBLE_TOKEN = "FT";
    private static final String WELL_KNOWN_NON_FUNGIBLE_TOKEN = "NFT";
    private static final String WELL_KNOWN_TREASURY_CONTRACT = "DoTokenManagement";

    private HapiSpecOperation someWellKnownAssertions() {
        return blockingOrder(
                getAccountBalance(A_WELL_KNOWN_RECEIVER)
                        .hasTokenBalance(WELL_KNOWN_FUNGIBLE_TOKEN, 2L)
                        .hasTokenBalance(WELL_KNOWN_NON_FUNGIBLE_TOKEN, 2L),
                getAccountBalance(B_WELL_KNOWN_RECEIVER)
                        .hasTokenBalance(WELL_KNOWN_FUNGIBLE_TOKEN, 1L)
                        .hasTokenBalance(WELL_KNOWN_NON_FUNGIBLE_TOKEN, 1L),
                getTokenNftInfo(WELL_KNOWN_NON_FUNGIBLE_TOKEN, 1L).hasAccountID(A_WELL_KNOWN_RECEIVER),
                getTokenNftInfo(WELL_KNOWN_NON_FUNGIBLE_TOKEN, 2L).hasAccountID(A_WELL_KNOWN_RECEIVER),
                getTokenNftInfo(WELL_KNOWN_NON_FUNGIBLE_TOKEN, 3L).hasAccountID(B_WELL_KNOWN_RECEIVER));
    }

    /**
     * Returns a multi-step operation that creates a fungible and non-fungible token; a contract
     * that serves as both their treasuries; and some accounts associated to both tokens, where the
     * sender accounts have fungible units and serial numbers 1 and 2, respectively.
     */
    private HapiSpecOperation someWellKnownTokensAndAccounts(
            final AtomicReference<Address> fungibleTokenMirrorAddr,
            final AtomicReference<Address> nonFungibleTokenMirrorAddr,
            final AtomicReference<Address> aSenderAddr,
            final AtomicReference<Address> aReceiverAddr,
            final AtomicReference<Address> bSenderAddr,
            final AtomicReference<Address> bReceiverAddr,
            final boolean transferToASender) {
        return blockingOrder(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(A_WELL_KNOWN_SENDER).exposingCreatedIdTo(id -> aSenderAddr.set(idAsHeadlongAddress(id))),
                cryptoCreate(B_WELL_KNOWN_SENDER).exposingCreatedIdTo(id -> bSenderAddr.set(idAsHeadlongAddress(id))),
                cryptoCreate(A_WELL_KNOWN_RECEIVER)
                        .exposingCreatedIdTo(id -> aReceiverAddr.set(idAsHeadlongAddress(id))),
                cryptoCreate(B_WELL_KNOWN_RECEIVER)
                        .exposingCreatedIdTo(id -> bReceiverAddr.set(idAsHeadlongAddress(id))),
                uploadInitCode(WELL_KNOWN_TREASURY_CONTRACT),
                contractCreate(WELL_KNOWN_TREASURY_CONTRACT).gas(4_000_000L),
                tokenCreate(WELL_KNOWN_FUNGIBLE_TOKEN)
                        .exposingAddressTo(fungibleTokenMirrorAddr::set)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000_000)
                        .treasury(WELL_KNOWN_TREASURY_CONTRACT),
                tokenCreate(WELL_KNOWN_NON_FUNGIBLE_TOKEN)
                        .exposingAddressTo(nonFungibleTokenMirrorAddr::set)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(MULTI_KEY)
                        .treasury(WELL_KNOWN_TREASURY_CONTRACT),
                mintToken(
                        WELL_KNOWN_NON_FUNGIBLE_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("A"),
                                ByteString.copyFromUtf8("B"),
                                ByteString.copyFromUtf8("C"))),
                tokenAssociate(A_WELL_KNOWN_SENDER, List.of(WELL_KNOWN_FUNGIBLE_TOKEN, WELL_KNOWN_NON_FUNGIBLE_TOKEN)),
                tokenAssociate(B_WELL_KNOWN_SENDER, List.of(WELL_KNOWN_FUNGIBLE_TOKEN, WELL_KNOWN_NON_FUNGIBLE_TOKEN)),
                tokenAssociate(
                        A_WELL_KNOWN_RECEIVER, List.of(WELL_KNOWN_FUNGIBLE_TOKEN, WELL_KNOWN_NON_FUNGIBLE_TOKEN)),
                tokenAssociate(
                        B_WELL_KNOWN_RECEIVER, List.of(WELL_KNOWN_FUNGIBLE_TOKEN, WELL_KNOWN_NON_FUNGIBLE_TOKEN)),
                transferToASender
                        ? cryptoTransfer(TokenMovement.movingUnique(WELL_KNOWN_NON_FUNGIBLE_TOKEN, 1L, 2L)
                                .between(WELL_KNOWN_TREASURY_CONTRACT, A_WELL_KNOWN_SENDER))
                        : noOp(),
                cryptoTransfer(TokenMovement.movingUnique(WELL_KNOWN_NON_FUNGIBLE_TOKEN, 3L)
                        .between(WELL_KNOWN_TREASURY_CONTRACT, B_WELL_KNOWN_SENDER)),
                transferToASender
                        ? cryptoTransfer(TokenMovement.moving(1_000, WELL_KNOWN_FUNGIBLE_TOKEN)
                                .between(WELL_KNOWN_TREASURY_CONTRACT, A_WELL_KNOWN_SENDER))
                        : noOp(),
                cryptoTransfer(TokenMovement.moving(1_000, WELL_KNOWN_FUNGIBLE_TOKEN)
                        .between(WELL_KNOWN_TREASURY_CONTRACT, B_WELL_KNOWN_SENDER)),
                contractUpdate(WELL_KNOWN_TREASURY_CONTRACT).properlyEmptyingAdminKey());
    }

    private static final String TOKEN_UNIT_FROM_TO_OTHERS_TXN = "transferTokenUnitFromToOthers";

    /**
     * Returns a multi-step operation that does one of each of the {@code transferToken}, {@code
     * transferTokens}, {@code transferNFT}, {@code transferNFTs} with the given expected status.
     * Every operation adds all the needed signatures to the sigMap using full-prefix signatures.
     */
    private HapiSpecOperation someWellKnownOperationsWithAllNeededSigsInSigMap(
            final AtomicReference<Address> fungibleTokenMirrorAddr,
            final AtomicReference<Address> nonFungibleTokenMirrorAddr,
            final AtomicReference<Address> aSenderAddr,
            final AtomicReference<Address> aReceiverAddr,
            final AtomicReference<Address> bSenderAddr,
            final AtomicReference<Address> bReceiverAddr,
            final ResponseCodeEnum expectedStatus) {
        return blockingOrder(
                sourcing(() -> contractCall(
                                WELL_KNOWN_TREASURY_CONTRACT,
                                TOKEN_UNIT_FROM_TO_OTHERS_TXN,
                                fungibleTokenMirrorAddr.get(),
                                aSenderAddr.get(),
                                aReceiverAddr.get())
                        .gas(2_000_000)
                        .alsoSigningWithFullPrefix(A_WELL_KNOWN_SENDER, B_WELL_KNOWN_SENDER)
                        .via(TOKEN_UNIT_FROM_TO_OTHERS_TXN)
                        .hasKnownStatus(expectedStatus)),
                sourcing(() -> contractCall(
                                WELL_KNOWN_TREASURY_CONTRACT,
                                "transferSerialNo1FromToOthers",
                                nonFungibleTokenMirrorAddr.get(),
                                aSenderAddr.get(),
                                aReceiverAddr.get())
                        .gas(2_000_000)
                        .alsoSigningWithFullPrefix(A_WELL_KNOWN_SENDER, B_WELL_KNOWN_SENDER)
                        .hasKnownStatus(expectedStatus)),
                sourcing(() -> contractCall(
                                WELL_KNOWN_TREASURY_CONTRACT,
                                "transferNFTSerialNos2And3ToFromToOthers",
                                nonFungibleTokenMirrorAddr.get(),
                                aSenderAddr.get(),
                                aReceiverAddr.get(),
                                bSenderAddr.get(),
                                bReceiverAddr.get())
                        .gas(2_000_000)
                        .alsoSigningWithFullPrefix(A_WELL_KNOWN_SENDER, B_WELL_KNOWN_SENDER)
                        .hasKnownStatus(expectedStatus)),
                sourcing(() -> contractCall(
                                WELL_KNOWN_TREASURY_CONTRACT,
                                "transferTokenUnitsFromToOthers",
                                fungibleTokenMirrorAddr.get(),
                                aSenderAddr.get(),
                                aReceiverAddr.get(),
                                bSenderAddr.get(),
                                bReceiverAddr.get())
                        .gas(2_000_000)
                        .alsoSigningWithFullPrefix(A_WELL_KNOWN_SENDER, B_WELL_KNOWN_SENDER)
                        .hasKnownStatus(expectedStatus)));
    }

    @HapiTest
    final Stream<DynamicTest> canStillTransferByVirtueOfContractIdInEOAThreshold() {
        final var fungibleToken = "token";
        final var managementContract = "DoTokenManagement";
        final AtomicReference<Address> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<Address> controlledSpenderAddr = new AtomicReference<>();
        final AtomicReference<Address> receiverAddr = new AtomicReference<>();
        final var threshKeyShape = KeyShape.threshOf(1, CONTRACT, SECP256K1);
        final var controlledSpender = "controlledSpender";
        final var receiver = "receiver";
        final var controlledSpenderKey = "controlledSpenderKey";

        return hapiTest(
                uploadInitCode(managementContract),
                // Create an immutable contract with a method
                // transferViaThresholdContractKey()
                // that tries to transfer token units from a spender to a receiver
                contractCreate(managementContract).gas(4_000_000L).omitAdminKey(),
                // Setup a 1/2 threshold key with this contract's ID as the first key
                newKeyNamed(controlledSpenderKey).shape(threshKeyShape.signedWith(sigs(managementContract, ON))),
                // Assign this key to an account
                cryptoCreate(controlledSpender)
                        .key(controlledSpenderKey)
                        .exposingCreatedIdTo(id -> controlledSpenderAddr.set(idAsHeadlongAddress(id))),
                // Make this account the treasury of a fungible token
                tokenCreate(fungibleToken)
                        .exposingAddressTo(tokenMirrorAddr::set)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000_000)
                        .treasury(controlledSpender),
                // Create a receiver
                cryptoCreate(receiver)
                        .maxAutomaticTokenAssociations(1)
                        .exposingCreatedIdTo(id -> receiverAddr.set(idAsHeadlongAddress(id))),
                // And now transfer from the controlled spender (treasury with 1M balance)
                // without its signature, by virtue of being in the threshold key
                sourcing(() -> contractCall(
                                managementContract,
                                "transferViaThresholdContractKey",
                                tokenMirrorAddr.get(),
                                controlledSpenderAddr.get(),
                                receiverAddr.get())
                        .gas(2_000_000)
                        .via("txn")),
                // Validate the receiver really did get a unit
                getAccountBalance(receiver).logged().hasTokenBalance(fungibleToken, 1));
    }

    @HapiTest
    final Stream<DynamicTest> contractKeysStillHaveSpecificityNoMatterTopLevelSignatures() {
        final var fungibleToken = "token";
        final var managementContract = "DoTokenManagement";
        final var otherContractAsKey = "otherContractAsKey";
        final var tmpAdminKey = "tmpAdminKey";
        final var associatedAccount = "associatedAccount";
        final AtomicReference<Address> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<Address> accountAddr = new AtomicReference<>();

        return hapiTest(
                uploadInitCode(managementContract, PAY_RECEIVABLE_CONTRACT),
                newKeyNamed(tmpAdminKey),
                contractCreate(managementContract).gas(4_000_000L).adminKey(tmpAdminKey),
                // Just create some other contract to be the real admin key
                contractCreate(PAY_RECEIVABLE_CONTRACT).gas(4_000_000L),
                newKeyNamed(otherContractAsKey).shape(CONTRACT.signedWith(PAY_RECEIVABLE_CONTRACT)),
                cryptoCreate(associatedAccount).keyShape(SECP256K1_ON).exposingEvmAddressTo(accountAddr::set),
                tokenCreate(fungibleToken)
                        .exposingAddressTo(tokenMirrorAddr::set)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000_000)
                        .treasury(managementContract)
                        .supplyKey(otherContractAsKey)
                        .wipeKey(otherContractAsKey)
                        .kycKey(otherContractAsKey)
                        .pauseKey(otherContractAsKey)
                        .freezeKey(otherContractAsKey),
                tokenAssociate(associatedAccount, fungibleToken),
                contractUpdate(managementContract).properlyEmptyingAdminKey(),
                // Confirm the contract is really immutable
                getContractInfo(managementContract).has(contractWith().immutableContractKey(managementContract)),
                // And now test a bunch of management functions are not authorized by
                // the management contract's ContractID key under these conditions;
                // even when it is the token treasury, and 0.0.2 has a top-level signature
                sourcing(() -> contractCall(managementContract, "justBurnFungible", tokenMirrorAddr.get())
                        .gas(15_000_000L)
                        .via("burnTxn")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "burnTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                sourcing(() -> contractCall(
                                managementContract, "justFreezeAccount", tokenMirrorAddr.get(), accountAddr.get())
                        .gas(15_000_000L)
                        .via("freezeTxn")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "freezeTxn", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_SIGNATURE)),
                sourcing(
                        () -> contractCall(managementContract, "justGrantKyc", tokenMirrorAddr.get(), accountAddr.get())
                                .gas(15_000_000L)
                                .via("grantTxn")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "grantTxn", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_SIGNATURE)),
                sourcing(() -> contractCall(
                                managementContract, "justRevokeKyc", tokenMirrorAddr.get(), accountAddr.get())
                        .gas(15_000_000L)
                        .via("revokeTxn")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "revokeTxn", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_SIGNATURE)),
                sourcing(() -> contractCall(
                                managementContract, "justWipeFungible", tokenMirrorAddr.get(), accountAddr.get())
                        .gas(15_000_000L)
                        .via("wipeTxn")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "wipeTxn", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_SIGNATURE)));
    }
}
