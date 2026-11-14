/**
 * Player reports and moderation: admins review reports, dismiss them or give strikes; a third strike freezes the
 * account (through `accounts`).
 *
 * <p>Public types in this package are the module API; everything else is package-private.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"accounts", "common", "security"})
package com.p2picks.app.moderation;
