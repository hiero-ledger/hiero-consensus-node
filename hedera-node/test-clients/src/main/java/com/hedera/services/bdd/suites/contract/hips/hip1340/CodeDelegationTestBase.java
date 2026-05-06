// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.esaulpaugh.headlong.abi.Address.toChecksumAddress;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.useAddressOfKey;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.AccountID;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.utility.CommonUtils;

public abstract class CodeDelegationTestBase {
    protected static final Address HTS_HOOKS_CONTRACT_ADDRESS =
            Address.wrap("0x000000000000000000000000000000000000016D");
    protected static final String DELEGATING_ACCOUNT = "delegating_account";
    protected static final String DELEGATING_ACCOUNT_1 = DELEGATING_ACCOUNT + "_1";
    protected static final String DELEGATING_ACCOUNT_2 = DELEGATING_ACCOUNT + "_2";
    protected static final String DELEGATING_ACCOUNT_3 = DELEGATING_ACCOUNT + "_3";
    protected static final String DELEGATING_ACCOUNT_ID = "delegating_account_id";
    protected static final String DELEGATING_ACCOUNT_ID_1 = DELEGATING_ACCOUNT_ID + "_1";
    protected static final String DELEGATING_ACCOUNT_ID_2 = DELEGATING_ACCOUNT_ID + "_2";
    protected static final String DELEGATING_ACCOUNT_ID_3 = DELEGATING_ACCOUNT_ID + "_3";
    protected static final String CONTRACT = "CreateTrivial";
    protected static final String REVERTING_CONTRACT = "InternalCallee";
    protected static final String CREATION = "creation";
    protected static final String DELEGATION_SET = "delegation_set";
    protected static final String DELEGATION_CALL = "delegation_call";
    protected static final String DELEGATION_RESET = "delegation_reset";
    protected static final String PAYER_KEY = "PayerAccountKey";
    protected static final String PAYER = "PayerAccount";
    protected static final String CODE_DELEGATION_CONTRACT = "CodeDelegationContract";

    private final AtomicInteger accountIdCounter = new AtomicInteger(0);

    protected record EvmAccount(
            String keyName, String name, AccountID accountId, Address evmAddress, Address longZeroEvmAddress) {
        String evmAddressHex() {
            return toChecksumAddress(evmAddress.value()).replace("0x", "");
        }

        byte[] evmAddressBytes() {
            return CommonUtils.unhex(evmAddressHex());
        }
    }

    protected record EvmContract(String name, Address address) {
        String evmAddressHex() {
            return toChecksumAddress(address.value()).replace("0x", "");
        }

        ByteString evmAddressPbjBytes() {
            return ByteString.fromHex(evmAddressHex());
        }

        byte[] evmAddressBytes() {
            return CommonUtils.unhex(evmAddressHex());
        }
    }

    protected EvmAccount createEvmAccountWithKey(HapiSpec spec) {
        final var id = accountIdCounter.getAndIncrement();
        final var name = "ACCOUNT_" + id;
        final var keyName = "ACCOUNT_KEY_" + id;
        final AtomicReference<Address> keyAliasEvmAddress = new AtomicReference<>();
        final AtomicReference<AccountID> accountId = new AtomicReference<>();
        final AtomicReference<Address> longZeroEvmAddress = new AtomicReference<>();
        allRunFor(spec, newKeyNamed(keyName).shape(SECP_256K1_SHAPE));
        final var key = spec.registry().getKey(keyName);
        final var keyEvmAddress = ByteString.copyFrom(
                recoverAddressFromPubKey(key.getECDSASecp256K1().toByteArray()));
        allRunFor(
                spec,
                cryptoCreate(name)
                        .key(keyName)
                        .alias(keyEvmAddress)
                        .exposingCreatedIdTo(accountId::set)
                        .exposingEvmAddressTo(longZeroEvmAddress::set),
                useAddressOfKey(keyName, keyAliasEvmAddress::set));
        return new EvmAccount(keyName, name, accountId.get(), keyAliasEvmAddress.get(), longZeroEvmAddress.get());
    }

    protected EvmAccount createFundedEvmAccountWithKey(HapiSpec spec, long hbarAmount) {
        final var account = createEvmAccountWithKey(spec);
        allRunFor(spec, cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, account.name, hbarAmount)));
        return account;
    }

    protected EvmContract deployEvmContract(HapiSpec spec, String name) {
        final AtomicReference<Address> evmAddress = new AtomicReference<>();
        allRunFor(
                spec,
                uploadInitCode(name),
                contractCreate(name).exposingAddressTo(evmAddress::set).gas(2_000_000L));
        return new EvmContract(name, evmAddress.get());
    }

    protected void nativeSetCodeDelegation(HapiSpec spec, EvmAccount account, EvmContract target) {
        nativeSetCodeDelegation(spec, account, target.evmAddressPbjBytes());
    }

    protected void nativeSetCodeDelegation(HapiSpec spec, EvmAccount account, ByteString targetAddress) {
        allRunFor(
                spec,
                cryptoUpdate(account.name()).delegationAddress(targetAddress),
                getAccountInfo(account.name()).has(accountWith().delegationAddress(targetAddress)));
    }

    protected static void createSecp256k1Keys(HapiSpec spec, String... names) {
        allRunFor(
                spec,
                Arrays.stream(names)
                        .map(name -> (HapiSpecOperation) newKeyNamed(name).shape(SECP_256K1_SHAPE))
                        .toArray(HapiSpecOperation[]::new));
    }

    protected static void verifyDelegationSet(HapiSpec spec, Address target, String... accountNames) {
        allRunFor(
                spec,
                Arrays.stream(accountNames)
                        .map(name -> (HapiSpecOperation)
                                getAliasedAccountInfo(name).isNotHollow().hasDelegationAddress(target))
                        .toArray(HapiSpecOperation[]::new));
    }

    protected static void verifyDelegationCleared(HapiSpec spec, String... accountNames) {
        allRunFor(
                spec,
                Arrays.stream(accountNames)
                        .map(name ->
                                (HapiSpecOperation) getAliasedAccountInfo(name).hasNoDelegation())
                        .toArray(HapiSpecOperation[]::new));
    }

    protected static void createPayerAccountWithAlias(HapiSpec spec) {
        allRunFor(
                spec,
                newKeyNamed(PAYER_KEY).shape(SECP_256K1_SHAPE).exposingKeyTo(key -> {
                    final var evmAddress = ByteString.copyFrom(
                            recoverAddressFromPubKey(key.getECDSASecp256K1().toByteArray()));
                    spec.registry()
                            .saveAccountAlias(
                                    PAYER_KEY,
                                    AccountID.newBuilder().setAlias(evmAddress).build());
                }),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS).between(GENESIS, PAYER_KEY))
                        .via(CREATION),
                getTxnRecord(CREATION).exposingCreationsTo(creations -> {
                    final var createdId = HapiPropertySource.asAccount(creations.getFirst());
                    spec.registry().saveAccountId(PAYER, createdId);
                }));
    }

    protected static byte[] encodeHtsTransfer(Address receiver, BigInteger amount) {
        return new Function("transfer(address,uint256)")
                .encodeCall(Tuple.of(receiver, amount))
                .array();
    }
}
