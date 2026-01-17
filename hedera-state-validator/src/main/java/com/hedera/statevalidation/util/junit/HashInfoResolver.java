// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util.junit;

import com.hedera.statevalidation.util.ConfigUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class HashInfoResolver implements ParameterResolver {

    public static final String FILE_NAME = "hashInfo.txt";
    private static HashInfo hashInfo;

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == HashInfo.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        if (hashInfo != null) {
            return hashInfo;
        }
        try (BufferedReader reader =
                Files.newBufferedReader(Path.of(ConfigUtils.STATE_DIR, FILE_NAME), StandardCharsets.UTF_8)) {
            hashInfo = new HashInfo(reader.lines().collect(Collectors.joining("\n")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return hashInfo;
    }
}
