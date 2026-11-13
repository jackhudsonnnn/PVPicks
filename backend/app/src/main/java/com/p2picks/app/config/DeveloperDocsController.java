package com.p2picks.app.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("dev")
class DeveloperDocsController {
    @GetMapping("/docs")
    String redirect() {
        return "redirect:/docs/";
    }

    @GetMapping("/docs/")
    String index() {
        return "forward:/docs/index.html";
    }
}
