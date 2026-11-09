package pl.kingscastles.server.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** The PWA lives on GitHub Pages, so the server allows calls from there (kc.allowed-origins). */
@Configuration
@EnableWebSocket
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer {
    private final String[] origins;
    private final GameSocket socket;

    public WebConfig(@Value("${kc.allowed-origins}") String origins, GameSocket socket) {
        this.origins = origins.split("\\s*,\\s*");
        this.socket = socket;
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
