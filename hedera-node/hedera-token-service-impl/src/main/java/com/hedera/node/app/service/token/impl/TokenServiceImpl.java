// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema;
import com.hedera.node.app.spi.AppContext;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.ZoneId;

/** An implementation of the {@link TokenService} interface. */
public class TokenServiceImpl implements TokenService {
    public static final long THREE_MONTHS_IN_SECONDS = 7776000L;
    public static final long MAX_SERIAL_NO_ALLOWED = 0xFFFFFFFFL;
    public static final long HBARS_TO_TINYBARS = 100_000_000L;
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");

    private final EntityIdFactory idFactory;

    public TokenServiceImpl(@NonNull final AppContext appContext) {
        requireNonNull(appContext);
        this.idFactory = appContext.idFactory();
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0490TokenSchema());
        registry.register(new V0530TokenSchema());
        registry.register(new V0610TokenSchema());
    }
}
