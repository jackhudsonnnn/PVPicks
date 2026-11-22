package com.p2picks.app.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Turns a path a browser called ({@code /tables/4f…/bets}) into the server route it hit
 * ({@code /tables/{tableId}/bets}), so client timings line up with server ones. Unknown paths have no route and
 * are dropped, which keeps client reports from inventing routes.
 */
@Component
class PerfRoutes {
    private record Route(String method, PathPattern pattern) {}

    private final RequestMappingHandlerMapping mappings;
    private volatile List<Route> routes;

    PerfRoutes(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    Optional<String> route(String method, String path) {
        if (path == null || !path.startsWith("/") || path.length() > 300) return Optional.empty();
        PathContainer container = PathContainer.parsePath(path);
        PathPattern best = null;
        for (Route route : routes()) {
            if (!route.method().equals(method) || !route.pattern().matches(container)) continue;
            if (best == null || PathPattern.SPECIFICITY_COMPARATOR.compare(route.pattern(), best) < 0) {
                best = route.pattern();
            }
        }
        return Optional.ofNullable(best).map(PathPattern::getPatternString);
    }

    private List<Route> routes() {
        List<Route> loaded = routes;
        if (loaded != null) return loaded;
        List<Route> found = new ArrayList<>();
        PathPatternParser parser = new PathPatternParser();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            for (String pattern : info.getPatternValues()) {
                for (var method : info.getMethodsCondition().getMethods()) {
                    found.add(new Route(method.name(), parser.parse(pattern)));
                }
            }
        });
        routes = List.copyOf(found);
        return routes;
    }
}
