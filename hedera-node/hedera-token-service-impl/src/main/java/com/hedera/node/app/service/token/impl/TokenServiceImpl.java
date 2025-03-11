// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.schemas.SyntheticAccountCreator;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0500TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.ZoneId;

/** An implementation of the {@link TokenService} interface. */
public class TokenServiceImpl implements TokenService {
    public static final long THREE_MONTHS_IN_SECONDS = 7776000L;
    public static final long MAX_SERIAL_NO_ALLOWED = 0xFFFFFFFFL;
    public static final long HBARS_TO_TINYBARS = 100_000_000L;
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");

    public TokenServiceImpl() {
        // No-op
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0490TokenSchema(new SyntheticAccountCreator()));
        registry.register(new V0500TokenSchema());
        registry.register(new V0530TokenSchema());
    }
}
