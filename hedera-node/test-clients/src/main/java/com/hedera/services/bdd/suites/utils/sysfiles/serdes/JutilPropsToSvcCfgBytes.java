// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Setting;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.StringReader;
import java.util.Comparator;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class JutilPropsToSvcCfgBytes implements SysFileSerde<String> {
    final String fileName;

    public JutilPropsToSvcCfgBytes(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public String fromRawFile(byte[] bytes) {
        try {
            var proto = ServicesConfigurationList.parseFrom(bytes);
            return proto.getNameValueList().stream()
                    .map(setting -> String.format("%s=%s", setting.getName(), setting.getValue()))
                    .sorted(LEGACY_THROTTLES_FIRST_ORDER)
                    .collect(Collectors.joining("\n"));
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Content was not a valid ServicesConfigurationList", e);
        }
    }

    @Override
    public byte[] toRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        var jutilConfig = new Properties();
        try {
            jutilConfig.load(new StringReader(styledFile));
            ServicesConfigurationList.Builder protoConfig = ServicesConfigurationList.newBuilder();
            jutilConfig.stringPropertyNames().stream()
                    .sorted(LEGACY_THROTTLES_FIRST_ORDER)
                    .forEach(prop -> protoConfig.addNameValue(
                            Setting.newBuilder().setName(prop).setValue(jutilConfig.getProperty(prop))));
            return protoConfig.build().toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Content was not a valid properties file", e);
        }
    }

    @Override
    public String preferredFileName() {
        return fileName;
    }

    public static final Comparator<String> LEGACY_THROTTLES_FIRST_ORDER = Comparator.<String>comparingInt(prop -> {
                if (isLegacyBouncerProp(prop)) {
                    return 0;
                } else if (prop.startsWith("throttling.hcs")) {
                    return 1;
                } else {
                    return 2;
                }
            })
            .thenComparing(Comparator.naturalOrder());

    private static Set<String> legacyBouncerProps =
            Set.of("throttlingTps", "simpletransferTps", "getReceiptTps", "queriesTps");

    private static boolean isLegacyBouncerProp(String prop) {
        return (!prop.contains("="))
                ? legacyBouncerProps.contains(prop)
                : legacyBouncerProps.contains(prop.substring(0, prop.indexOf("=")));
    }
}
