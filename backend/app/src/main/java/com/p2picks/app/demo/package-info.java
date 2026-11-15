/**
 * The dev demo world (dev profile only, when `pvpicks.demo.enabled`): the generated demo users and their
 * {@link com.p2picks.app.demo.DemoProfile behavior profiles}, the main accounts' seeded state, and the Dev page's
 * reset, and the bots that play the demo users through the same services real requests use. See
 * docs/site/demo-data.md.
 *
 * <p>Public types in this package are the module API; everything else is package-private.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "bets", "chat", "common", "moderation", "security", "settlement", "social", "sports"})
package com.p2picks.app.demo;
