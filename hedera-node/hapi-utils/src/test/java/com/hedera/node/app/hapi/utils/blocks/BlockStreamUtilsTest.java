// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.output.StateIdentifier;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockStreamUtilsTest {

    private static final List<StateIdentifier> UPGRADE_DATA_IDS = List.of(
            StateIdentifier.STATE_ID_UPGRADE_DATA_150,
            StateIdentifier.STATE_ID_UPGRADE_DATA_151,
            StateIdentifier.STATE_ID_UPGRADE_DATA_152,
            StateIdentifier.STATE_ID_UPGRADE_DATA_153,
            StateIdentifier.STATE_ID_UPGRADE_DATA_154,
            StateIdentifier.STATE_ID_UPGRADE_DATA_155,
            StateIdentifier.STATE_ID_UPGRADE_DATA_156,
            StateIdentifier.STATE_ID_UPGRADE_DATA_157,
            StateIdentifier.STATE_ID_UPGRADE_DATA_158,
            StateIdentifier.STATE_ID_UPGRADE_DATA_159);

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "fa-IR", "ar-SA", "bn-BD", "my-MM", "ne-NP", "th-TH-u-nu-thai", "tr-TR"})
    void stateNameOfUpgradeDataIsLocaleIndependent(final String languageTag) {
        final var defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));

            for (int i = 0; i < UPGRADE_DATA_IDS.size(); i++) {
                final var fileNum = 150 + i;
                assertEquals(
                        "FileService.UPGRADE_DATA_" + fileNum,
                        BlockStreamUtils.stateNameOf(UPGRADE_DATA_IDS.get(i).protoOrdinal()));
            }
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }
}
