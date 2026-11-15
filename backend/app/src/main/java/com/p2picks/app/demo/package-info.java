/**
 * The dev demo world (dev profile only, when `pvpicks.demo.enabled`): the generated demo users and their
 * {@link com.p2picks.app.demo.DemoProfile behavior profiles}, the main accounts' seeded state, and the Dev page's
 * reset. See docs/site/demo-data.md.
 *
 * <p>Public types in this package are the module API; everything else is package-private.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"common", "security", "sports"})
package com.p2picks.app.demo;
