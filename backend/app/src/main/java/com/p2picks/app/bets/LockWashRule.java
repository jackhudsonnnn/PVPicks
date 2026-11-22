package com.p2picks.app.bets;

import java.util.Optional;
import java.util.UUID;

/**
 * A mode-specific reason to wash a bet the moment its picks lock (for example, a Prop Hunt line the player already
 * passed). The general lock washes (nobody picked, everyone on one option) run first, in {@link BetLockService}.
 * Implementations run inside the lock transaction, after picks are final. See the domain rules, "Wash triggers".
 */
interface LockWashRule {
    /** The wash reason, or empty to let the bet go Pending. */
    Optional<String> washAtLock(UUID betId, String mode);
}
