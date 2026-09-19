package com.jarvis.api;

/**
 * Consequence classification for a mutation, per docs/PROJECT_DECISIONS.md.
 *
 * <ul>
 *     <li>{@link #LOW}: read-only operations (search, status, history).</li>
 *     <li>{@link #MEDIUM}: creates and copies.</li>
 *     <li>{@link #HIGH}: moves and renames.</li>
 *     <li>{@link #CRITICAL}: deletes, overwrites and encryption — not
 *     implemented in sprint 2; JARVIS never deletes user files.</li>
 * </ul>
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
