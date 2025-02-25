// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.validation.domain;

import java.util.ArrayList;
import java.util.List;

public class SysFilesDownScenario {
    public static final String COMPARE_EVAL_MODE = "compare";
    public static final String SNAPSHOT_EVAL_MODE = "snapshot";

    String evalMode = SNAPSHOT_EVAL_MODE;
    List<Integer> numsToFetch = new ArrayList<>();

    public List<Integer> getNumsToFetch() {
        return numsToFetch;
    }

    public void setNumsToFetch(List<Integer> numsToFetch) {
        this.numsToFetch = numsToFetch;
    }

    public String getEvalMode() {
        return evalMode;
    }

    public void setEvalMode(String evalMode) {
        this.evalMode = evalMode;
    }
}
