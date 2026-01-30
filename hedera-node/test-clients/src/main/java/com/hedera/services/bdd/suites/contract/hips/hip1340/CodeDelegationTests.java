// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.esaulpaugh.headlong.abi.Address.toChecksumAddress;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.useAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractLoginfo;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("Code Delegation Tests")
public class CodeDelegationTests {
    private static final String DELEGATION_TARGET_CONTRACT = "CodeDelegationTarget";

    private int accountCounter = 0;

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationFallbackMethod() {
        return hapiTest(withOpContext((spec, opLog) -> {
            /* Create a delegation target contract and an EOA delegating to it. */
            final var delegationTargetContract = createEvmContract(spec, DELEGATION_TARGET_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, delegatingEoa, delegationTargetContract);

            final AtomicReference<ContractLoginfo> logInfo = new AtomicReference<>();

            final var callData = "cafebabe";

            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            /* Call the EOA */
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmLongZeroAddressBytes(), 0)
                            .withExplicitParams(() -> callData)
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .exposingEventDataTo(logInfo::set));

            // Verify that the event originates from the EOA
            assertEquals(
                    delegatingEoa.longZeroAddress().value().longValueExact(),
                    logInfo.get().getContractID().getContractNum());

            // And verify the remaining fields of the event
            final var eventData = logInfo.get().getData().toByteArray();
            final var eventArgs = TupleType.parse("(address,uint256,bytes)").decode(eventData);

            final var senderInEvent = (Address) eventArgs.get(0);
            final var valueInEvent = (BigInteger) eventArgs.get(1);
            final var callDataInEvent = (byte[]) eventArgs.get(2);

            assertEquals(caller.keyAliasAddress(), senderInEvent);
            assertEquals(BigInteger.ZERO, valueInEvent);
            assertArrayEquals(Hex.decode(callData), callDataInEvent);
        }));
        // just some change
    }

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationStorage() {
        return hapiTest(withOpContext((spec, opLog) -> {
            /* Create a delegation target contract and an EOA delegating to it. */
            final var delegationTargetContract = createEvmContract(spec, DELEGATION_TARGET_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, delegatingEoa, delegationTargetContract);

            final AtomicReference<ContractLoginfo> logInfo = new AtomicReference<>();

            final var callData = new Function("storeAndEmit(uint256)")
                    .encodeCall(Tuple.singleton(BigInteger.valueOf(1234)))
                    .array();

            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmLongZeroAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(callData))
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .exposingEventDataTo(logInfo::set));

            // Verify that the event originates from the EOA
            assertEquals(
                    delegatingEoa.longZeroAddress().value().longValueExact(),
                    logInfo.get().getContractID().getContractNum());

            // And verify the remaining fields of the event
            final var eventData = logInfo.get().getData().toByteArray();
            final var eventArgs = TupleType.parse("(address,uint256,uint256)").decode(eventData);

            final var senderInEvent = (Address) eventArgs.get(0);
            final var valueInEvent = (BigInteger) eventArgs.get(1);
            final var paramInEvent = (BigInteger) eventArgs.get(2);

            assertEquals(caller.keyAliasAddress(), senderInEvent);
            assertEquals(BigInteger.ZERO, valueInEvent);
            assertEquals(BigInteger.valueOf(1234), paramInEvent);

            // Verify that the value is stored correctly
            final AtomicReference<ContractFunctionResult> getValueResult = new AtomicReference<>();
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmLongZeroAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(new Function("getValue()")
                                    .encodeCall(Tuple.EMPTY)
                                    .array()))
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .exposingRawResultTo(getValueResult::set));

            assertEquals(
                    BigInteger.valueOf(1234),
                    new BigInteger(getValueResult.get().getContractCallResult().toByteArray()));
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationInternalHtsTransfer() {
        return hapiTest(withOpContext((spec, opLog) -> {
            /* Create a delegation target contract and an EOA delegating to it. */
            final var delegationTargetContract = createEvmContract(spec, DELEGATION_TARGET_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, delegatingEoa, delegationTargetContract);

            final var token = "TOKEN_1";
            final AtomicReference<Address> tokenAddress = new AtomicReference<>();

            /* Create a token */
            allRunFor(
                    spec,
                    tokenCreate(token)
                            .initialSupply(1000)
                            .treasury(delegatingEoa.name())
                            .exposingAddressTo(tokenAddress::set));

            final var tokenReceiver = createEvmAccountWithKey(spec);
            allRunFor(spec, cryptoUpdate(tokenReceiver.name).maxAutomaticAssociations(100));

            final var innerTransferCallData = new Function("transfer(address,uint256)")
                    .encodeCall(Tuple.of(
                            tokenReceiver.longZeroAddress(), /* address */ BigInteger.valueOf(200L) /* amount */))
                    .array();

            final var callData = new Function("executeCall(address,uint256,bytes)")
                    .encodeCall(Tuple.of(
                            tokenAddress.get() /* call target */,
                            BigInteger.valueOf(0L) /* hbar value to pass */,
                            innerTransferCallData /* call data */))
                    .array();

            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            /* Call the EOA */
            allRunFor(
                    spec,
                    /* Verify the initial balance of the delegating EOA */
                    getAccountBalance(delegatingEoa.name()).hasTokenBalance(token, 1000),

                    /* Trigger an HTS token transfer by sending a transaction from the owner account */
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmLongZeroAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(callData))
                            .payingWith(payer.name())
                            .signingWith(delegatingEoa.keyName()),
                    getAccountBalance(delegatingEoa.name()).hasTokenBalance(token, 800),
                    getAccountBalance(tokenReceiver.name()).hasTokenBalance(token, 200L),

                    /* Trigger an HTS token transfer by sending a transaction from an unrelated account */
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmLongZeroAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(callData))
                            .payingWith(payer.name())
                            .signingWith(caller.keyName()),
                    getAccountBalance(delegatingEoa.name()).hasTokenBalance(token, 600),
                    getAccountBalance(tokenReceiver.name()).hasTokenBalance(token, 400L));
        }));
    }

    private record EvmAccount(String keyName, String name, Address longZeroAddress, Address keyAliasAddress) {
        String evmLongZeroAddressHex() {
            return toChecksumAddress(longZeroAddress.value()).replace("0x", "");
        }

        byte[] evmLongZeroAddressBytes() {
            return CommonUtils.unhex(evmLongZeroAddressHex());
        }
    }

    private record EvmContract(String name, Address address) {
        String evmAddressHex() {
            return toChecksumAddress(address.value()).replace("0x", "");
        }

        ByteString evmAddressPbjBytes() {
            return ByteString.fromHex(evmAddressHex());
        }
    }

    private EvmAccount createEvmAccountWithKey(HapiSpec spec) {
        final var name = "ACCOUNT_" + accountCounter;
        final var key = "ACCOUNT_KEY_" + accountCounter;
        accountCounter = accountCounter + 1;
        final AtomicReference<Address> longZeroEvmAddress = new AtomicReference<>();
        final AtomicReference<Address> keyAliasEvmAddress = new AtomicReference<>();
        allRunFor(
                spec,
                newKeyNamed(key).shape(SECP_256K1_SHAPE),
                cryptoCreate(name).key(key).exposingEvmAddressTo(longZeroEvmAddress::set),
                useAddressOfKey(key, keyAliasEvmAddress::set));
        return new EvmAccount(key, name, longZeroEvmAddress.get(), keyAliasEvmAddress.get());
    }

    private EvmAccount createFundedEvmAccountWithKey(HapiSpec spec, long hbarAmount) {
        final var account = createEvmAccountWithKey(spec);
        allRunFor(spec, cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, account.name, hbarAmount)));
        return account;
    }

    private EvmContract createEvmContract(HapiSpec spec, String name) {
        final AtomicReference<Address> evmAddress = new AtomicReference<>();
        allRunFor(
                spec,
                uploadInitCode(DELEGATION_TARGET_CONTRACT),
                contractCreate(DELEGATION_TARGET_CONTRACT)
                        .exposingAddressTo(evmAddress::set)
                        .gas(2_000_000L));
        return new EvmContract(name, evmAddress.get());
    }

    private void nativeSetCodeDelegation(HapiSpec spec, EvmAccount account, EvmContract target) {
        allRunFor(
                spec,
                cryptoUpdate(account.name()).delegationAddress(target.evmAddressPbjBytes()),
                getAccountInfo(account.name()).has(accountWith().delegationAddress(target.evmAddressPbjBytes())));
    }
}
