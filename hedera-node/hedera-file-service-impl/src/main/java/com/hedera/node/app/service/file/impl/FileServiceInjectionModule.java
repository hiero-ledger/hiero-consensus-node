// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl;

import com.hedera.node.app.service.file.FileSignatureWaivers;
import com.hedera.node.app.service.file.impl.handlers.FileSignatureWaiversImpl;
import dagger.Binds;
import dagger.Module;

@Module
public interface FileServiceInjectionModule {
    @Binds
    FileSignatureWaivers fileSignatureWaivers(FileSignatureWaiversImpl impl);
}
