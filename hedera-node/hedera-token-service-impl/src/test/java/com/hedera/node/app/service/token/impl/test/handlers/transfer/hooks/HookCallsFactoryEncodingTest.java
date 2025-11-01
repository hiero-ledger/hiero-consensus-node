// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer.hooks;

import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFixedFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFractionalFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.ItemizedAssessedFee;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookCalls;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookCallsFactory;
import com.hedera.node.app.service.token.impl.test.handlers.transfer.StepsBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates ProposedTransfers encoding (direct and customFee) for a token transfer that
 * triggers custom fees. Ensures ABI tuples match IHieroAccountAllowanceHook definitions.
 */
public class HookCallsFactoryEncodingTest extends StepsBase {

    @Test
    void encodesDirectAndCustomFeesCorrectly() {
        // Direct transfer: owner -> tokenReceiver of fungibleTokenIDC
        final var directTtl = TokenTransferList.newBuilder()
                .token(fungibleTokenIDC)
                .transfers(List.of(aaWith(ownerId, -100), aaWith(tokenReceiverId, +100)))
                .build();
        final var directHbarTransfer = TransferList.newBuilder()
                .accountAmounts(List.of(aaWith(ownerId, -50L), aaWith(tokenReceiverId, +50L)))
                .build();
        final var userTxn = CryptoTransferTransactionBody.newBuilder()
                .transfers(directHbarTransfer)
                .tokenTransfers(List.of(directTtl))
                .build();
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        givenTxn(userTxn, payerId);

        // Assessed custom fees: 17 tinybar (HBAR) to feeCollector from owner; and 40 units of fungibleTokenIDC
        final var hbarAssessed = AssessedCustomFee.newBuilder()
                .amount(17L)
                .feeCollectorAccountId(feeCollectorId)
                .effectivePayerAccountId(List.of(ownerId))
                .build();
        final var htsAssessed = AssessedCustomFee.newBuilder()
                .amount(40L)
                .tokenId(fungibleTokenIDC)
                .feeCollectorAccountId(feeCollectorId)
                .effectivePayerAccountId(List.of(ownerId))
                .build();

        final var factory = new HookCallsFactory();
        final HookCalls calls = factory.from(
                handleContext,
                userTxn,
                List.of(new ItemizedAssessedFee(hbarAssessed, null), new ItemizedAssessedFee(htsAssessed, null)));

        final var proposed = calls.context().proposedTransfers();
        final var direct = (Tuple) proposed.get(0);
        final var custom = (Tuple) proposed.get(1);

        // Expected addresses
        final Address ownerAddr = HookCallsFactory.asHeadlongAddress(asEvmAddress(ownerId.accountNum()));
        final Address receiverAddr = HookCallsFactory.asHeadlongAddress(asEvmAddress(tokenReceiverId.accountNum()));
        final Address feeCollectorAddr = HookCallsFactory.asHeadlongAddress(asEvmAddress(feeCollectorId.accountNum()));
        final Address tokenAddr = HookCallsFactory.headlongAddressOf(fungibleTokenIDC);

        // Validate direct transfers: HBAR +/-50 and one token transfer list with +/-100
        final Tuple[] directHbar = direct.get(0);
        assertHasAccountDelta(directHbar, ownerAddr, -50);
        assertHasAccountDelta(directHbar, receiverAddr, +50);
        final Tuple[] directTokenLists = direct.get(1);
        assertThat(directTokenLists).hasSize(1);
        assertThat((Address) directTokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] directFungibleAdjusts = directTokenLists[0].get(1);
        final Tuple[] directNftAdjusts = directTokenLists[0].get(2);
        assertThat(directNftAdjusts).isEmpty();
        assertHasAccountDelta(directFungibleAdjusts, ownerAddr, -100);
        assertHasAccountDelta(directFungibleAdjusts, receiverAddr, +100);

        // Validate custom fee transfers
        final Tuple[] customHbar = custom.get(0);
        assertHasAccountDelta(customHbar, ownerAddr, -17);
        assertHasAccountDelta(customHbar, feeCollectorAddr, +17);

        final Tuple[] customTokenLists = custom.get(1);
        assertThat(customTokenLists).hasSize(1);
        assertThat((Address) customTokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] customFungibleAdjusts = customTokenLists[0].get(1);
        final Tuple[] customNftAdjusts = customTokenLists[0].get(2);
        assertThat(customNftAdjusts).isEmpty();
        assertHasAccountDelta(customFungibleAdjusts, ownerAddr, -40);
        assertHasAccountDelta(customFungibleAdjusts, feeCollectorAddr, +40);

        // No pre-only or pre-post hooks gathered from this encoding path
        assertThat(calls.preOnlyHooks()).isEmpty();
        assertThat(calls.prePostHooks()).isEmpty();
    }

