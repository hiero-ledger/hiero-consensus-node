/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokentype.address_0x167.TokenTypeTranslator;
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
public interface Hts0x167TranslatorsModule {

    @Provides
    @Singleton
    @IntoSet
    @Named("HtsTranslators")
    static CallTranslator<HtsCallAttempt> provideTokenTypeTranslator(@NonNull final TokenTypeTranslator translator) {
        return translator;
    }
}
