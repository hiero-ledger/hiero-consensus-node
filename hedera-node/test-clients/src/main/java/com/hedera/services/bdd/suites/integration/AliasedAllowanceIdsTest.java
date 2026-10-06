// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_DELEGATING_SPENDER;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.utilops.mod.BodyMutation;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NftAllowance;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Covers NFT allowances that name an account with an alias rather than an account number. Such an id is a reference
 * to some other account, so it must resolve to one; only a genuinely unset owner falls back to the payer.
 *
 * <p>{@code CryptoApproveAllowanceHandler#preHandle} picks one of the owner and the delegating spender as the account
 * that must sign, and leaves the other unexamined: with {@code approved_for_all} set it requires the owner, otherwise
 * it requires the delegating spender.
 */
@Tag(INTEGRATION)
@TargetEmbeddedMode(CONCURRENT)
@Execution(ExecutionMode.SAME_THREAD)
public class AliasedAllowanceIdsTest {

    private static final String OWNER = "aliasedAllowanceOwner";
    private static final String SPENDER = "aliasedAllowanceSpender";
    private static final String DELEGATE = "aliasedAllowanceDelegate";
    private static final String NFT = "aliasedAllowanceNft";
    private static final String SUPPLY_KEY = "aliasedAllowanceSupplyKey";
    private static final String DELEGATE_KEY = "aliasedAllowanceDelegateKey";

    /** An EVM address matching no account - the shape an SDK sends when naming an account by alias. */
    private static final ByteString EVM_ALIAS = ByteString.copyFrom(new byte[] {
        (byte) 0xA0,
        (byte) 0xA1,
        (byte) 0xA2,
        (byte) 0xA3,
        (byte) 0xA4,
        (byte) 0xA5,
        (byte) 0xA6,
        (byte) 0xA7,
        (byte) 0xA8,
        (byte) 0xA9,
        (byte) 0xAA,
        (byte) 0xAB,
        (byte) 0xAC,
        (byte) 0xAD,
        (byte) 0xAE,
        (byte) 0xAF,
        (byte) 0xB0,
        (byte) 0xB1,
        (byte) 0xB2,
        (byte) 0xB3
    });

    private static final AccountID ALIASED_ID =
            AccountID.newBuilder().setAlias(EVM_ALIAS).build();

    /**
     * A delegated allowance whose owner is named by alias fails with {@code INVALID_ALLOWANCE_OWNER_ID}: the alias
     * belongs to no account, and an owner that is present but unresolvable is not the payer.
     */
    @HapiTest
    Stream<DynamicTest> aliasedOwnerIsRejected() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(DELEGATE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(OWNER),
                mintToken(NFT, List.of(ByteString.copyFromUtf8("a"))),
                // Grant the delegate approveForAll over the payer's NFTs, so that every other requirement of the
                // delegated allowance below is met - the delegate may sub-delegate the payer's serials, and the payer
                // holds serial 1. The aliased owner is then the only thing the resulting status can be attributed to.
                cryptoApproveAllowance().payingWith(OWNER).addNftAllowance(OWNER, NFT, DELEGATE, true, List.of()),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addDelegatedNftAllowance(OWNER, NFT, SPENDER, DELEGATE, false, List.of(1L))
                        .withBodyMutation(BodyMutation.withTransform(mutatingNftAllowance(a -> a.setOwner(ALIASED_ID))))
                        .signedBy(OWNER, DELEGATE)
                        .hasKnownStatus(INVALID_ALLOWANCE_OWNER_ID));
    }

    /**
     * A delegating spender named by alias must name an account, so an alias belonging to none fails with
     * {@code INVALID_DELEGATING_SPENDER}.
     */
    @HapiTest
    Stream<DynamicTest> aliasedDelegatingSpenderIsRejected() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(OWNER),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NFT, SPENDER, true, List.of())
                        .withBodyMutation(BodyMutation.withTransform(
                                mutatingNftAllowance(a -> a.setDelegatingSpender(ALIASED_ID))))
                        .signedBy(OWNER)
                        .hasKnownStatus(INVALID_DELEGATING_SPENDER));
    }

    /**
     * The same rejection when the alias does resolve. The delegate here is a real account that the alias map really
     * points at, and it is still refused, because allowances look ids up by account number and never consult that map.
     *
     * <p>{@code approved_for_all} is set so that pre-handle requires the owner's signature and leaves the delegating
     * spender unexamined; that is what lets the aliased id reach handle, where the lookup happens.
     */
    @HapiTest
    Stream<DynamicTest> resolvableAliasedDelegatingSpenderIsRejected() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(DELEGATE_KEY).shape(KeyShape.ED25519),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                // Auto-creation is what puts an alias in state; the created account's alias is its serialized key,
                // and the query below fails outright unless that alias really resolves
                cryptoTransfer(tinyBarsFromAccountToAlias(OWNER, DELEGATE_KEY, ONE_HBAR)),
                doingContextual(spec -> updateSpecFor(spec, DELEGATE_KEY)),
                getAliasedAccountInfo(DELEGATE_KEY),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(OWNER),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NFT, SPENDER, true, List.of())
                        .withBodyMutation((builder, spec) ->
                                mutatingNftAllowance(a -> a.setDelegatingSpender(AccountID.newBuilder()
                                                .setAlias(spec.registry()
                                                        .getKey(DELEGATE_KEY)
                                                        .toByteString())
                                                .build()))
                                        .apply(builder.build())
                                        .toBuilder())
                        .signedBy(OWNER)
                        .hasKnownStatus(INVALID_DELEGATING_SPENDER));
    }

    /** Rewrites the transaction's single NFT allowance, which the DSL cannot express with an aliased id. */
    private static UnaryOperator<TransactionBody> mutatingNftAllowance(
            final UnaryOperator<NftAllowance.Builder> mutation) {
        return body -> {
            final var op = body.getCryptoApproveAllowance().toBuilder();
            op.setNftAllowances(0, mutation.apply(op.getNftAllowances(0).toBuilder()));
            return body.toBuilder().setCryptoApproveAllowance(op).build();
        };
    }
}
