// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

/**
 * Contains a single account number and a single associated token number.
 */
public record AccountNumTokenNum(Long accountNum, Long tokenNum) {}
