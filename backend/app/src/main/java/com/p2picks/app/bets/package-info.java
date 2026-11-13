/**
 * Bets: U2Pick proposal, picks, locking, resolution and payout, plus bet reads and the opt-in lock worker.
 *
 * <p>Public types in this package are the module API; everything else is package-private.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"settlement", "common", "security"})
package com.p2picks.app.bets;
