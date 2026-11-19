package pl.kingscastles.server.web;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** With kc.client-dir set, one address serves the game page, its files, the API and the WebSocket. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientFilesTest {
    static Path root;
    static Path client;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry r) throws IOException {
        root = Files.createTempDirectory("kc-client-test");
        client = Files.createDirectories(root.resolve("dist"));
        Files.createDirectories(client.resolve("assets"));
        Files.writeString(client.resolve("index.html"), "<!doctype html><title>Kings</title>");
        Files.writeString(client.resolve("assets/index-abc.js"), "console.log(1)");
        Files.writeString(root.resolve("secret.txt"), "not for the web");
        r.add("kc.client-dir", client::toString);
        r.add("kc.data-dir", () -> root.resolve("data").toString());
    }

    @AfterAll
    static void clean() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Autowired
    TestRestTemplate http;

    @Test
    void theGamePageComesFromTheSameAddressAsTheApi() {
        ResponseEntity<String> page = http.getForEntity("/", String.class);
        assertEquals(HttpStatus.OK, page.getStatusCode());
        assertTrue(page.getBody().contains("<title>Kings</title>"));
        assertEquals("no-cache", page.getHeaders().getCacheControl());

        ResponseEntity<String> js = http.getForEntity("/assets/index-abc.js", String.class);
        assertEquals(HttpStatus.OK, js.getStatusCode());
        assertTrue(js.getHeaders().getCacheControl().contains("immutable"));

        assertEquals(HttpStatus.OK, http.getForEntity("/api/rules", String.class).getStatusCode());
    }

    @Test
    void filesOutsideTheGameFolderStayHidden() {
        assertNotEquals(HttpStatus.OK, http.getForEntity("/../secret.txt", String.class).getStatusCode());
        assertNotEquals(HttpStatus.OK, http.getForEntity("/%2e%2e/secret.txt", String.class).getStatusCode());
        assertNotEquals(HttpStatus.OK, http.getForEntity("/assets/..%2f..%2fsecret.txt", String.class).getStatusCode());
        assertNotEquals(HttpStatus.OK, http.getForEntity("/secret.txt", String.class).getStatusCode());
    }
}
