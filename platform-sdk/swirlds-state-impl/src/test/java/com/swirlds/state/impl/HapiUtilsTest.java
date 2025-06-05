// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.impl;

import static com.swirlds.state.lifecycle.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.VirtualMapKey;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class HapiUtilsTest {

    @Test
    void parseB() throws ParseException {
        VirtualMapKey key = VirtualMapKey.PROTOBUF.parse(Bytes.fromHex("f007c301"));
        System.out.println(key);
    }

    @Test
    void nonAlphaPreReleasePartsComeAfterAnyAlpha() {
        final var alphaVersion = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("alpha.4")
                .build();
        final var nonAlphaVersion = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("abcdefg")
                .build();
        assertThat(SEMANTIC_VERSION_COMPARATOR.compare(alphaVersion, nonAlphaVersion))
                .isLessThan(0);
    }

    @Test
    void nonNumericBuildPartsHaveNoEffectOnOrdering() {
        final var abcBuild = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("")
                .build("abc")
                .build();
        final var defBuild = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("")
                .build("def")
                .build();
        assertThat(SEMANTIC_VERSION_COMPARATOR.compare(abcBuild, defBuild)).isEqualTo(0);
    }

    @Test
    void numericBuildPartsHaveEffectOnOrdering() {
        final var zeroBuild = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("")
                .build("0")
                .build();
        final var oneBuild = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("")
                .build("1")
                .build();
        assertThat(SEMANTIC_VERSION_COMPARATOR.compare(zeroBuild, oneBuild)).isLessThan(0);
    }
}
