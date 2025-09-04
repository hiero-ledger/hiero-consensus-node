// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.output;

import com.hedera.services.bdd.spec.queries.QueryUtils;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.suites.Utils;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

public enum StdoutYahcliOutput implements YahcliOutput {
    STDOUT_YAHCLI_OUTPUT;

    @Override
    public void warn(String notice) {
        System.out.println(".!. " + notice);
    }

    @Override
    public void info(String notice) {
        System.out.println(".i. " + notice);
    }

    @Override
    public void printGlobalInfo(ConfigManager config) {
        final var payer = config.getDefaultPayer();
        var msg = String.format(
                "Targeting %s, paying with %d.%d.%d",
                config.getTargetName(), payer.getShardNum(), payer.getRealmNum(), payer.getAccountNum());
        System.out.println(msg);
    }

    @Override
    public void appendBeginning(FileID target) {
        var msg = "Appending to the uploaded " + Utils.nameOf(target) + "...";
        System.out.print(msg);
        System.out.flush();
    }

    @Override
    public void appendEnding(final ResponseCodeEnum resolvedStatus, final int appendsRemaining) {
        if (resolvedStatus == ResponseCodeEnum.SUCCESS) {
            System.out.println(resolvedStatus + " (" + (appendsRemaining - 1) + " appends left)");
        } else {
            System.out.println(resolvedStatus);
        }
    }

    @Override
    public void uploadBeginning(FileID target) {
        var msg = "Uploading the " + Utils.nameOf(target) + "...";
        System.out.print(msg);
        System.out.flush();
    }

    @Override
    public void uploadEnding(ResponseCodeEnum resolvedStatus) {
        System.out.println(resolvedStatus.toString());
    }

    @Override
    public void downloadBeginning(FileID target) {
        var msg = "Downloading the " + Utils.nameOf(target) + "...";
        System.out.print(msg);
        System.out.flush();
    }

    @Override
    public void downloadEnding(Response response) {
        try {
            var precheck = QueryUtils.reflectForPrecheck(response);
            System.out.println(precheck.toString());
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }
}
