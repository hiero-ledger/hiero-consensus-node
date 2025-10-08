// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.precompile.ContractBurnHTSSuite.ALICE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(INTEGRATION)
@TargetEmbeddedMode(CONCURRENT)
class AtomicBatchIntegrationTest {

    @HapiTest
    @DisplayName("Validate burn precompile gas used for inner transaction")
    final Stream<DynamicTest> validateInnerCallToBurnPrecompile() {
        final var nft = "nft";
        final var gasToOffer = 2_000_000L;
        final var burnContract = "BurnToken";
        final var supplyKey = "supplyKey";
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        final KeyShape listOfPredefinedAndContract = KeyShape.threshOf(1, PREDEFINED_SHAPE, CONTRACT);
        final AtomicLong gasUsed = new AtomicLong(0);
        return hapiTest(
                cryptoCreate(ALICE).balance(10 * ONE_HUNDRED_HBARS),
                tokenCreate(nft)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(ALICE)
                        .adminKey(ALICE)
                        .treasury(ALICE)
                        .exposingAddressTo(tokenAddress::set),
                mintToken(nft, List.of(copyFromUtf8("1"), copyFromUtf8("2"))),
                uploadInitCode(burnContract),
                sourcing(() -> contractCreate(burnContract, tokenAddress.get())
                        .payingWith(ALICE)
                        .gas(gasToOffer)),
                newKeyNamed(supplyKey).shape(listOfPredefinedAndContract.signedWith(sigs(ALICE, burnContract))),
                tokenUpdate(nft).supplyKey(supplyKey).signedByPayerAnd(ALICE),

                // burn NFT via precompile and save the used gas
                contractCall(burnContract, "burnToken", BigInteger.ZERO, new long[] {1L})
                        .payingWith(ALICE)
                        .alsoSigningWithFullPrefix(supplyKey)
                        .gas(gasToOffer)
                        .via("burn"),

                // save precompile gas used
                withOpContext((spec, op) -> {
                    final var callRecord = getTxnRecord("burn").andAllChildRecords();
                    allRunFor(spec, callRecord);
                    gasUsed.set(callRecord
                            .getFirstNonStakingChildRecord()
                            .getContractCallResult()
                            .getGasUsed());
                }),

                // burn NFT via precompile as inner batch txn
                atomicBatch(contractCall(burnContract, "burnToken", BigInteger.ZERO, new long[] {2L})
                                .batchKey(ALICE)
                                .payingWith(ALICE)
                                .alsoSigningWithFullPrefix(supplyKey)
                                .gas(gasToOffer)
                                .via("burnFromBatch"))
                        .payingWith(ALICE),

                // validate precompile used gas is the same as in the previous call
                sourcing(() -> childRecordsCheck(
                        "burnFromBatch",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith().gasUsed(gasUsed.get())))));
    }
}
