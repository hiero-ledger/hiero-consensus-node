// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.getevmaddressalias.EvmAddressAliasTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hederaaccountnumalias.HederaAccountNumAliasTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized.IsAuthorizedTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias.IsValidAliasTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations.SetUnlimitedAutoAssociationsTranslator;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Provides the {@link CallTranslator} implementations for the HAS system contract.
 */
@Module
public interface HasTranslatorsModule {
    @Provides
    @Singleton
    @Named("HasTranslators")
    static List<CallTranslator<HasCallAttempt>> provideCallAttemptTranslators(
            @NonNull @Named("HasTranslators") final Set<CallTranslator<HasCallAttempt>> translators) {
        return List.copyOf(translators);
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HasTranslators")
    static CallTranslator<HasCallAttempt> provideHbarAllowanceTranslator(
            @NonNull final HbarAllowanceTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HasTranslators")
    static CallTranslator<HasCallAttempt> provideHbarApproveTranslator(
            @NonNull final HbarApproveTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HasTranslators")
    static CallTranslator<HasCallAttempt> provideEvmAddressAliasTranslator(
            @NonNull final EvmAddressAliasTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HasTranslators")
    static CallTranslator<HasCallAttempt> provideHederaAccountNumAliasTranslator(
            @NonNull final HederaAccountNumAliasTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HasTranslators")
    static CallTranslator<HasCallAttempt> provideIsValidAliasTranslator(
            @NonNull final IsValidAliasTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HasTranslators")
    static CallTranslator<HasCallAttempt> provideIsAuthorizedRawTranslator(
            @NonNull final IsAuthorizedRawTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HasTranslators")
    static CallTranslator<HasCallAttempt> provideIsAuthorizedTranslator(
            @NonNull final IsAuthorizedTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HasTranslators")
    static CallTranslator<HasCallAttempt> provideSetUnlimitedAutoAssociationsTranslator(
            @NonNull final SetUnlimitedAutoAssociationsTranslator translator) {
        return translator;
    }
}
