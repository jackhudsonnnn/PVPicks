/**
 * Dev sports data (dev profile only, when `pvpicks.dev-sports.dir` is set): scenario files you edit live and
 * recorded chunks you replay, fed into the real sports pipeline as `DEV-` games next to real ones. See
 * docs/site/dev-sports-data.md.
 *
 * <p>Public types in this package are the module API; everything else is package-private.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"sports", "common"})
package com.p2picks.app.devdata;
