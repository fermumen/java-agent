package dev.fxjava;

import java.util.HashSet;
import java.util.Set;

/**
 * Session-scoped "always this session" approval grants keyed by tool plus
 * normalized preview. In-memory only: a restart (a new instance) starts with
 * no grants.
 */
final class SessionApprovals {
    private final Set<String> grants = new HashSet<>();

    synchronized boolean allows(String tool, String preview) {
        return grants.contains(ApprovalPrompt.grantKey(tool, preview));
    }

    synchronized void grant(String tool, String preview) {
        grants.add(ApprovalPrompt.grantKey(tool, preview));
    }

    synchronized int count() {
        return grants.size();
    }

    synchronized void clear() {
        grants.clear();
    }
}
