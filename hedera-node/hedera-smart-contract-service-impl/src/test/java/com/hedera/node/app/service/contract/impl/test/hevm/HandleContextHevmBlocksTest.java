// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmBlocks.UNAVAILABLE_BLOCK_HASH;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETERNAL_NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.service.contract.impl.hevm.HandleContextHevmBlocks;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleContextHevmBlocksTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private HandleContext context;

    @Mock
    private BlockRecordInfo blockRecordInfo;

    private HandleContextHevmBlocks subject;

    @BeforeEach
    void setUp() {
        subject = new HandleContextHevmBlocks(context);
        given(context.blockRecordInfo()).willReturn(blockRecordInfo);
    }

    @Test
    void blockHashDelegates() {
        given(blockRecordInfo.blockHashByBlockNumber(123L)).willReturn(ConversionUtils.tuweniToPbjBytes(Hash.EMPTY));
        assertEquals(Hash.EMPTY, subject.blockHashOf(frame, 123L));
    }

    @Test
    void returnsUnavailableHashIfNecessary() {
        assertSame(UNAVAILABLE_BLOCK_HASH, subject.blockHashOf(frame, 123L));
    }

    @Test
    void blockValuesHasExpectedValues() {
        final var now = new Timestamp(1_234_567L, 890);
        given(blockRecordInfo.blockNo()).willReturn(123L);
        given(blockRecordInfo.blockTimestamp()).willReturn(now);
        given(context.blockRecordInfo()).willReturn(blockRecordInfo);

        final var blockValues = subject.blockValuesOf(456L);

        assertEquals(456L, blockValues.getGasLimit());
        assertEquals(123L, blockValues.getNumber());
        assertEquals(ETERNAL_NOW.getEpochSecond(), blockValues.getTimestamp());
        assertEquals(Optional.of(Wei.ZERO), blockValues.getBaseFee());
        assertSame(Bytes.EMPTY, blockValues.getDifficultyBytes());
    }
}
