// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.props;

import static java.util.stream.Collectors.toMap;

import com.hedera.services.bdd.spec.HapiPropertySource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MapPropertySource implements HapiPropertySource {
    private static final Logger log = LogManager.getLogger(MapPropertySource.class);
    private static final Set<String> KEYS_TO_CENSOR = Set.of("default.payer.key", "default.payer.pemKeyPassphrase");

    public static MapPropertySource parsedFromCommaDelimited(String literal) {
        return new MapPropertySource(Stream.of(literal.split(","))
                .map(s -> List.of(s.split("=")))
                .filter(l -> l.size() == 2)
                .collect(toMap(List::getFirst, List::getLast)));
    }

    private final Map<String, String> props;

    /**
     * Whether to log the properties being initialized in a property source.
     */
    public enum Quiet {
        YES,
        NO
    }

    public MapPropertySource(@NonNull final Map<String, String> props) {
        this(props, Quiet.NO);
    }

    public MapPropertySource(@NonNull final Map<String, String> props, @NonNull final Quiet quiet) {
        final var filteredProps = props.entrySet().stream()
                .filter(entry -> !KEYS_TO_CENSOR.contains(entry.getKey()))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (quiet == Quiet.NO) {
            log.info(String.format("Initializing a MapPropertySource from %s", filteredProps));
        }
        this.props = props;
    }

    public Map<String, String> getProps() {
        return props;
    }

    @Override
    public String get(String property) {
        return props.get(property);
    }

    @Override
    public boolean has(String property) {
        return props.containsKey(property);
    }
}