    private static void assertHasAccountDelta(final Tuple[] adjustments, final Address expected, final long amount) {
        boolean found = false;
        for (final Tuple t : adjustments) {
            final Address addr = t.get(0);
            final long amt = ((Number) t.get(1)).longValue();
            if (addr.equals(expected) && amt == amount) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .withFailMessage("Expected delta %s for %s not found", amount, expected)
                .isTrue();
    }

    @Test
    void encodesMultiPayerFractionalFeeWithReclaimSplitsAcrossReceivers() {
        // Direct token transfer: owner -> tokenReceiver (+70), owner -> hbarReceiver (+30)
        final var directTtl = TokenTransferList.newBuilder()
                .token(fungibleTokenId)
                .transfers(List.of(aaWith(ownerId, -100), aaWith(tokenReceiverId, +70), aaWith(hbarReceiverId, +30)))
                .build();
        final var userTxn = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(List.of(directTtl))
                .build();

        // Provide stores to the factory under test
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        givenTxn(userTxn, payerId);

        // Build a token definition that charges a 10% non-net-of-transfers fractional fee
        final var tenPercent = FractionalFee.newBuilder()
                .fractionalAmount(Fraction.newBuilder().numerator(1).denominator(10))
                .minimumAmount(0)
                .maximumAmount(0)
                .netOfTransfers(false)
                .build();
        final var tokenWithFractional = fungibleToken
                .copyBuilder()
                .customFees(List.of(withFractionalFee(tenPercent, feeCollectorId, false)))
                .build();

        // Use the real assessor to exercise reclaim() splitting logic across multiple receivers
        final var assessor = new CustomFractionalFeeAssessor(new CustomFixedFeeAssessor());
        final var result = new AssessmentResult(userTxn.tokenTransfers(), List.of());
        assessor.assessFractionalFees(tokenWithFractional, ownerId, result);

        // Now encode ProposedTransfers from the user txn and the itemized assessed fees (with multi-payer deltas)
        final var factory = new HookCallsFactory();
        final HookCalls calls = factory.from(handleContext, userTxn, result.getItemizedAssessedFees());

        final var proposed = calls.context().proposedTransfers();
        final var direct = (Tuple) proposed.get(0);
        final var custom = (Tuple) proposed.get(1);

        // Expected addresses
        final Address ownerAddr = HookCallsFactory.asHeadlongAddress(asEvmAddress(ownerId.accountNum()));
        final Address receiver1Addr = HookCallsFactory.asHeadlongAddress(asEvmAddress(tokenReceiverId.accountNum()));
        final Address receiver2Addr = HookCallsFactory.asHeadlongAddress(asEvmAddress(hbarReceiverId.accountNum()));
        final Address feeCollectorAddr = HookCallsFactory.asHeadlongAddress(asEvmAddress(feeCollectorId.accountNum()));
        final Address tokenAddr = HookCallsFactory.headlongAddressOf(fungibleTokenId);

        // Validate direct: one token list with -100 (owner), +70 (receiver1), +30 (receiver2); no HBAR entries
        final Tuple[] directHbar = direct.get(0);
        assertThat(directHbar).isEmpty();
        final Tuple[] directTokenLists = direct.get(1);
        assertThat(directTokenLists).hasSize(1);
        assertThat((Address) directTokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] directFungibleAdjusts = directTokenLists[0].get(1);
        final Tuple[] directNftAdjusts = directTokenLists[0].get(2);
        assertThat(directNftAdjusts).isEmpty();
        assertHasAccountDelta(directFungibleAdjusts, ownerAddr, -100);
        assertHasAccountDelta(directFungibleAdjusts, receiver1Addr, +70);
        assertHasAccountDelta(directFungibleAdjusts, receiver2Addr, +30);

        // Validate custom fee (fractional 10% of 100 = 10): split proportionally 7 and 3; credited to collector
        final Tuple[] customHbar = custom.get(0);
        assertThat(customHbar).isEmpty();
        final Tuple[] customTokenLists = custom.get(1);
        assertThat(customTokenLists).hasSize(1);
        assertThat((Address) customTokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] customFungibleAdjusts = customTokenLists[0].get(1);
        final Tuple[] customNftAdjusts = customTokenLists[0].get(2);
        assertThat(customNftAdjusts).isEmpty();
        assertHasAccountDelta(customFungibleAdjusts, receiver1Addr, -7);
        assertHasAccountDelta(customFungibleAdjusts, receiver2Addr, -3);
        assertHasAccountDelta(customFungibleAdjusts, feeCollectorAddr, +10);

        // No pre-only or pre-post hooks included
        assertThat(calls.preOnlyHooks()).isEmpty();
        assertThat(calls.prePostHooks()).isEmpty();
    }

    @Test
    void encodesRemainderDistributionWhenAllFloorsZero_firstReceiverPaysOne() {
        // owner -100, receivers +34,+33,+33; fee 1% => assessed 1 -> remainder distributed to first
        final var ttl = TokenTransferList.newBuilder()
                .token(fungibleTokenId)
                .transfers(List.of(
                        aaWith(ownerId, -100),
                        aaWith(tokenReceiverId, +34),
                        aaWith(hbarReceiverId, +33),
                        aaWith(spenderId, +33)))
                .build();
        final var userTxn = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(List.of(ttl))
                .build();
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        givenTxn(userTxn, payerId);

        final var onePercent = FractionalFee.newBuilder()
                .fractionalAmount(Fraction.newBuilder().numerator(1).denominator(100))
                .minimumAmount(0)
                .maximumAmount(0)
                .netOfTransfers(false)
                .build();
        final var tokenWithFee = fungibleToken
                .copyBuilder()
                .customFees(List.of(withFractionalFee(onePercent, feeCollectorId, false)))
                .build();

        final var assessor = new CustomFractionalFeeAssessor(new CustomFixedFeeAssessor());
        final var result = new AssessmentResult(userTxn.tokenTransfers(), List.of());
        assessor.assessFractionalFees(tokenWithFee, ownerId, result);

        final var calls = new HookCallsFactory().from(handleContext, userTxn, result.getItemizedAssessedFees());
        final var custom = (Tuple) calls.context().proposedTransfers().get(1);

        final Address r1 = HookCallsFactory.asHeadlongAddress(asEvmAddress(tokenReceiverId.accountNum()));
        final Address r2 = HookCallsFactory.asHeadlongAddress(asEvmAddress(hbarReceiverId.accountNum()));
        final Address r3 = HookCallsFactory.asHeadlongAddress(asEvmAddress(spenderId.accountNum()));
        final Address collector = HookCallsFactory.asHeadlongAddress(asEvmAddress(feeCollectorId.accountNum()));
        final Address tokenAddr = HookCallsFactory.headlongAddressOf(fungibleTokenId);

        final Tuple[] customHbar = custom.get(0);
        assertThat(customHbar).isEmpty();
        final Tuple[] tokenLists = custom.get(1);
        assertThat(tokenLists).hasSize(1);
        assertThat((Address) tokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] f = tokenLists[0].get(1);
        assertHasAccountDelta(f, r1, -1);
        assertHasAccountDelta(f, collector, +1);
    }

    @Test
    void encodesRemainderDistributionWhenSomeHaveNonZeroAndOthersZero() {
        // owner -1000, receivers +1,+999; fee 1% => assessed 10 -> floors 0 and 9, remainder 1 to first
        final var ttl = TokenTransferList.newBuilder()
                .token(fungibleTokenId)
                .transfers(List.of(aaWith(ownerId, -1000), aaWith(tokenReceiverId, +1), aaWith(hbarReceiverId, +999)))
                .build();
        final var userTxn = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(List.of(ttl))
                .build();
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        givenTxn(userTxn, payerId);

        final var onePercent = FractionalFee.newBuilder()
                .fractionalAmount(Fraction.newBuilder().numerator(1).denominator(100))
                .minimumAmount(0)
                .maximumAmount(0)
                .netOfTransfers(false)
                .build();
        final var tokenWithFee = fungibleToken
                .copyBuilder()
                .customFees(List.of(withFractionalFee(onePercent, feeCollectorId, false)))
                .build();

        final var assessor = new CustomFractionalFeeAssessor(new CustomFixedFeeAssessor());
        final var result = new AssessmentResult(userTxn.tokenTransfers(), List.of());
        assessor.assessFractionalFees(tokenWithFee, ownerId, result);

        final var calls = new HookCallsFactory().from(handleContext, userTxn, result.getItemizedAssessedFees());
        final var custom = (Tuple) calls.context().proposedTransfers().get(1);

        final Address r1 = HookCallsFactory.asHeadlongAddress(asEvmAddress(tokenReceiverId.accountNum()));
        final Address r2 = HookCallsFactory.asHeadlongAddress(asEvmAddress(hbarReceiverId.accountNum()));
        final Address collector = HookCallsFactory.asHeadlongAddress(asEvmAddress(feeCollectorId.accountNum()));
        final Address tokenAddr = HookCallsFactory.headlongAddressOf(fungibleTokenId);

        final Tuple[] customHbar = custom.get(0);
        assertThat(customHbar).isEmpty();
        final Tuple[] tokenLists = custom.get(1);
        assertThat(tokenLists).hasSize(1);
        assertThat((Address) tokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] f = tokenLists[0].get(1);
        assertHasAccountDelta(f, r1, -1);
        assertHasAccountDelta(f, r2, -9);
        assertHasAccountDelta(f, collector, +10);
    }

    @Test
    void encodesMultipleNonNetFractionalFeesAggregatedAcrossCollectors() {
        // owner -100, receivers +70,+30; two 10% fees -> total 20, split -14/-6 to payers; +10 to each collector
        final var ttl = TokenTransferList.newBuilder()
                .token(fungibleTokenId)
                .transfers(List.of(aaWith(ownerId, -100), aaWith(tokenReceiverId, +70), aaWith(hbarReceiverId, +30)))
                .build();
        final var userTxn = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(List.of(ttl))
                .build();
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        givenTxn(userTxn, payerId);

        final var tenPercent = FractionalFee.newBuilder()
                .fractionalAmount(Fraction.newBuilder().numerator(1).denominator(10))
                .minimumAmount(0)
                .maximumAmount(0)
                .netOfTransfers(false)
                .build();
        final var tokenWithTwoFees = fungibleToken
                .copyBuilder()
                .customFees(List.of(
                        withFractionalFee(tenPercent, feeCollectorId, false),
                        withFractionalFee(tenPercent, spenderId, false)))
                .build();

        final var assessor = new CustomFractionalFeeAssessor(new CustomFixedFeeAssessor());
        final var result = new AssessmentResult(userTxn.tokenTransfers(), List.of());
        assessor.assessFractionalFees(tokenWithTwoFees, ownerId, result);

        final var calls = new HookCallsFactory().from(handleContext, userTxn, result.getItemizedAssessedFees());
        final var custom = (Tuple) calls.context().proposedTransfers().get(1);

        final Address r1 = HookCallsFactory.asHeadlongAddress(asEvmAddress(tokenReceiverId.accountNum()));
        final Address r2 = HookCallsFactory.asHeadlongAddress(asEvmAddress(hbarReceiverId.accountNum()));
        final Address c1 = HookCallsFactory.asHeadlongAddress(asEvmAddress(feeCollectorId.accountNum()));
        final Address c2 = HookCallsFactory.asHeadlongAddress(asEvmAddress(spenderId.accountNum()));
        final Address tokenAddr = HookCallsFactory.headlongAddressOf(fungibleTokenId);

        final Tuple[] customHbar = custom.get(0);
        assertThat(customHbar).isEmpty();
        final Tuple[] tokenLists = custom.get(1);
        assertThat(tokenLists).hasSize(1);
        assertThat((Address) tokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] f = tokenLists[0].get(1);
        assertHasAccountDelta(f, r1, -14);
        assertHasAccountDelta(f, r2, -5);
        assertHasAccountDelta(f, c1, +9);
        assertHasAccountDelta(f, c2, +10);
    }

    @Test
    void encodesNoCustomFeesWhenFractionRoundsToZero() {
        // owner -3, receiver +3; fee 1% => assessed 0 -> no itemized fee, custom transfers empty
        final var ttl = TokenTransferList.newBuilder()
                .token(fungibleTokenId)
                .transfers(List.of(aaWith(ownerId, -3), aaWith(tokenReceiverId, +3)))
                .build();
        final var userTxn = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(List.of(ttl))
                .build();
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        givenTxn(userTxn, payerId);

        final var onePercent = FractionalFee.newBuilder()
                .fractionalAmount(Fraction.newBuilder().numerator(1).denominator(100))
                .minimumAmount(0)
                .maximumAmount(0)
                .netOfTransfers(false)
                .build();
        final var tokenWithFee = fungibleToken
                .copyBuilder()
                .customFees(List.of(withFractionalFee(onePercent, feeCollectorId, false)))
                .build();

        final var assessor = new CustomFractionalFeeAssessor(new CustomFixedFeeAssessor());
        final var result = new AssessmentResult(userTxn.tokenTransfers(), List.of());
        assessor.assessFractionalFees(tokenWithFee, ownerId, result);

        // No itemized assessed fees should be added (rounded down to zero)
        final var calls = new HookCallsFactory().from(handleContext, userTxn, result.getItemizedAssessedFees());
        final var custom = (Tuple) calls.context().proposedTransfers().get(1);
        final Tuple[] customHbar = custom.get(0);
        final Tuple[] tokenLists = custom.get(1);
        assertThat(customHbar).isEmpty();
        assertThat(tokenLists).isEmpty();
    }

    @Test
    void encodesNetOfTransfersTrue_senderPaysFullFraction() {
        // owner -100, receivers +70,+30; fee 10% net-of-transfers => owner pays 10, collector +10
        final var ttl = TokenTransferList.newBuilder()
                .token(fungibleTokenId)
                .transfers(List.of(aaWith(ownerId, -100), aaWith(tokenReceiverId, +70), aaWith(hbarReceiverId, +30)))
                .build();
        final var userTxn = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(List.of(ttl))
                .build();
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        givenTxn(userTxn, payerId);

        final var tenPercentNet = FractionalFee.newBuilder()
                .fractionalAmount(Fraction.newBuilder().numerator(1).denominator(10))
                .minimumAmount(0)
                .maximumAmount(0)
                .netOfTransfers(true)
                .build();
        final var tokenWithFee = fungibleToken
                .copyBuilder()
                .customFees(List.of(withFractionalFee(tenPercentNet, feeCollectorId, false)))
                .build();

        final var assessor = new CustomFractionalFeeAssessor(new CustomFixedFeeAssessor());
        final var result = new AssessmentResult(userTxn.tokenTransfers(), List.of());
        assessor.assessFractionalFees(tokenWithFee, ownerId, result);

        final var calls = new HookCallsFactory().from(handleContext, userTxn, result.getItemizedAssessedFees());
        final var proposed = calls.context().proposedTransfers();
        final var direct = (Tuple) proposed.get(0);
        final var custom = (Tuple) proposed.get(1);

        final Address ownerAddr = HookCallsFactory.asHeadlongAddress(asEvmAddress(ownerId.accountNum()));
        final Address r1 = HookCallsFactory.asHeadlongAddress(asEvmAddress(tokenReceiverId.accountNum()));
        final Address r2 = HookCallsFactory.asHeadlongAddress(asEvmAddress(hbarReceiverId.accountNum()));
        final Address collector = HookCallsFactory.asHeadlongAddress(asEvmAddress(feeCollectorId.accountNum()));
        final Address tokenAddr = HookCallsFactory.headlongAddressOf(fungibleTokenId);

        // Direct remains unchanged
        final Tuple[] directHbar = direct.get(0);
        assertThat(directHbar).isEmpty();
        final Tuple[] directTokenLists = direct.get(1);
        final Tuple[] directFungibleAdjusts = directTokenLists[0].get(1);
        assertHasAccountDelta(directFungibleAdjusts, ownerAddr, -100);
        assertHasAccountDelta(directFungibleAdjusts, r1, +70);
        assertHasAccountDelta(directFungibleAdjusts, r2, +30);

        // Custom fee: owner pays -10; collector +10
        final Tuple[] customHbar = custom.get(0);
        assertThat(customHbar).isEmpty();
        final Tuple[] tokenLists = custom.get(1);
        assertThat(tokenLists).hasSize(1);
        assertThat((Address) tokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] f = tokenLists[0].get(1);
        assertHasAccountDelta(f, ownerAddr, -10);
        assertHasAccountDelta(f, collector, +10);
    }
}
