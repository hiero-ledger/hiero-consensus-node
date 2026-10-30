// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.node.app.service.contract.impl.exec.scope.AccountAuthorizingVerificationStrategy.withAdditionalPrimitiveStrategy;
import static com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision.INVALID;
import static com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision.VALID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_SECP256K1_KEY;
import static com.hedera.node.app.spi.key.KeyRequirement.anyOf;
import static com.hedera.node.app.spi.key.KeyRequirement.forAccount;
import static com.hedera.node.app.spi.key.KeyRequirement.forKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.service.contract.impl.exec.scope.AccountAuthorizingVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy.UseTopLevelSigs;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.spi.key.KeyRequirement;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AccountAuthorizingVerificationStrategyTest {
    private static final AccountID CALLER =
            AccountID.newBuilder().shardNum(1).realmNum(2).accountNum(100).build();
    private static final AccountID OTHER = CALLER.copyBuilder().accountNum(200).build();
    private static final Bytes ADDRESS = Bytes.fromHex("abcdef");

    @ParameterizedTest
    @MethodSource("sharedKeys")
    void executionAuthorizesOnlyTheCallerEvenWhenAccountsShareACompleteKey(final Key sharedKey) {
        final var context = contextWithSignatures(Set.of());
        final var strategy = scoped(CALLER, false, UseTopLevelSigs.NO);
        final var test = strategy.asRequirementTestIn(context, null);

        assertTrue(test.test(forAccount(CALLER, sharedKey)));
        assertFalse(test.test(forAccount(OTHER, sharedKey)));
        assertFalse(test.test(forKey(sharedKey)));
        assertTrue(context.keyVerifier().authorizingSimpleKeys().isEmpty());
    }

    @Test
    void alternativesRetainTheirOwnIdentities() {
        final var test =
                scoped(CALLER, false, UseTopLevelSigs.NO).asRequirementTestIn(contextWithSignatures(Set.of()), null);

        assertFalse(test.test(anyOf(List.of(forAccount(OTHER, AN_ED25519_KEY), forKey(AN_ED25519_KEY)))));
        assertTrue(test.test(anyOf(List.of(forAccount(OTHER, AN_ED25519_KEY), forAccount(CALLER, AN_ED25519_KEY)))));
    }

    @Test
    void contextualPrimitiveDecisionsReceiveEachOriginThroughoutCompoundKeys() {
        final var seen = new ArrayList<KeyRequirement>();
        final VerificationStrategy primitiveStrategy = new VerificationStrategy() {
            @Override
            public Decision decideForPrimitive(final Key key) {
                throw new AssertionError("Requirement identity must accompany every primitive");
            }

            @Override
            public Decision decideForPrimitive(final KeyRequirement requirement, final Key key) {
                seen.add(requirement);
                return INVALID;
            }
        };
        final var scoped = new AccountAuthorizingVerificationStrategy(CALLER, primitiveStrategy);
        final var requirement = forAccount(OTHER, thresholdKey());
        final var test = scoped.asRequirementTestIn(contextWithSignatures(Set.of()), null);

        assertFalse(test.test(requirement));
        assertEquals(List.of(requirement, requirement), seen);
    }

    @Test
    void explicitContractDelegationStillRespectsDelegatePermissions() {
        final var context = contextWithSignatures(Set.of());
        final var ordinary = scoped(CALLER, false, UseTopLevelSigs.NO).asRequirementTestIn(context, null);
        final var delegate = scoped(CALLER, true, UseTopLevelSigs.NO).asRequirementTestIn(context, null);
        final var contractKey = Key.newBuilder().contractID(contractId(CALLER)).build();
        final var delegatableKey =
                Key.newBuilder().delegatableContractId(contractId(CALLER)).build();

        assertTrue(ordinary.test(forAccount(OTHER, contractKey)));
        assertFalse(delegate.test(forAccount(OTHER, contractKey)));
        assertTrue(delegate.test(forAccount(OTHER, delegatableKey)));
    }

    @Test
    void topLevelAndEthereumSignatureEvidenceRemainsSeparateFromAccountAuthority() {
        final var context = contextWithSignatures(Set.of(AN_ED25519_KEY));
        final var allowed = scoped(CALLER, false, UseTopLevelSigs.YES).asRequirementTestIn(context, A_SECP256K1_KEY);
        final var forbidden = scoped(CALLER, false, UseTopLevelSigs.NO).asRequirementTestIn(context, A_SECP256K1_KEY);

        assertTrue(allowed.test(forAccount(OTHER, AN_ED25519_KEY)));
        assertTrue(allowed.test(forKey(A_SECP256K1_KEY)));
        assertFalse(forbidden.test(forAccount(OTHER, AN_ED25519_KEY)));
        assertFalse(forbidden.test(forKey(A_SECP256K1_KEY)));
    }

    @Test
    void allAuthorizingParentVerifierCannotWaiveStrategyRules() {
        // A dispatch's verifier may treat its (absent) signatures as all-authorizing, e.g. the no-op
        // verifier used for hook and CLPR dispatches. The strategy must still apply its own rules
        // instead of trusting that verifier to walk the key tree.
        final var context = contextWithAllAuthorizingVerifier();
        final var test = scoped(CALLER, false, UseTopLevelSigs.NO).asRequirementTestIn(context, null);

        assertTrue(test.test(forAccount(CALLER, AN_ED25519_KEY)));
        assertFalse(test.test(forAccount(OTHER, AN_ED25519_KEY)));
        assertFalse(test.test(forKey(AN_ED25519_KEY)));
        assertFalse(test.test(forAccount(
                OTHER,
                Key.newBuilder()
                        .keyList(new KeyList(List.of(AN_ED25519_KEY, A_SECP256K1_KEY)))
                        .build())));
        assertFalse(test.test(forAccount(OTHER, thresholdKey())));
    }

    @Test
    void extraLegacyActivationDoesNotAcquireAccountAuthority() {
        final var original = scoped(CALLER, false, UseTopLevelSigs.NO);
        final var legacy = scoped(OTHER, false, UseTopLevelSigs.NO);
        final var combined = withAdditionalPrimitiveStrategy(original, legacy);
        final var test = combined.asRequirementTestIn(contextWithSignatures(Set.of()), null);

        assertTrue(test.test(forAccount(CALLER, AN_ED25519_KEY)));
        assertFalse(test.test(forAccount(OTHER, AN_ED25519_KEY)));
        assertTrue(
                test.test(forKey(Key.newBuilder().contractID(contractId(OTHER)).build())));
    }

    @Test
    void sameAccountNumberInAnotherRealmOrShardHasNoExemption() {
        final var strategy = scoped(CALLER, false, UseTopLevelSigs.NO);
        assertFalse(strategy.authorizesAccount(CALLER.copyBuilder().realmNum(3).build()));
        assertFalse(strategy.authorizesAccount(CALLER.copyBuilder().shardNum(3).build()));
    }

    @Test
    void accountAuthorityMustBeCanonical() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccountAuthorizingVerificationStrategy(
                        AccountID.newBuilder().alias(ADDRESS).build(), key -> VALID));
    }

    private static Stream<Key> sharedKeys() {
        return Stream.of(
                AN_ED25519_KEY,
                Key.newBuilder()
                        .keyList(new KeyList(List.of(AN_ED25519_KEY, A_SECP256K1_KEY)))
                        .build(),
                thresholdKey(),
                Key.newBuilder().keyList(KeyList.DEFAULT).build());
    }

    private static Key thresholdKey() {
        return Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(2)
                        .keys(new KeyList(List.of(AN_ED25519_KEY, A_SECP256K1_KEY))))
                .build();
    }

    private static AccountAuthorizingVerificationStrategy scoped(
            final AccountID accountId, final boolean delegate, final UseTopLevelSigs topLevelSigs) {
        return new AccountAuthorizingVerificationStrategy(
                accountId,
                new ActiveContractVerificationStrategy(contractId(accountId), ADDRESS, delegate, topLevelSigs));
    }

    private static ContractID contractId(final AccountID accountId) {
        return ContractID.newBuilder()
                .shardNum(accountId.shardNum())
                .realmNum(accountId.realmNum())
                .contractNum(accountId.accountNumOrThrow())
                .build();
    }

    private static HandleContext contextWithSignatures(final Set<Key> signedKeys) {
        final var context = mock(HandleContext.class);
        // A deterministic key-tree evaluator lets the adapter exercise the real contextual
        // KeyVerifier default method without depending on the application implementation module.
        given(context.keyVerifier()).willReturn(new KeyVerifier() {
            @Override
            public SignatureVerification verificationFor(final Key key) {
                return new Verification(key, signedKeys.contains(key));
            }

            @Override
            public SignatureVerification verificationFor(final Key key, final VerificationAssistant callback) {
                final boolean passed;
                if (key.hasThresholdKey()) {
                    final var threshold = key.thresholdKeyOrThrow();
                    passed = threshold.keysOrThrow().keys().stream()
                                    .filter(k -> verificationFor(k, callback).passed())
                                    .count()
                            >= threshold.threshold();
                } else if (key.hasKeyList()) {
                    final var keys = key.keyListOrThrow().keys();
                    passed = !keys.isEmpty()
                            && keys.stream()
                                    .allMatch(k -> verificationFor(k, callback).passed());
                } else {
                    passed = callback.test(key, verificationFor(key));
                }
                return new Verification(key, passed);
            }
        });
        return context;
    }

    /**
     * Mimics {@code NoOpKeyVerifier}: every key passes, and the {@link VerificationAssistant} is never
     * consulted. The contextual requirement overload is left as the interface default, which routes
     * through this all-authorizing key overload, exactly as the no-op verifier does in production.
     */
    private static HandleContext contextWithAllAuthorizingVerifier() {
        final var context = mock(HandleContext.class);
        given(context.keyVerifier()).willReturn(new KeyVerifier() {
            @Override
            public SignatureVerification verificationFor(final Key key) {
                return new Verification(key, true);
            }

            @Override
            public SignatureVerification verificationFor(final Key key, final VerificationAssistant callback) {
                return new Verification(key, true);
            }
        });
        return context;
    }

    private record Verification(Key key, boolean passed) implements SignatureVerification {}
}
