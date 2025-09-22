package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import java.util.List;

public record HookInvocations(List<HookInvocation> pre, List<HookInvocation> post) {
}
