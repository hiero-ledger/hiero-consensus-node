// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import java.util.List;

/**
 * A record that encapsulates the context and lists of hook invocations for pre-only and pre-post hooks.
 *
 * @param context the context for the hook calls
 * @param preOnlyHooks the list of pre-only hook invocations
 * @param prePostHooks the list of pre-post hook invocations
 */
public record HookCalls(
        HookContext context,
        List<HookCallFactory.HookInvocation> preOnlyHooks,
        List<HookCallFactory.HookInvocation> prePostHooks) {}
