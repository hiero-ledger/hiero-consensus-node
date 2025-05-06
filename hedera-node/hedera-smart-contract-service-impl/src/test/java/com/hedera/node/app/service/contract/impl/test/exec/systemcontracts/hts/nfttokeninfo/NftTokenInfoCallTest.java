// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.nfttokeninfo;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.ZERO_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.address_0x167.NftTokenInfoTranslator.NON_FUNGIBLE_TOKEN_INFO;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.address_0x16c.NftTokenInfoTranslator.NON_FUNGIBLE_TOKEN_INFO_16C;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CIVILIAN_OWNED_NFT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTED_FIXED_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTED_FRACTIONAL_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTED_KEYLIST_V2;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTED_ROYALTY_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTE_DEFAULT_KEYLIST;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTE_KEYLIST;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_EVERYTHING_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_EVERYTHING_TOKEN_16C;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.LEDGER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OPERATOR;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.NftTokenInfoCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.address_0x167.NftTokenInfoTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.config.data.LedgerConfig;
import com.swirlds.config.api.Configuration;
import java.util.Collections;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NftTokenInfoCallTest extends CallTestBase {
    @Mock
    private Configuration config;

    @Mock
    private LedgerConfig ledgerConfig;

    @Test
    void returnsNftTokenInfoStatusForPresentToken() {
        when(config.getConfigData(LedgerConfig.class)).thenReturn(ledgerConfig);
        final var expectedLedgerId = com.hedera.pbj.runtime.io.buffer.Bytes.fromHex(LEDGER_ID);
        when(ledgerConfig.id()).thenReturn(expectedLedgerId);
        when(nativeOperations.getNft(FUNGIBLE_EVERYTHING_TOKEN.tokenId(), 2L)).thenReturn(CIVILIAN_OWNED_NFT);

        when(nativeOperations.getAccount(CIVILIAN_OWNED_NFT.ownerIdOrThrow())).thenReturn(SOMEBODY);
        when(nativeOperations.getAccount(CIVILIAN_OWNED_NFT.spenderIdOrThrow())).thenReturn(OPERATOR);

        final var subject = new NftTokenInfoCall(
                gasCalculator,
                mockEnhancement(),
                false,
                FUNGIBLE_EVERYTHING_TOKEN,
                2L,
                config,
                NON_FUNGIBLE_TOKEN_INFO.function());

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(NftTokenInfoTranslator.NON_FUNGIBLE_TOKEN_INFO
                        .getOutputs()
                        .encode(Tuple.of(
                                SUCCESS.protoOrdinal(),
                                Tuple.of(
                                        Tuple.from(
                                                Tuple.from(
                                                        "Fungible Everything Token",
                                                        "FET",
                                                        headlongAddressOf(SENDER_ID),
                                                        "The memo",
                                                        true,
                                                        88888888L,
                                                        true,
                                                        EXPECTE_KEYLIST.toArray(new Tuple[0]),
                                                        Tuple.of(100L, headlongAddressOf(SENDER_ID), 200L)),
                                                7777777L,
                                                false,
                                                true,
                                                true,
                                                EXPECTED_FIXED_CUSTOM_FEES.toArray(new Tuple[0]),
                                                EXPECTED_FRACTIONAL_CUSTOM_FEES.toArray(new Tuple[0]),
                                                EXPECTED_ROYALTY_CUSTOM_FEES.toArray(new Tuple[0]),
                                                Bytes.wrap(expectedLedgerId.toByteArray())
                                                        .toString()),
                                        2L,
                                        headlongAddressOf(CIVILIAN_OWNED_NFT.ownerId()),
                                        1000000L,
                                        com.hedera.pbj.runtime.io.buffer.Bytes.wrap("SOLD")
                                                .toByteArray(),
                                        headlongAddressOf(CIVILIAN_OWNED_NFT.spenderId()))))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsNftTokenInfoStatusForPresentTokenV2() {
        when(config.getConfigData(LedgerConfig.class)).thenReturn(ledgerConfig);
        final var expectedLedgerId = com.hedera.pbj.runtime.io.buffer.Bytes.fromHex(LEDGER_ID);
        when(ledgerConfig.id()).thenReturn(expectedLedgerId);
        when(nativeOperations.getNft(FUNGIBLE_EVERYTHING_TOKEN_16C.tokenId(), 2L))
                .thenReturn(CIVILIAN_OWNED_NFT);

        when(nativeOperations.getAccount(CIVILIAN_OWNED_NFT.ownerIdOrThrow())).thenReturn(SOMEBODY);
        when(nativeOperations.getAccount(CIVILIAN_OWNED_NFT.spenderIdOrThrow())).thenReturn(OPERATOR);

        final var subject = new NftTokenInfoCall(
                gasCalculator,
                mockEnhancement(),
                false,
                FUNGIBLE_EVERYTHING_TOKEN_16C,
                2L,
                config,
                NON_FUNGIBLE_TOKEN_INFO_16C.function());

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(NON_FUNGIBLE_TOKEN_INFO_16C
                        .getOutputs()
                        .encode(Tuple.of(
                                SUCCESS.protoOrdinal(),
                                Tuple.of(
                                        Tuple.from(
                                                Tuple.from(
                                                        "Fungible Everything Token",
                                                        "FET",
                                                        headlongAddressOf(SENDER_ID),
                                                        "The memo",
                                                        true,
                                                        88888888L,
                                                        true,
                                                        EXPECTED_KEYLIST_V2.toArray(new Tuple[0]),
                                                        Tuple.of(100L, headlongAddressOf(SENDER_ID), 200L),
                                                        com.hedera.pbj.runtime.io.buffer.Bytes.wrap("SOLD")
                                                                .toByteArray()),
                                                7777777L,
                                                false,
                                                true,
                                                true,
                                                EXPECTED_FIXED_CUSTOM_FEES.toArray(new Tuple[0]),
                                                EXPECTED_FRACTIONAL_CUSTOM_FEES.toArray(new Tuple[0]),
                                                EXPECTED_ROYALTY_CUSTOM_FEES.toArray(new Tuple[0]),
                                                Bytes.wrap(expectedLedgerId.toByteArray())
                                                        .toString()),
                                        2L,
                                        headlongAddressOf(CIVILIAN_OWNED_NFT.ownerId()),
                                        1000000L,
                                        com.hedera.pbj.runtime.io.buffer.Bytes.wrap("SOLD")
                                                .toByteArray(),
                                        headlongAddressOf(CIVILIAN_OWNED_NFT.spenderId()))))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsNftTokenInfoStatusForMissingToken() {
        when(config.getConfigData(LedgerConfig.class)).thenReturn(ledgerConfig);
        final var expectedLedgerId = com.hedera.pbj.runtime.io.buffer.Bytes.fromHex("01");
        when(ledgerConfig.id()).thenReturn(expectedLedgerId);

        final var subject = new NftTokenInfoCall(
                gasCalculator, mockEnhancement(), false, null, 0L, config, NON_FUNGIBLE_TOKEN_INFO.function());

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(NftTokenInfoTranslator.NON_FUNGIBLE_TOKEN_INFO
                        .getOutputs()
                        .encode(Tuple.of(
                                INVALID_TOKEN_ID.protoOrdinal(),
                                Tuple.of(
                                        Tuple.from(
                                                Tuple.from(
                                                        "",
                                                        "",
                                                        headlongAddressOf(ZERO_ACCOUNT_ID),
                                                        "",
                                                        false,
                                                        0L,
                                                        false,
                                                        EXPECTE_DEFAULT_KEYLIST.toArray(new Tuple[0]),
                                                        Tuple.of(0L, headlongAddressOf(ZERO_ACCOUNT_ID), 0L)),
                                                0L,
                                                false,
                                                false,
                                                false,
                                                Collections.emptyList().toArray(new Tuple[0]),
                                                Collections.emptyList().toArray(new Tuple[0]),
                                                Collections.emptyList().toArray(new Tuple[0]),
                                                Bytes.wrap(expectedLedgerId.toByteArray())
                                                        .toString()),
                                        0L,
                                        headlongAddressOf(ZERO_ACCOUNT_ID),
                                        new Timestamp(0, 0).seconds(),
                                        com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY.toByteArray(),
                                        headlongAddressOf(ZERO_ACCOUNT_ID))))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsNftTokenInfoStatusForMissingTokenStaticCall() {
        final var subject = new NftTokenInfoCall(
                gasCalculator, mockEnhancement(), true, null, 0L, config, NON_FUNGIBLE_TOKEN_INFO.function());

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }

    @Test
    void returnsNftTokenInfoStatusForMissingTokenStaticCallV2() {
        final var subject = new NftTokenInfoCall(
                gasCalculator, mockEnhancement(), true, null, 0L, config, NON_FUNGIBLE_TOKEN_INFO_16C.function());

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }
}
