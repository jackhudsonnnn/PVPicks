/**
 * Account lifecycle: freezing, unfreezing and deleting accounts. It owns account status changes and coordinates
 * the modules that hold an account's data, each through its own cleanup API (tables recover hosts, chat removes
 * messages, and so on), so no module writes another's tables.
 *
 * <p>Public types in this package are the module API; everything else is package-private.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tables", "chat", "social", "feedback", "settlement", "bets", "common", "security"})
package com.p2picks.app.accounts;
