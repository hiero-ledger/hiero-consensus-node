// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.transferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.keys.KeyShape;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(INTEGRATION)
@TargetEmbeddedMode(CONCURRENT)
public class AtomicBatchIntegrationTest {

    @HapiTest
    @DisplayName("Validate crypto transfer precompile gas used for inner transaction")
    final Stream<DynamicTest> validateInnerCallToCryptoTransferPrecompile() {
        final var sender = "sender";
        final var receiver = "receiver";
        final var gasToOffer = 2_000_000L;
        final var transferContract = "AtomicCryptoTransfer";
        final AtomicLong gasUsed = new AtomicLong(0);
        final KeyShape simpleContractKeyShape = KeyShape.threshOf(1, KeyShape.SIMPLE, CONTRACT);

        final AtomicReference<Address> senderAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddress = new AtomicReference<>();

        // call parameters
        final Supplier<Tuple> transferListSupplier = () -> transferList()
                .withAccountAmounts(
                        accountAmount(senderAddress.get(), -ONE_HBAR, false),
                        accountAmount(receiverAddress.get(), ONE_HBAR, false))
                .build();
        final var EMPTY_TUPLE_ARRAY = new Tuple[] {};

        return hapiTest(
                // deploy the contract
                uploadInitCode(transferContract),
                contractCreate(transferContract).gas(gasToOffer),

                // create sender and receiver with proper keys and expose their addresses
                newKeyNamed("key").shape(simpleContractKeyShape.signedWith(sigs(ON, transferContract))),
                cryptoCreate(sender).key("key").balance(ONE_HUNDRED_HBARS).exposingEvmAddressTo(senderAddress::set),
                cryptoCreate(receiver).key("key").balance(0L).exposingEvmAddressTo(receiverAddress::set),
                cryptoCreate("operator"),

                // Simple transfer between sender, receiver
                sourcing(() -> contractCall(
                                transferContract,
                                "transferMultipleTokens",
                                transferListSupplier.get(),
                                EMPTY_TUPLE_ARRAY)
                        .via("cryptoTransferTxn")
                        .gas(gasToOffer)),

                // save precompile gas used
                withOpContext((spec, op) -> {
                    final var callRecord = getTxnRecord("cryptoTransferTxn")
                            .andAllChildRecords()
                            .logged();
                    allRunFor(spec, callRecord);
                    gasUsed.set(callRecord
                            .getFirstNonStakingChildRecord()
                            .getContractCallResult()
                            .getGasUsed());
                }),

                // transfer hbars via precompile as inner batch txn
                sourcing(() -> atomicBatch(contractCall(
                                        transferContract,
                                        "transferMultipleTokens",
                                        transferListSupplier.get(),
                                        EMPTY_TUPLE_ARRAY)
                                .batchKey("operator")
                                .via("cryptoTransferFromBatch")
                                .gas(gasToOffer))
                        .payingWith("operator")),

                // validate precompile used gas is the same as in the previous call
                sourcing(() -> childRecordsCheck(
                        "cryptoTransferFromBatch",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith().gasUsed(gasUsed.get())))));
    }
}
