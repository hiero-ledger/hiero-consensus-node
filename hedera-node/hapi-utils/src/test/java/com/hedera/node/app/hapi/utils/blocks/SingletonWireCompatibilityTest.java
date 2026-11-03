// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static com.hedera.hapi.block.stream.output.SingletonUpdateChange.NewValueOneOfType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.schema.SingletonUpdateChangeSchema;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class SingletonWireCompatibilityTest {
    @Test
    void everyHistoricalSingletonTagStillParsesAndSerializesIdentically() throws Exception {
        final var historicalKinds = List.of(
                BLOCK_INFO_VALUE,
                CONGESTION_LEVEL_STARTS_VALUE,
                ENTITY_NUMBER_VALUE,
                EXCHANGE_RATE_SET_VALUE,
                NETWORK_STAKING_REWARDS_VALUE,
                BYTES_VALUE,
                STRING_VALUE,
                RUNNING_HASHES_VALUE,
                THROTTLE_USAGE_SNAPSHOTS_VALUE,
                TIMESTAMP_VALUE,
                BLOCK_STREAM_INFO_VALUE,
                PLATFORM_STATE_VALUE,
                ROSTER_STATE_VALUE,
                HINTS_CONSTRUCTION_VALUE,
                ENTITY_COUNTS_VALUE,
                HISTORY_PROOF_CONSTRUCTION_VALUE,
                CRS_STATE_VALUE,
                NODE_REWARDS_VALUE,
                NODE_PAYMENTS_VALUE,
                NODE_ID_VALUE,
                CLPR_LEDGER_CONFIGURATION_VALUE,
                CLPR_ENDPOINT_MANIFEST_VALUE,
                CLPR_ENDPOINT_MANIFEST_CONSTRUCTION_VALUE);
        for (int i = 0; i < historicalKinds.size(); i++) {
            final int tag = ((i + 1) << 3) | 2;
            final var historical = Bytes.wrap(
                    tag < 128
                            ? new byte[] {(byte) tag, 0}
                            : new byte[] {(byte) ((tag & 127) | 128), (byte) (tag >>> 7), 0});
            final var decoded = SingletonUpdateChange.PROTOBUF.parse(historical);
            assertThat(decoded.newValue().kind()).isEqualTo(historicalKinds.get(i));
            assertThat(decoded.partial()).isFalse();
            assertThat(decoded.clearedFields()).isEmpty();
            assertThat(SingletonUpdateChange.PROTOBUF.toBytes(decoded)).isEqualTo(historical);
            final var type = StateChangeDeltas.singletonType(i + 1);
            final var fresh = new SingletonUpdateChange(
                    false, List.of(), new OneOf<>(historicalKinds.get(i), type.wireValue(type.defaultValue())));
            assertThat(SingletonUpdateChange.PROTOBUF.toBytes(fresh)).isEqualTo(historical);
        }
        final var entity = SingletonUpdateChange.PROTOBUF.parse(Bytes.fromHex("1a020807"));
        assertThat(entity.entityNumberValue()).isEqualTo(7L);
        assertThat(SingletonUpdateChange.PROTOBUF.toBytes(entity)).isEqualTo(Bytes.fromHex("1a020807"));
    }

    @Test
    void metadataUsesNextUnusedTags() {
        assertThat(SingletonUpdateChangeSchema.PARTIAL.number()).isEqualTo(10001);
        assertThat(SingletonUpdateChangeSchema.CLEARED_FIELDS.number()).isEqualTo(10002);
    }
}
