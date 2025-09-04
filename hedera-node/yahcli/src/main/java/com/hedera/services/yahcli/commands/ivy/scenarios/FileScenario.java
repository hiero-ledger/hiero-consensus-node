// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.scenarios;

public class FileScenario {
    public static String NOVEL_FILE_NAME = "novelFile";
    public static String PERSISTENT_FILE_NAME = "persistentFile";
    public static String DEFAULT_CONTENTS = "MrBleaney.txt";

    PersistentFile persistent;

    public PersistentFile getPersistent() {
        return persistent;
    }

    public void setPersistent(PersistentFile persistent) {
        this.persistent = persistent;
    }
}
