// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class Constants {

    public static String STATE_DIR = System.getProperty("state.dir");

    public static String TMP_DIR = System.getProperty("tmp.dir", "");

    public static String NODE_NAME = System.getProperty("node.name");

    //
    public static int PARALLELISM = Integer.parseInt(
            System.getProperty("thread.num", "" + Runtime.getRuntime().availableProcessors()));
    public static int FILE_CHANNELS = Integer.parseInt(
            System.getProperty("file.channels", "" + (Runtime.getRuntime().availableProcessors() / 2)));

    public static Boolean VALIDATE_FILE_LAYOUT =
            Boolean.parseBoolean(System.getProperty("validate.file.layout", "true"));
    public static Integer COLLECTED_INFO_THRESHOLD = Integer.parseInt(System.getProperty("hdhm.collected.infos", "20"));
    // current default is 0, previous default was 8388608

    public static String NODE_DESCRIPTION = System.getProperty("node.description");
    public static String ROUND = System.getProperty("round", "");
    public static String NET_NAME = System.getProperty("net.name");
    public static Set<String> VALIDATE_STALE_KEYS_EXCLUSIONS = Arrays.stream(
                    System.getProperty("validate.stale.keys.exclusions", "").split(","))
            .collect(Collectors.toSet());
    public static Set<String> VALIDATE_INCORRECT_BUCKET_INDEX_EXCLUSIONS = Arrays.stream(
                    System.getProperty("validate.incorrect.bucket.index.exclusions", "TokenService.PENDING_AIRDROPS")
                            .split(","))
            .collect(Collectors.toSet());

    public static String SLACK_TAGS = System.getProperty("slack.tags", "@ivan");
    public static String JOB_URL = System.getProperty("job.url");
}
