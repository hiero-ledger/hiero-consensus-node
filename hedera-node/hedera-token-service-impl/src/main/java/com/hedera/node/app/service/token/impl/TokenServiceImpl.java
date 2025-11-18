// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.calculator.CryptoCreateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoDeleteFeeCalculator;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V069TokenSchema;
import com.hedera.node.app.service.token.impl.systemtasks.KeyPropagationSystemTaskHandler;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.systemtasks.SystemTaskHandler;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.ZoneId;
import java.util.Set;

/** An implementation of the {@link TokenService} interface. */
public class TokenServiceImpl implements TokenService {
    public static final long THREE_MONTHS_IN_SECONDS = 7776000L;
    public static final long MAX_SERIAL_NO_ALLOWED = 0xFFFFFFFFL;
    public static final long HBARS_TO_TINYBARS = 100_000_000L;
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");

    public TokenServiceImpl(@NonNull final AppContext appContext) {
        requireNonNull(appContext);
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0490TokenSchema());
        registry.register(new V0530TokenSchema());
        registry.register(new V0610TokenSchema());
        registry.register(new V069TokenSchema());
    }

    @Override
    public Set<SystemTaskHandler> systemTaskHandlers() {
        return Set.of(new KeyPropagationSystemTaskHandler());
    }

    @Override
    public Set<ServiceFeeCalculator> serviceFeeCalculators() {
        return Set.of(new CryptoCreateFeeCalculator(), new CryptoDeleteFeeCalculator());
    }
}
