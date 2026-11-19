package pl.kingscastles.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.file.Files;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "kc.data-dir=${java.io.tmpdir}/kc-test-${random.uuid}")
class GameFlowTest {
    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;

    private JsonNode post(String url, String body, String header, String token) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (header != null) {
            h.set(header, token);
        }
        ResponseEntity<String> r = http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        return r.getBody() == null ? null : json.readTree(r.getBody());
    }

    @Test
    void aChildPlaysATurnOverRestAndWebSocket() throws Exception {
        JsonNode created = post("/api/rooms", "{\"turnSeconds\":0,\"maxTurns\":12,\"seats\":"
                + "[\"HUMAN\",\"AI_BEGINNER\",\"AI_BEGINNER\",\"AI_ADVANCED\",\"AI_PRO\",\"AI_BEGINNER\",\"HUMAN\"]}",
                null, null);
        String code = created.get("code").asText();
        String host = created.get("hostToken").asText();

        JsonNode ania = post("/api/rooms/" + code + "/join", "{\"name\":\"Ania\",\"realm\":\"Kraina Lisów\",\"avatar\":3}", null, null);
        assertEquals("fox", ania.get("kingdom").asText());
        String token = ania.get("token").asText();

        JsonNode lobby = json.readTree(http.getForObject("/api/rooms/" + code, String.class));
        assertEquals("Ania", lobby.at("/seats/0/player").asText());
        assertEquals(7, lobby.get("seats").size());
        assertEquals("Kraina Lisów", lobby.at("/seats/0/realm").asText());
        assertEquals(3, lobby.at("/seats/0/avatar").asInt());

        // a child cannot start the game
        JsonNode refused = post("/api/rooms/" + code + "/start", "", RoomController.HOST, token);
        assertEquals("error.notHost", refused.get("error").asText());

        BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        WebSocketSession ws = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                inbox.add(json.readTree(m.getPayload()));
            }
        }, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);
        ws.sendMessage(new TextMessage("{\"type\":\"hello\",\"code\":\"" + code + "\",\"token\":\"" + token + "\"}"));
        assertEquals("lobby", next(inbox).get("type").asText());

        post("/api/rooms/" + code + "/start", "", RoomController.HOST, host);
        JsonNode state = next(inbox);
        assertEquals("state", state.get("type").asText());
        JsonNode s = state.get("state");
        assertEquals(1, s.get("turn").asInt());
        assertEquals("fox", s.get("me").asText());
        assertEquals(26, s.at("/map/rows").size());
        assertEquals(34, s.at("/map/rows/0").asText().length());
        assertEquals(7, s.get("kingdoms").size());
        for (JsonNode k : s.get("kingdoms")) {
            assertEquals(k.get("id").asText().equals("fox"), k.has("gold"), "only my own gold is shown: " + k);
        }

        ws.sendMessage(new TextMessage("{\"type\":\"orders\",\"orders\":[{\"order\":\"setTax\",\"rate\":\"MEDIUM\"}]}"));
        assertEquals("ordersOk", next(inbox).get("type").asText());
        ws.sendMessage(new TextMessage("{\"type\":\"orders\",\"orders\":[{\"order\":\"build\",\"settlementId\":999,\"building\":\"MARKET\"}]}"));
        JsonNode problems = next(inbox);
        assertEquals("problems", problems.get("type").asText());
        assertTrue(problems.at("/problems/0/key").asText().startsWith("error."));

        ws.sendMessage(new TextMessage("{\"type\":\"ready\",\"ready\":true}"));
        assertEquals("ready", next(inbox).get("type").asText());
        JsonNode turn2 = next(inbox);
        assertEquals(2, turn2.at("/state/turn").asInt());
        assertEquals("MEDIUM", turn2.at("/state/kingdoms/0/tax").asText());
        assertEquals("Kraina Lisów", turn2.at("/state/kingdoms/0/realm").asText());
        assertEquals(3, turn2.at("/state/kingdoms/0/avatar").asInt());
        assertTrue(turn2.at("/state/report/ledger").size() > 0, "Ania sees her Ledger");

        JsonNode rest = json.readTree(http.exchange("/api/rooms/" + code + "/state", HttpMethod.GET,
                new HttpEntity<>(headers(RoomController.PLAYER, token)), String.class).getBody());
        assertEquals(2, rest.get("turn").asInt());

        ws.close();
    }

    @Test
    void badSettingsAndUnknownRoomsGiveAnErrorKey() throws Exception {
        JsonNode bad = post("/api/rooms", "{\"turnSeconds\":7,\"maxTurns\":12,\"seats\":[]}", null, null);
        assertEquals("error.badRequest", bad.get("error").asText());
        JsonNode none = json.readTree(http.getForObject("/api/rooms/999999x", String.class));
        assertEquals("error.noSuchRoom", none.get("error").asText());
    }

    @Test
    void theRulesListPricesAndKingdoms() throws Exception {
        JsonNode rules = json.readTree(http.getForObject("/api/rules", String.class));
        assertEquals(7, rules.get("kingdoms").size());
        assertEquals(50, rules.at("/buildings/1/cost").asInt());
        assertEquals("MARKET", rules.at("/buildings/1/type").asText());
        assertEquals(100, rules.at("/balance/startGold").asInt());
        assertTrue(rules.at("/units/2/needsBarracks").asBoolean());
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders h = new HttpHeaders();
        h.set(name, value);
        return h;
    }

    private static JsonNode next(BlockingQueue<JsonNode> inbox) throws InterruptedException {
        JsonNode n = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(n, "no message from the server");
        return n;
    }
}
