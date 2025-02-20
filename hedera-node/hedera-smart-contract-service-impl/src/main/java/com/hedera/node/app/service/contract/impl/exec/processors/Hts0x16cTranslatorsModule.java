// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokentype.address_0x16c.TokenTypeTranslator;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Provides the {@link CallTranslator} implementations for the HTS system contract at address 0x16c.
 */
@Module
public interface Hts0x16cTranslatorsModule {

    @Provides
    @Singleton
    @IntoSet
    @Named("HtsTranslators")
    static CallTranslator<HtsCallAttempt> provideTokenTypeTranslator(@NonNull final TokenTypeTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HtsTranslators")
    static CallTranslator<HtsCallAttempt> provideCreateTranslator(@NonNull final CreateTranslator translator) {
        return translator;
    }
}
