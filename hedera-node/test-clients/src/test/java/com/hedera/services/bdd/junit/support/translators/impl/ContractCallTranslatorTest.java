// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.services.bdd.junit.support.translators.impl.ContractCallTranslator.isClprDispatch;
import static com.hedera.services.bdd.junit.support.translators.impl.ContractCallTranslator.reached;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContractCallTranslator}.
 */
class ContractCallTranslatorTest {
    private static final ContractID APPLICATION =
            ContractID.newBuilder().contractNum(1001L).build();
    private static final ContractID HTS =
            ContractID.newBuilder().contractNum(0x167L).build();
    private static final Bytes APPLICATION_ALIAS = Bytes.fromHex("c0ffee00000000000000000000000000000000c1");
    private static final Map<Bytes, AccountID> ALIASES =
            Map.of(APPLICATION_ALIAS, AccountID.newBuilder().accountNum(1001L).build());

    @Test
    @DisplayName("A call a CLPR transaction dispatched carries no sender, so it is a CLPR dispatch")
    void dispatchedCallIsClprDispatch() {
        final var dispatched =
                EvmTransactionResult.newBuilder().contractId(APPLICATION).build();

        assertTrue(isClprDispatch(true, dispatched));
    }

    @Test
    @DisplayName("An HTS view called by a CLPR callback carries its caller as sender, so it is not a CLPR dispatch")
    void systemContractChildOfCallbackIsNotClprDispatch() {
        // E.g. the application callback calling HTS isToken(): a synthetic child CONTRACT_CALL whose result names
        // the calling contract as sender; its receipt must not get the dispatched call's contract id
        final var htsView = EvmTransactionResult.newBuilder()
                .contractId(HTS)
                .senderId(AccountID.newBuilder().accountNum(1001L).build())
                .build();

        assertFalse(isClprDispatch(true, htsView));
    }

    @Test
    @DisplayName("Outside a unit with a CLPR transaction, no child call is a CLPR dispatch")
    void noClprTransactionNoDispatch() {
        final var call =
                EvmTransactionResult.newBuilder().contractId(APPLICATION).build();

        assertFalse(isClprDispatch(false, call));
    }

    @Test
    @DisplayName("A callback addressed to the numeric id of its application reached it")
    void numericTargetReached() {
        assertTrue(reached(APPLICATION, APPLICATION, ALIASES));
        assertFalse(reached(APPLICATION, HTS, ALIASES));
    }

    @Test
    @DisplayName("A callback addressed to the long-zero address of its application reached it")
    void longZeroTargetReached() {
        final var target = ContractID.newBuilder()
                .evmAddress(Bytes.wrap(asEvmAddress(1001L)))
                .build();

        assertTrue(reached(target, APPLICATION, ALIASES));
        assertFalse(reached(target, HTS, ALIASES));
    }

    @Test
    @DisplayName("A callback addressed to the EVM alias of its application reached it")
    void aliasedTargetReached() {
        // CLPR callbacks address the application by the EVM address in the message, e.g. a CREATE2 address,
        // while the result carries the resolved numeric id
        final var target = ContractID.newBuilder().evmAddress(APPLICATION_ALIAS).build();

        assertTrue(reached(target, APPLICATION, ALIASES));
        assertFalse(reached(target, HTS, ALIASES));
    }

    @Test
    @DisplayName("A callback addressed to an unknown alias did not reach the contract its result reports")
    void unknownAliasNotReached() {
        final var target = ContractID.newBuilder().evmAddress(APPLICATION_ALIAS).build();

        assertFalse(reached(target, APPLICATION, Map.of()));
    }
}
