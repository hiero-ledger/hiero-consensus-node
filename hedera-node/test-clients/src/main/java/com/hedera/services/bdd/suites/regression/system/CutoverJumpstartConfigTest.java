// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.config.data.CutoverJumpstartConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

class CutoverJumpstartConfigTest {

    @LeakyEmbeddedHapiTest(
            reason = {NEEDS_STATE_ACCESS},
            overrides = {
                "blockStream.jumpstart.blockNum",
                "blockStream.jumpstart.previousWrappedRecordBlockHash",
                "blockStream.jumpstart.streamingHasherLeafCount",
                "blockStream.jumpstart.streamingHasherHashCount",
                "blockStream.jumpstart.streamingHasherSubtreeHashes",
            })
    Stream<DynamicTest> cutoverJumpstartConfigPropertiesAreSetInConfigProvider() {
        return hapiTest(
                overridingAllOf(Map.of(
                        "blockStream.jumpstart.blockNum", "42",
                        "blockStream.jumpstart.previousWrappedRecordBlockHash", "1234567890abcdef",
                        "blockStream.jumpstart.streamingHasherLeafCount", "7",
                        "blockStream.jumpstart.streamingHasherHashCount", "3",
                        "blockStream.jumpstart.streamingHasherSubtreeHashes", "abcdef123456,deadbeef0000")),
                doingContextual(spec -> {
                    final var config = spec.embeddedHederaOrThrow()
                            .hedera()
                            .configProvider()
                            .getConfiguration()
                            .getConfigData(CutoverJumpstartConfig.class);
                    assertEquals(42L, config.blockNum());
                    assertEquals(Bytes.fromHex("1234567890abcdef"), config.previousWrappedRecordBlockHash());
                    assertEquals(7L, config.streamingHasherLeafCount());
                    assertEquals(3, config.streamingHasherHashCount());
                    assertEquals(
                            List.of(Bytes.fromHex("abcdef123456"), Bytes.fromHex("deadbeef0000")),
                            config.streamingHasherSubtreeHashes());
                }));
    }
}
