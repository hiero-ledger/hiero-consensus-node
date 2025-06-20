// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

import static com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor.ONE_TO_ONE;

import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public record EntityScaleFactors(
        UtilizationScaleFactors defaultScaleFactors, Map<EntityType, UtilizationScaleFactors> typeScaleFactors) {

    private static final Logger log = LogManager.getLogger(EntityScaleFactors.class);

    private static final String DEFAULT_TYPE = "DEFAULT";
    private static final Pattern SCOPED_FACTOR_PATTERN = Pattern.compile("(.*?)\\((.*?)\\),?");
    private static final UtilizationScaleFactors NOOP_SCALE_FACTORS =
            new UtilizationScaleFactors(new int[] {0}, new ScaleFactor[] {ONE_TO_ONE});

    public static EntityScaleFactors from(final String csv) {
        try {
            return parseScaleFactorsByEntity(csv);
        } catch (Exception any) {
            log.warn("Unable to parse '{}' as an entity scale factors spec, using 1:1 everywhere", csv, any);
            return new EntityScaleFactors(NOOP_SCALE_FACTORS, new EnumMap<>(EntityType.class));
        }
    }

    private static EntityScaleFactors parseScaleFactorsByEntity(final String csv) {
        UtilizationScaleFactors defaultScaleFactors = NOOP_SCALE_FACTORS;
        final Map<EntityType, UtilizationScaleFactors> typeScaleFactors = new EnumMap<>(EntityType.class);
        final var matcher = SCOPED_FACTOR_PATTERN.matcher(csv);
        while (matcher.find()) {
            final var entityType = matcher.group(1);
            final var scaleFactors = UtilizationScaleFactors.from(matcher.group(2));
            if (DEFAULT_TYPE.equals(entityType)) {
                defaultScaleFactors = scaleFactors;
            } else {
                typeScaleFactors.put(EntityType.valueOf(entityType), scaleFactors);
            }
        }
        return new EntityScaleFactors(defaultScaleFactors, typeScaleFactors);
    }

    public ScaleFactor scaleForNew(final EntityType type, final int utilPercent) {
        var choice = ONE_TO_ONE;
        final var choicesForEntity = typeScaleFactors.getOrDefault(type, defaultScaleFactors);
        final var triggers = choicesForEntity.usagePercentTriggers();
        for (var i = 0; i < triggers.length && utilPercent >= triggers[i]; i++) {
            choice = choicesForEntity.scaleFactors()[i];
        }
        return choice;
    }
}
