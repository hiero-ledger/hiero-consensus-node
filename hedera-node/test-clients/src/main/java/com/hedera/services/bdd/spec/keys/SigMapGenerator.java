// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

import static java.util.Map.Entry;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.SignatureMap;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * A generator of {@link SignatureMap} instances whose public key prefixes have a given
 * nature. Generally that nature is that each prefix is be the shortest unique prefix to
 * identify its corresponding public key among all that sign; though in some cases we
 * must always include the full prefix. (E.g., to trigger a hollow account completion.)
 * In case of {@link Nature#FULL_PREFIXES} the prefixes are the full public keys.
 *
 * <p>For negative testing there are also natures like {@link Nature#AMBIGUOUS_PREFIXES}
 * and {@link Nature#CONFUSED_PREFIXES} which may construct invalid maps.
 */
public interface SigMapGenerator {
    enum Nature {
        UNIQUE_PREFIXES,
        AMBIGUOUS_PREFIXES,
        CONFUSED_PREFIXES,
        UNIQUE_WITH_SOME_FULL_PREFIXES,
        FULL_PREFIXES,
    }

    SignatureMap forPrimitiveSigs(@Nullable HapiSpec spec, List<Entry<byte[], byte[]>> keySigs);
}
