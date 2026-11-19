package pl.kingscastles.server.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Calls from other addresses are allowed only from kc.allowed-origins. When kc.client-dir points at the built
 * browser game (client/dist), the server also serves the game itself, so one address is enough for everything.
 */
@Configuration
@EnableWebSocket
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer {
    private final String[] origins;
    private final GameSocket socket;
    private final String clientDir;

    public WebConfig(@Value("${kc.allowed-origins}") String origins, @Value("${kc.client-dir:}") String clientDir,
                     GameSocket socket) {
        this.origins = origins.split("\\s*,\\s*");
        this.clientDir = clientDir.isBlank() ? null : Path.of(clientDir).toAbsolutePath().normalize().toUri().toString();
        this.socket = socket;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (clientDir == null) {
            return;
        }
        // file names under assets/ change with every build, so they can be kept for long
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(clientDir + "assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        // the page, the service worker and the icons are checked every time, so a new version shows up at once
        registry.addResourceHandler("/**")
                .addResourceLocations(clientDir)
                .setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (clientDir != null) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST")
                .allowedHeaders("*");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws").setAllowedOriginPatterns(origins);
    }
}
