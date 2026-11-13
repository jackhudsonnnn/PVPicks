/**
 * Shared infrastructure: HTTP retry protection (IdempotencyGuard), API error bodies, text validation and the betting readiness marker.
 *
 * <p>An open module: every feature module may use it, and it must not depend on feature modules.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.p2picks.app.common;
