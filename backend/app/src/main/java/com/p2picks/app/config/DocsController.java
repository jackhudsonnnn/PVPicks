package com.p2picks.app.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The handbook's entry point; its pages are static files under {@code /docs/}. Open in dev; on LAN and in production
 * only for an admin session (`security.SessionSecurity`), linked from the admin portal.
 */
@Controller
class DocsController {
    @GetMapping("/docs")
    String redirect() {
        return "redirect:/docs/";
    }

    @GetMapping("/docs/")
    String index() {
        return "forward:/docs/index.html";
    }
}
