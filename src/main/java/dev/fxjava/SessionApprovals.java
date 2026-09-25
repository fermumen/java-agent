package dev.fxjava;

import java.util.HashSet;
import java.util.Set;

/**
 * Session-scoped "always this session" approval grants keyed by exact tool
 * name plus canonical structured arguments. Preview text is for display only.
 * In-memory only: a restart (a new instance) starts with no grants.
 */
final class SessionApprovals {
    private final Set<String> grants = new HashSet<>();

    synchronized boolean allows(String tool, String canonicalArguments) {
        return grants.contains(ApprovalPrompt.grantKey(tool, canonicalArguments));
    }

    synchronized void grant(String tool, String canonicalArguments) {
        grants.add(ApprovalPrompt.grantKey(tool, canonicalArguments));
    }

    synchronized int count() {
        return grants.size();
    }

    synchronized void clear() {
        grants.clear();
    }
}
