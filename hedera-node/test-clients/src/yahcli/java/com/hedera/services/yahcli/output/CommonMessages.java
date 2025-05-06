// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.output;

import com.hedera.services.bdd.spec.queries.QueryUtils;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.suites.Utils;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

public enum CommonMessages {
    COMMON_MESSAGES;

    public void warn(String notice) {
        System.out.println(".!. " + notice);
    }

    public void info(String notice) {
        System.out.println(".i. " + notice);
    }

    public void printGlobalInfo(ConfigManager config) {
        var msg = String.format("Targeting %s, paying with %s", config.getTargetName(), config.getDefaultPayer());
        System.out.println(msg);
    }

    public void beginBanner(String marker, String name) {
        var msg = marker + " BEGINNING " + name + " " + marker;
        System.out.println(msg);
    }

    public void appendBeginning(FileID target) {
        var msg = "Appending to the uploaded " + Utils.nameOf(target) + "...";
        System.out.print(msg);
        System.out.flush();
    }

    public void appendEnding(final ResponseCodeEnum resolvedStatus, final int appendsRemaining) {
        if (resolvedStatus == ResponseCodeEnum.SUCCESS) {
            System.out.println(resolvedStatus + " (" + (appendsRemaining - 1) + " appends left)");
        } else {
            System.out.println(resolvedStatus);
        }
    }

    public void uploadBeginning(FileID target) {
        var msg = "Uploading the " + Utils.nameOf(target) + "...";
        System.out.print(msg);
        System.out.flush();
    }

    public void uploadEnding(ResponseCodeEnum resolvedStatus) {
        System.out.println(resolvedStatus.toString());
    }

    public void downloadBeginning(FileID target) {
        var msg = "Downloading the " + Utils.nameOf(target) + "...";
        System.out.print(msg);
        System.out.flush();
    }

    public void downloadEnding(Response response) {
        try {
            var precheck = QueryUtils.reflectForPrecheck(response);
            System.out.println(precheck.toString());
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }
}
