/**
 * Delivery: publishes committed outbox events to Kafka and turns them into realtime invalidations for
 * authorized WebSocket (STOMP) subscribers. HTTP reads stay authoritative; delivery is best effort.
 *
 * <p>Public types in this package are the module API; everything else is package-private.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"common"})
package com.p2picks.app.delivery;
