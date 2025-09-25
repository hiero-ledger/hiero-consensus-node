package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import java.util.List;

public record HookCalls(HookContext context,
                        List<HookCallFactory.HookInvocation> preOnlyHooks,
                        List<HookCallFactory.HookInvocation> prePostHooks) {
}
