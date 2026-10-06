// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BytesKeyTest {

    @Test
    void equalContents_areEqualWithEqualHashCodes() {
        BytesKey a = new BytesKey(new byte[] {1, 2, 3});
        BytesKey b = new BytesKey(new byte[] {1, 2, 3});
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentContents_areNotEqual() {
        assertThat(new BytesKey(new byte[] {1, 2, 3})).isNotEqualTo(new BytesKey(new byte[] {1, 2, 4}));
    }

    @Test
    void worksAsMapKeyByValue() {
        Map<BytesKey, String> map = new HashMap<>();
        map.put(new BytesKey(new byte[] {9, 9}), "v");
        assertThat(map.get(new BytesKey(new byte[] {9, 9}))).isEqualTo("v");
    }

    @Test
    void defensivelyCopiesOnConstructAndAccess() {
        byte[] source = {1, 2, 3};
        BytesKey key = new BytesKey(source);

        source[0] = 9; // mutating the input must not affect the key
        assertThat(key.bytes()[0]).isEqualTo((byte) 1);

        key.bytes()[0] = 9; // mutating the accessor result must not affect the key
        assertThat(key.bytes()[0]).isEqualTo((byte) 1);
    }
}
