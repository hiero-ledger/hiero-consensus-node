// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle.ops;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.UPGRADE_ARTIFACTS_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CONFIG_TXT;

import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.lifecycle.AbstractLifecycleOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.function.UnaryOperator;

public class ReplaceConfigTxtOp extends AbstractLifecycleOp {
    private final UnaryOperator<String> newConfigTxt;

    public ReplaceConfigTxtOp(@NonNull final NodeSelector selector, @NonNull final UnaryOperator<String> newConfigTxt) {
        super(selector);
        this.newConfigTxt = Objects.requireNonNull(newConfigTxt);
    }

    @Override
    protected void run(@NonNull final HederaNode node, @NonNull final HapiSpec spec) {
        final var configTxtPath = node.getExternalPath(UPGRADE_ARTIFACTS_DIR).resolve(CONFIG_TXT);
        try {
            final var oldConfigTxt = Files.readString(configTxtPath);
            Files.writeString(configTxtPath, newConfigTxt.apply(oldConfigTxt));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
