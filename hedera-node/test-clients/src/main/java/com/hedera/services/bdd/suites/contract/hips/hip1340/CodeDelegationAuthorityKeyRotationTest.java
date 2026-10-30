// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** Verifies that an EIP-7702 authorization signed by an ECDSA key that no longer controls the account is ignored. */
@Tag(SMART_CONTRACT)
public class CodeDelegationAuthorityKeyRotationTest extends CodeDelegationTestBase {
    @LeakyHapiTest(overrides = {"contracts.codeDelegations.enabled"})
    final Stream<DynamicTest> authorizationFromRotatedAwayEcdsaKeyIsIgnored() {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, overriding("contracts.codeDelegations.enabled", "true"));
            final var target = deployEvmContract(spec, CODE_DELEGATION_CONTRACT);
            final var authority = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var other = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var token = "AUTHORITY_TOKEN";
            allRunFor(
                    spec,
                    newKeyNamed("NEW_ED25519_KEY").shape(KeyShape.ED25519),
                    tokenCreate(token).initialSupply(1000).treasury(authority.name()),
                    cryptoUpdate(other.name()).maxAutomaticAssociations(10),
                    // account owner rotates away from the original ECDSA key
                    cryptoUpdate(authority.name())
                            .key("NEW_ED25519_KEY")
                            .signedBy(GENESIS, authority.keyName(), "NEW_ED25519_KEY"),
                    getAccountInfo(authority.name())
                            .has(com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith()
                                    .key("NEW_ED25519_KEY")));
            final var before = new AtomicLong();
            allRunFor(spec, getAccountBalance(authority.name()).exposingBalanceTo(before::set));
            // unrelated sender submits a type-4 tx carrying an authorization signed by the rotated-away ECDSA key
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(target.evmAddressBytes(), 0)
                            .withExplicitParams(() -> HexFormat.of()
                                    .formatHex(new Function("getValue()")
                                            .encodeCall(Tuple.EMPTY)
                                            .array()))
                            .type(EthTransactionType.EIP7702)
                            .addCodeDelegationWithNonce(target.address(), 0L, authority.keyName())
                            .gasLimit(2_000_000L)
                            .payingWith(other.name())
                            .signingWith(other.keyName())
                            .hasKnownStatusFrom(ResponseCodeEnum.SUCCESS, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED),
                    getAccountInfo(authority.name())
                            .has(com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith()
                                    .key("NEW_ED25519_KEY")
                                    .delegationAddress(com.google.protobuf.ByteString.EMPTY)));
            final long amount = 50 * ONE_HBAR;
            final var hbarCall = new Function("executeCall(address,uint256,bytes)")
                    .encodeCall(Tuple.of(other.evmAddress(), BigInteger.valueOf(amount), new byte[0]))
                    .array();
            final var htsCall = new Function("executeCall(address,uint256,bytes)")
                    .encodeCall(Tuple.of(
                            spec.registry().getTokenID(token) == null ? null : tokenAddr(spec, token),
                            BigInteger.ZERO,
                            encodeHtsTransfer(other.evmAddress(), BigInteger.valueOf(300L))))
                    .array();
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(authority.evmAddressBytes(), 0)
                            .withExplicitParams(() -> HexFormat.of().formatHex(hbarCall))
                            .gasLimit(2_000_000L)
                            .payingWith(other.name())
                            .signingWith(other.keyName())
                            .hasKnownStatusFrom(ResponseCodeEnum.SUCCESS, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                            .via("HBAR_TRANSFER_ATTEMPT"),
                    HapiEthereumCall.explicitlyTo(authority.evmAddressBytes(), 0)
                            .withExplicitParams(() -> HexFormat.of().formatHex(htsCall))
                            .gasLimit(2_000_000L)
                            .payingWith(other.name())
                            .signingWith(other.keyName())
                            .hasKnownStatusFrom(ResponseCodeEnum.SUCCESS, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                            .via("HTS_TRANSFER_ATTEMPT"));
            final var after = new AtomicLong();
            allRunFor(spec, getAccountBalance(authority.name()).exposingBalanceTo(after::set));
            org.junit.jupiter.api.Assertions.assertEquals(before.get(), after.get());
            allRunFor(
                    spec,
                    com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord("HBAR_TRANSFER_ATTEMPT")
                            .andAllChildRecords()
                            .logged(),
                    com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord("HTS_TRANSFER_ATTEMPT")
                            .andAllChildRecords()
                            .logged(),
                    getAccountBalance(authority.name()).hasTokenBalance(token, 1000L),
                    getAccountBalance(other.name()).hasTokenBalance(token, 0L));
        }));
    }

    private static com.esaulpaugh.headlong.abi.Address tokenAddr(
            com.hedera.services.bdd.spec.HapiSpec spec, String token) {
        final var id = spec.registry().getTokenID(token);
        return com.esaulpaugh.headlong.abi.Address.wrap(
                com.esaulpaugh.headlong.abi.Address.toChecksumAddress("0x" + String.format("%040x", id.getTokenNum())));
    }
}
