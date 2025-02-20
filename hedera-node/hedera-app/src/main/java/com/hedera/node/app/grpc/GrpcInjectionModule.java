// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc;

import com.hedera.node.app.grpc.impl.netty.NettyGrpcServerManager;
import dagger.Binds;
import dagger.Module;

/** A Dagger module for facilities in the {@link com.hedera.node.app.info} package. */
@Module
public interface GrpcInjectionModule {
    @Binds
    GrpcServerManager provideGrpcServerManager(NettyGrpcServerManager serverManager);
}
