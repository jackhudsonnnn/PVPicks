package com.p2picks.app.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The curated GIF library (`chat/gifs.json`, hosted on Tenor's CDN). The server owns it: sending checks ids
 * against it and `GET /gifs` serves it to the picker, so a live GIF search can replace it in one place.
 */
@Component
public class GifCatalog {
    public record Category(String id, String label) {}
    public record Gif(String id, String url, String alt, String category, List<String> tags) {}
    public record Catalog(List<Category> categories, List<Gif> gifs) {}

    private final Catalog catalog;
    private final Set<String> ids;

    GifCatalog(ObjectMapper json) throws IOException {
        try (InputStream input = GifCatalog.class.getResourceAsStream("/chat/gifs.json")) {
            if (input == null) throw new IllegalStateException("chat/gifs.json is missing");
            Catalog loaded = json.readValue(input, Catalog.class);
            this.catalog = new Catalog(List.copyOf(loaded.categories()), List.copyOf(loaded.gifs()));
        }
        this.ids = catalog.gifs().stream().map(Gif::id).collect(Collectors.toUnmodifiableSet());
        Set<String> categories = catalog.categories().stream().map(Category::id).collect(Collectors.toSet());
        if (ids.size() != catalog.gifs().size()
                || catalog.gifs().stream().anyMatch(gif -> !categories.contains(gif.category()))) {
            throw new IllegalStateException("chat/gifs.json has duplicate ids or unknown categories");
        }
    }

    public Catalog catalog() {
        return catalog;
    }

    public boolean contains(String id) {
        return ids.contains(id);
    }
}
