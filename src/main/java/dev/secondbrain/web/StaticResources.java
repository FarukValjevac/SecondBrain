package dev.secondbrain.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.CssLinkResourceTransformer;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.time.Duration;

/**
 * Stylesheets, fonts and icons are served under content-hashed names (for example {@code
 * /css/app-3f2a….css}, written by Thymeleaf's {@code @{...}} links) and may be cached for a year: a
 * changed file gets a new name. Scripts are versioned too, but browsers revalidate them on each
 * use, because module imports such as {@code ./dialogs.js} are plain names that cannot carry a
 * hash. Revalidation is a cheap 304 when nothing changed, and an update is never served stale.
 */
@Configuration(proxyBeanMethods = false)
class StaticResources implements WebMvcConfigurer {
    private final String[] locations;
    private final Duration maximumAge;
    private final boolean cacheResolved;

    StaticResources(
            @Value("${spring.web.resources.static-locations:classpath:/static/}")
                    String[] locations,
            @Value("${secondbrain.web.static-max-age:365d}") Duration maximumAge,
            @Value("${spring.web.resources.chain.cache:true}") boolean cacheResolved) {
        this.locations = locations.clone();
        this.maximumAge = maximumAge;
        this.cacheResolved = cacheResolved;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        var longLived =
                maximumAge.isZero()
                        ? CacheControl.noCache()
                        : CacheControl.maxAge(maximumAge).cachePublic().immutable();
        register(registry, "/css/**", "css/", longLived, true);
        register(registry, "/fonts/**", "fonts/", longLived, false);
        register(registry, "/js/**", "js/", CacheControl.noCache(), false);
        for (var icon : new String[] {"/favicon.svg", "/favicon-32.png", "/apple-touch-icon.png"})
            register(registry, icon, "", longLived, false);
    }

    /** A handler's path is resolved below its own folder, so each folder is registered alone. */
    private void register(
            ResourceHandlerRegistry registry,
            String pattern,
            String folder,
            CacheControl caching,
            boolean stylesheet) {
        var folders = new String[locations.length];
        for (int index = 0; index < locations.length; index++)
            folders[index] =
                    (locations[index].endsWith("/") ? locations[index] : locations[index] + "/")
                            + folder;
        var chain =
                registry.addResourceHandler(pattern)
                        .addResourceLocations(folders)
                        .setCacheControl(caching)
                        .resourceChain(cacheResolved)
                        .addResolver(
                                new VersionResourceResolver().addContentVersionStrategy("/**"));
        // Rewrites url(...) references such as fonts to their hashed names.
        if (stylesheet) chain.addTransformer(new CssLinkResourceTransformer());
    }
}
