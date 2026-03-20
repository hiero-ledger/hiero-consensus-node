// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertCreationMaxAssociations;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertCreationViaCallMaxAssociations;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@OrderedInIsolation
@SuppressWarnings("java:S1192")
public class ContractCreateSerialSuite {

    private static final String FUNGIBLE_TOKEN = "fungible";
    @SuppressWarnings("java:S2068")
    private static final String MULTI_KEY = "multiKey";

    @LeakyHapiTest(overrides = {"ledger.maxAutoAssociations"})
    final Stream<DynamicTest> contractCreationsHaveValidAssociations() {
        final var initCreateContract = "ParentChildTransfer";
        final var slotUserContract = "SlotUser";
        final var multiPurpose = "Multipurpose";
        final var createContract = "CreateTrivial";
        return hapiTest(
                overriding("ledger.maxAutoAssociations", "5000"),
                newKeyNamed(MULTI_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .initialSupply(1000)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(initCreateContract, createContract, multiPurpose, slotUserContract),
                contractCreate(initCreateContract)
                        .refusingEthConversion()
                        .via("constructorWithoutExplicitAssociations")
                        .hasKnownStatus(SUCCESS),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, initCreateContract))
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                contractCreate(createContract)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(0)
                        .hasKnownStatus(SUCCESS),
                contractCreate(multiPurpose)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(3)
                        .hasKnownStatus(SUCCESS),
                contractCreate(slotUserContract)
                        .refusingEthConversion()
                        .via("constructorCreate")
                        .maxAutomaticTokenAssociations(5)
                        .hasKnownStatus(SUCCESS),
                contractCall(createContract, "create")
                        .via("createViaCall")
                        .gas(400_000L)
                        .hasKnownStatus(SUCCESS),
                ethereumCall(createContract, "create")
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .via("ethereumCreate")
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .hasKnownStatus(SUCCESS),
                getContractInfo(initCreateContract)
                        .has(contractWith().maxAutoAssociations(0))
                        .logged(),
                getContractInfo(multiPurpose)
                        .has(contractWith().maxAutoAssociations(3))
                        .logged(),
                getContractInfo(slotUserContract)
                        .has(contractWith().maxAutoAssociations(5))
                        .logged(),
                assertCreationMaxAssociations("constructorWithoutExplicitAssociations", 1, 0),
                assertCreationMaxAssociations("constructorCreate", 1, 5),
                assertCreationViaCallMaxAssociations("createViaCall", 0, 0),
                assertCreationViaCallMaxAssociations("ethereumCreate", 0, 0));
    }

    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> childCreationsHaveExpectedKeysWithOmittedAdminKey() {
        final AtomicLong firstStickId = new AtomicLong();
        final AtomicLong secondStickId = new AtomicLong();
        final AtomicLong thirdStickId = new AtomicLong();
        final var txn = "creation";
        final var contract = "Fuse";

        return hapiTest(
                overriding("contracts.evm.version", "v0.46"),
                uploadInitCode(contract),
                contractCreate(contract).omitAdminKey().gas(600_000).via(txn),
                withOpContext((spec, opLog) -> {
                    final var op = getTxnRecord(txn);
                    allRunFor(spec, op);
                    final var record = op.getResponseRecord();
                    final var creationResult = record.getContractCreateResult();
                    final var createdIds = creationResult.getCreatedContractIDsList();
                    assertEquals(4, createdIds.size(), "Expected four creations but got " + createdIds);
                    firstStickId.set(createdIds.get(1).getContractNum());
                    secondStickId.set(createdIds.get(2).getContractNum());
                    thirdStickId.set(createdIds.get(3).getContractNum());
                }),
                sourcing(() -> getContractInfo(String.valueOf(firstStickId.get()))
                        .has(contractWith().immutableContractKey(String.valueOf(firstStickId.get())))
                        .logged()),
                sourcing(() -> getContractInfo(String.valueOf(secondStickId.get()))
                        .has(contractWith().immutableContractKey(String.valueOf(secondStickId.get())))
                        .logged()),
                sourcing(() ->
                        getContractInfo(String.valueOf(thirdStickId.get())).logged()),
                contractCall(contract, "light").via("lightTxn"),
                sourcing(() -> getContractInfo(String.valueOf(firstStickId.get()))
                        .has(contractWith().isDeleted())),
                sourcing(() -> getContractInfo(String.valueOf(secondStickId.get()))
                        .has(contractWith().isDeleted())),
                sourcing(() -> getContractInfo(String.valueOf(thirdStickId.get()))
                        .has(contractWith().isDeleted())));
    }

    @LeakyHapiTest
    final Stream<DynamicTest> delegateContractIdRequiredForTransferInDelegateCall() {
        final var justSendContract = "JustSend";
        final var sendInternalAndDelegateContract = "SendInternalAndDelegate";

        final var beneficiary = "civilian";
        final var totalToSend = 1_000L;
        final var origKey = KeyShape.threshOf(1, SIMPLE, CONTRACT);
        final var revisedKey = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT);
        final var newKey = "delegateContractKey";

        final AtomicReference<ContractID> justSendContractId = new AtomicReference<>();
        final AtomicReference<AccountID> beneficiaryAccountId = new AtomicReference<>();

        return hapiTest(
                uploadInitCode(justSendContract, sendInternalAndDelegateContract),
                // refuse eth conversion because we can't delegate call contract by contract num
                // when it has EVM address alias (isNotPriority check fails)
                contractCreate(justSendContract)
                        .gas(300_000L)
                        .exposingContractIdTo(justSendContractId::set)
                        .refusingEthConversion(),
                contractCreate(sendInternalAndDelegateContract).gas(300_000L).balance(2 * totalToSend),
                cryptoCreate(beneficiary)
                        .balance(0L)
                        .keyShape(origKey.signedWith(sigs(ON, sendInternalAndDelegateContract)))
                        .receiverSigRequired(true)
                        .exposingCreatedIdTo(beneficiaryAccountId::set),
                /* Without delegateContractId permissions, the second send via delegate call will
                 * fail, so only half of totalToSend will make it to the beneficiary. (Note the entire
                 * call doesn't fail because exceptional halts in "raw calls" don't automatically
                 * propagate up the stack like a Solidity revert does.) */
                sourcing(() -> contractCall(
                        sendInternalAndDelegateContract,
                        "sendRepeatedlyTo",
                        new BigInteger(asSolidityAddress(justSendContractId.get())),
                        new BigInteger(asSolidityAddress(beneficiaryAccountId.get())),
                        BigInteger.valueOf(totalToSend / 2))),
                getAccountBalance(beneficiary).hasTinyBars(totalToSend / 2),
                /* But now we update the beneficiary to have a delegateContractId */
                newKeyNamed(newKey).shape(revisedKey.signedWith(sigs(ON, sendInternalAndDelegateContract))),
                cryptoUpdate(beneficiary).key(newKey),
                sourcing(() -> contractCall(
                        sendInternalAndDelegateContract,
                        "sendRepeatedlyTo",
                        new BigInteger(asSolidityAddress(justSendContractId.get())),
                        new BigInteger(asSolidityAddress(beneficiaryAccountId.get())),
                        BigInteger.valueOf(totalToSend / 2))),
                getAccountBalance(beneficiary).hasTinyBars(3 * (totalToSend / 2)));
    }
}
