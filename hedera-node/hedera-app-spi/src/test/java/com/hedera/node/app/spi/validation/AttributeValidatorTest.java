// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import static com.hedera.node.app.spi.validation.AttributeValidator.MAX_NESTED_KEY_LEVELS;
import static com.hedera.node.app.spi.validation.AttributeValidator.isWithinNestedKeyLimit;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

// NOTE: this module's own test cannot see its testFixtures package under JPMS, so the nested-key builders are kept
// local here; the shared com.hedera.node.app.spi.fixtures.NestedKeys is used by the consuming service modules.
class AttributeValidatorTest {

    @Test
    void simpleKeyIsWithinLimit() {
        assertThat(isWithinNestedKeyLimit(simpleKey())).isTrue();
    }

    @Test
    void keyListAtSemanticDepthLimitIsAccepted() {
        // The root key counts as level 1, so MAX_NESTED_KEY_LEVELS - 1 wrappers put the leaf at the limit.
        assertThat(isWithinNestedKeyLimit(nestedKeyList(MAX_NESTED_KEY_LEVELS - 1)))
                .isTrue();
    }

    @Test
    void keyListBeyondSemanticDepthLimitIsRejected() {
        assertThat(isWithinNestedKeyLimit(nestedKeyList(MAX_NESTED_KEY_LEVELS))).isFalse();
    }

    @Test
    void thresholdKeyAtSemanticDepthLimitIsAccepted() {
        assertThat(isWithinNestedKeyLimit(nestedThresholdKey(MAX_NESTED_KEY_LEVELS - 1)))
                .isTrue();
    }

    @Test
    void thresholdKeyBeyondSemanticDepthLimitIsRejected() {
        assertThat(isWithinNestedKeyLimit(nestedThresholdKey(MAX_NESTED_KEY_LEVELS)))
                .isFalse();
    }

    private static Key simpleKey() {
        return Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
    }

    private static Key nestedKeyList(final int levels) {
        var key = simpleKey();
        for (int i = 0; i < levels; i++) {
            key = Key.newBuilder()
                    .keyList(KeyList.newBuilder().keys(key).build())
                    .build();
        }
        return key;
    }

    private static Key nestedThresholdKey(final int levels) {
        var key = simpleKey();
        for (int i = 0; i < levels; i++) {
            key = Key.newBuilder()
                    .thresholdKey(ThresholdKey.newBuilder()
                            .threshold(1)
                            .keys(KeyList.newBuilder().keys(key).build())
                            .build())
                    .build();
        }
        return key;
    }
}
