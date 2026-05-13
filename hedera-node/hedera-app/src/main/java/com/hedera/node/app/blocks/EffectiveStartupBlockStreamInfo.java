// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The block stream info that startup components should behave as if they had read from state.
 *
 * @param blockStreamInfo the effective startup block stream info, if one exists
 * @param previewingCutover whether this value previews block stream cutover before it is written to state
 */
public record EffectiveStartupBlockStreamInfo(@Nullable BlockStreamInfo blockStreamInfo, boolean previewingCutover) {
    public static final EffectiveStartupBlockStreamInfo NONE = new EffectiveStartupBlockStreamInfo(null, false);

    public EffectiveStartupBlockStreamInfo {
        if (previewingCutover) {
            requireNonNull(blockStreamInfo);
        }
    }

    public static @NonNull EffectiveStartupBlockStreamInfo fromPersisted(
            @Nullable final BlockStreamInfo blockStreamInfo) {
        return blockStreamInfo == null ? NONE : new EffectiveStartupBlockStreamInfo(blockStreamInfo, false);
    }

    public static @NonNull EffectiveStartupBlockStreamInfo previewingCutover(
            @NonNull final BlockStreamInfo blockStreamInfo) {
        return new EffectiveStartupBlockStreamInfo(requireNonNull(blockStreamInfo), true);
    }
}
