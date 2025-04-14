/*
 * Copyright 2016-2022 Hedera Hashgraph, LLC
 *
 * This software is the confidential and proprietary information of
 * Hedera Hashgraph, LLC. ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Hedera Hashgraph.
 *
 * HEDERA HASHGRAPH MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. HEDERA HASHGRAPH SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

/**
 * This package provides extended functionalities for providing locks in Java. For a general information about locks you
 * should have a look at the Java SE {@link java.util.concurrent.locks.Lock} class.
 * <p>
 * The main entry point is the {@link com.swirlds.common.threading.locks.Locks} interface that is a facade and factory to
 * create locks.
 */
package com.swirlds.common.threading.locks;
