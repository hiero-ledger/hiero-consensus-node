// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.output;

import com.hedera.services.yahcli.config.ConfigManager;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

public interface YahcliOutput {
    void warn(String notice);

    void info(String notice);

    void printGlobalInfo(ConfigManager config);

    void appendBeginning(FileID target);

    void appendEnding(ResponseCodeEnum resolvedStatus, int appendsRemaining);

    void uploadBeginning(FileID target);

    void uploadEnding(ResponseCodeEnum resolvedStatus);

    void downloadBeginning(FileID target);

    void downloadEnding(Response response);
}
