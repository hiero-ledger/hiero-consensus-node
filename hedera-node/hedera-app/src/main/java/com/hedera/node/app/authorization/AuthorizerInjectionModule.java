// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.authorization;

import com.hedera.node.app.spi.authorization.Authorizer;
import dagger.Binds;
import dagger.Module;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

/** A Dagger module for providing dependencies based on {@link Authorizer}. */
@Module
public interface AuthorizerInjectionModule {
    @Binds
    @Singleton
    Authorizer provideAuthorizer(@NonNull final AuthorizerImpl impl);
}
