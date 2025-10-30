// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer.hooks;

import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
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
                handleContext, userTxn, List.of(new ItemizedAssessedFee(hbarAssessed, null), new ItemizedAssessedFee(htsAssessed, null)));

        final var proposed = calls.context().proposedTransfers();
        final var direct = (Tuple) proposed.get(0);
        final var custom = (Tuple) proposed.get(1);

        // Expected addresses
        final Address ownerAddr = HookCallsFactory.asHeadlongAddress(asEvmAddress(ownerId.accountNum()));
        final Address receiverAddr = HookCallsFactory.asHeadlongAddress(asEvmAddress(tokenReceiverId.accountNum()));
        final Address feeCollectorAddr = HookCallsFactory.asHeadlongAddress(asEvmAddress(feeCollectorId.accountNum()));
        final Address tokenAddr = HookCallsFactory.headlongAddressOf(fungibleTokenIDC);

        // Validate direct transfers: HBAR +/-50 and one token transfer list with +/-100
        final Tuple[] directHbar = (Tuple[]) direct.get(0);
        assertHasAccountDelta(directHbar, ownerAddr, -50);
        assertHasAccountDelta(directHbar, receiverAddr, +50);
        final Tuple[] directTokenLists = (Tuple[]) direct.get(1);
        assertThat(directTokenLists).hasSize(1);
        assertThat((Address) directTokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] directFungibleAdjusts = (Tuple[]) directTokenLists[0].get(1);
        final Tuple[] directNftAdjusts = (Tuple[]) directTokenLists[0].get(2);
        assertThat(directNftAdjusts).isEmpty();
        assertHasAccountDelta(directFungibleAdjusts, ownerAddr, -100);
        assertHasAccountDelta(directFungibleAdjusts, receiverAddr, +100);

        // Validate custom fee transfers
        final Tuple[] customHbar = (Tuple[]) custom.get(0);
        assertHasAccountDelta(customHbar, ownerAddr, -17);
        assertHasAccountDelta(customHbar, feeCollectorAddr, +17);

        final Tuple[] customTokenLists = (Tuple[]) custom.get(1);
        assertThat(customTokenLists).hasSize(1);
        assertThat((Address) customTokenLists[0].get(0)).isEqualTo(tokenAddr);
        final Tuple[] customFungibleAdjusts = (Tuple[]) customTokenLists[0].get(1);
        final Tuple[] customNftAdjusts = (Tuple[]) customTokenLists[0].get(2);
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
            final Address addr = (Address) t.get(0);
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
}

