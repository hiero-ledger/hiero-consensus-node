// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies the {@code Approval} event emitted when an approved-for-all operator approves (or revokes) a spender for a
 * single NFT through the HTS system contract names the NFT's owner as {@code owner}; for both the ERC-721
 * {@code approve()} redirect and the classic {@code approveNFT()} function.
 */
@Tag(SMART_CONTRACT)
public class NftOperatorApprovalEventTest {
    private static final String APPROVAL_SIGNATURE = "Approval(address,address,uint256)";
    private static final String MULTI_KEY = "multiKey";
    private static final String OWNER = "owner";
    private static final String SPENDER = "spender";
    private static final String NFT = "nft";
    private static final String ERC_OPERATOR = "SomeERC721Scenarios";
    private static final String CLASSIC_OPERATOR = "TokenTransferContract";

    @HapiTest
    final Stream<DynamicTest> ercApproveByOperatorLogsNftOwner() {
        return hapiTest(
                setUpOwnerWithOperator(ERC_OPERATOR),
                contractCall(ERC_OPERATOR, "doSpecificApproval", spec ->
                                new Object[] {tokenAddress(spec), accountAddress(spec, SPENDER), BigInteger.ONE})
                        .gas(1_000_000L)
                        .via("ercApprove"),
                getTokenNftInfo(NFT, 1L).hasAccountID(OWNER).hasSpenderID(SPENDER),
                approvalLogsOwner("ercApprove", SPENDER, false),
                contractCall(ERC_OPERATOR, "revokeSpecificApproval", spec ->
                                new Object[] {tokenAddress(spec), BigInteger.ONE})
                        .gas(1_000_000L)
                        .via("ercRevoke"),
                getTokenNftInfo(NFT, 1L).hasAccountID(OWNER).hasNoSpender(),
                approvalLogsOwner("ercRevoke", null, false));
    }

    @HapiTest
    final Stream<DynamicTest> classicApproveByOperatorLogsNftOwner() {
        return hapiTest(
                setUpOwnerWithOperator(CLASSIC_OPERATOR),
                contractCall(CLASSIC_OPERATOR, "approveNFTPublic", spec ->
                                new Object[] {tokenAddress(spec), accountAddress(spec, SPENDER), BigInteger.ONE})
                        .gas(1_000_000L)
                        .via("classicApprove"),
                getTokenNftInfo(NFT, 1L).hasAccountID(OWNER).hasSpenderID(SPENDER),
                approvalLogsOwner("classicApprove", SPENDER, true),
                contractCall(CLASSIC_OPERATOR, "approveNFTPublic", spec ->
                                new Object[] {tokenAddress(spec), asHeadlongAddress(new byte[20]), BigInteger.ONE})
                        .gas(1_000_000L)
                        .via("classicRevoke"),
                getTokenNftInfo(NFT, 1L).hasAccountID(OWNER).hasNoSpender(),
                approvalLogsOwner("classicRevoke", null, true));
    }

    /**
     * Creates an NFT whose serial 1 is owned by {@link #OWNER}, and the given operator contract to which the owner
     * grants an approve-for-all allowance over the token.
     */
    private static SpecOperation setUpOwnerWithOperator(final String operator) {
        return blockingOrder(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .supplyKey(MULTI_KEY),
                mintToken(NFT, List.of(ByteString.copyFromUtf8("1"))),
                tokenAssociate(OWNER, NFT),
                cryptoTransfer(movingUnique(NFT, 1L).between(TOKEN_TREASURY, OWNER)),
                uploadInitCode(operator),
                contractCreate(operator).gas(4_000_000L),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NFT, operator, true, List.of())
                        .fee(ONE_HBAR));
    }

    /**
     * Asserts the given transaction logged {@code Approval(owner, approved, 1)} from the token, with the
     * {@link #OWNER} as {@code owner}; and, for the classic operator, its {@code ResponseCode} event after.
     *
     * @param txn the operator's approval transaction
     * @param approved the approved spender, or null for a revocation
     * @param classicOperator whether the operator is the classic {@code approveNFT()} caller
     */
    private static SpecOperation approvalLogsOwner(
            final String txn, @Nullable final String approved, final boolean classicOperator) {
        return withOpContext((spec, opLog) -> {
            final var tokenNum = String.valueOf(spec.registry().getTokenID(NFT).getTokenNum());
            final var approvalLog = logWith()
                    .contract(tokenNum)
                    .withTopicsInOrder(List.of(
                            eventSignatureOf(APPROVAL_SIGNATURE),
                            parsedToByteString(spec.registry().getAccountID(OWNER)),
                            approved == null
                                    ? parsedToByteString(0L)
                                    : parsedToByteString(spec.registry().getAccountID(approved)),
                            parsedToByteString(1L)));
            allRunFor(
                    spec,
                    getTxnRecord(txn)
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .logs(
                                                    classicOperator
                                                            ? inOrder(
                                                                    approvalLog,
                                                                    logWith().contract(CLASSIC_OPERATOR))
                                                            : inOrder(approvalLog)))));
        });
    }

    private static Object tokenAddress(final HapiSpec spec) {
        return asHeadlongAddress(asAddress(spec.registry().getTokenID(NFT)));
    }

    private static Object accountAddress(final HapiSpec spec, final String account) {
        return asHeadlongAddress(asAddress(spec.registry().getAccountID(account)));
    }
}
