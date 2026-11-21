/**
 * The LAN watch party (lan profile only; docs: LAN watch party): the party table. The first player the admin
 * creates gets a table named "New Table" and hosts it; every later player is seated there and becomes friends with
 * everyone already at it. Switched on by {@code pvpicks.lan.party.enabled}, which only {@code application-lan.yml}
 * sets. Nothing depends on this module: it listens for {@link com.p2picks.app.accounts.PlayerCreated}.
 *
 * <p>Public types in this package are the module API; everything else is package-private.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"accounts", "tables", "social", "common", "security"})
package com.p2picks.app.lan;
