// SPDX-License-Identifier: Apache-2.0
/**
 * The Hedera implementation of the Hedera Node Schedule Service (as defined in
 * {@code com.hedera.node.app.service.schedule}).
 * The Schedule Service implements the handlers needed to support the following transactions:<br/>
 * <ul>
 *     <li>Schedule Create</li>
 *     <li>Schedule Sign</li>
 *     <li>Schedule Delete</li>
 *     <li>Query Schedule Get Info</li>
 * </ul>
 * Together, these form the full set of operations required to schedule an Hedera transaction in advance,
 * gather signatures for that transaction, monitor the state of the scheduled transaction, and possibly
 * delete (i.e. cancel) that transaction.  Execution is handled via the internal scheduled execution
 * within the node workflows.
 */
package com.hedera.node.app.service.schedule.impl;
