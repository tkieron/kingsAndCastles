package pl.kingscastles.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator.Problem;
import pl.kingscastles.server.room.Room;
import pl.kingscastles.server.room.RoomRecord;
import pl.kingscastles.server.room.RoomService;
import pl.kingscastles.server.room.RoomService.Change;
import pl.kingscastles.server.room.RoomService.GameException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live connection for one screen. Messages are JSON objects with a "type":
 * <pre>
 * client → server
 *   {"type":"hello","code":"123456","token":"..."}      player token, or "hostToken" for the host's screen
 *   {"type":"orders","orders":[{"type":"build",...}]}   replaces this turn's orders
 *   {"type":"ready","ready":true}
 *   {"type":"endTurn"}                                  host only
 *   {"type":"ping"}
 * server → client
 *   {"type":"lobby",...}   {"type":"state",...}   {"type":"ready","ready":[...]}
 *   {"type":"problems","problems":[...]}   {"type":"ordersOk"}   {"type":"error","error":"error.key"}   {"type":"pong"}
 * </pre>
 * A new "state" is sent to everyone when a turn ends; it carries last turn's report.
 */
@Component
public class GameSocket extends TextWebSocketHandler implements RoomService.Listener {
    private static final Logger log = LoggerFactory.getLogger(GameSocket.class);

    private final RoomService rooms;
    private final ObjectMapper json;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    /** Who is behind one connection. kingdom is null on the host's screen. */
    record Client(WebSocketSession session, String code, String token, String kingdom, boolean host) {
    }

    public GameSocket(RoomService rooms, ObjectMapper json) {
        this.rooms = rooms;
        this.json = json;
        rooms.addListener(this);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // the decorator queues messages so the turn clock and a player's own reply never write at once
        session.getAttributes().put("out", new ConcurrentWebSocketSessionDecorator(session, 5_000, 2 * 1024 * 1024));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode msg;
        try {
            msg = json.readTree(message.getPayload());
        } catch (IOException e) {
            send(session, Map.of("type", "error", "error", "error.badRequest"));
            return;
        }
        String type = msg.path("type").asText();
        try {
            switch (type) {
                case "hello" -> hello(session, msg);
                case "orders" -> orders(session, msg);
                case "ready" -> {
                    Client c = client(session);
                    rooms.ready(c.code, c.token, msg.path("ready").asBoolean(true));
                }
                case "endTurn" -> {
                    Client c = client(session);
                    rooms.hostEndTurn(c.code, c.token);
                }
                case "ping" -> send(session, Map.of("type", "pong"));
                default -> send(session, Map.of("type", "error", "error", "error.badRequest"));
            }
        } catch (GameException e) {
            send(session, Map.of("type", "error", "error", e.key));
        }
    }

    private void hello(WebSocketSession session, JsonNode msg) {
        String code = msg.path("code").asText();
        Room room = rooms.room(code);
        String token = msg.path("token").asText(null);
        String hostToken = msg.path("hostToken").asText(null);
        Client c;
        if (token != null) {
            String kingdom = rooms.kingdomOf(room, token).orElseThrow(() -> new GameException("error.notInRoom"));
            c = new Client(session, code, token, kingdom, room.isHost(hostToken));
        } else if (hostToken != null && room.isHost(hostToken)) {
            c = new Client(session, code, hostToken, null, true);
        } else {
            throw new GameException("error.notInRoom");
        }
        clients.put(session.getId(), c);
        sendView(c);
    }

    private void orders(WebSocketSession session, JsonNode msg) throws IOException {
        Client c = client(session);
        List<Order> orders = json.readerForListOf(Order.class).readValue(msg.path("orders"));
        List<Problem> problems = rooms.submitOrders(c.code, c.token, orders);
        if (problems.isEmpty()) {
            send(session, Map.of("type", "ordersOk"));
        } else {
            send(session, Map.of("type", "problems", "problems", problems));
        }
    }

    private Client client(WebSocketSession session) {
        Client c = clients.get(session.getId());
        if (c == null) {
            throw new GameException("error.sayHelloFirst");
        }
        if (c.kingdom == null && !c.host) {
            throw new GameException("error.notInRoom");
        }
        return c;
    }

    // ------------------------------------------------------------------ pushing changes

    @Override
    public void roomChanged(Room room, Change change) {
        for (Client c : clients.values()) {
            if (!c.code.equals(room.code())) {
                continue;
            }
            switch (change) {
                case LOBBY, TURN -> sendView(c);
                case READY -> send(c.session, Map.of("type", "ready", "ready", room.ready()));
                case ORDERS -> {
                    // others only learn that someone changed their mind when they press Ready; nothing to send
                }
            }
        }
    }

    private void sendView(Client c) {
        Room room = rooms.room(c.code);
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (room) {
            if (room.record.status == RoomRecord.Status.LOBBY) {
                out.put("type", "lobby");
                out.put("lobby", ViewBuilder.lobby(room));
            } else {
                out.put("type", "state");
                out.put("state", ViewBuilder.state(room, c.kingdom));
            }
            if (c.host) {
                out.put("host", true);
            }
        }
        send(c.session, out);
    }

    private void send(WebSocketSession session, Object payload) {
        WebSocketSession out = (WebSocketSession) session.getAttributes().getOrDefault("out", session);
        try {
            if (out.isOpen()) {
                out.sendMessage(new TextMessage(json.writeValueAsString(payload)));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not send to {}", session.getId(), e);
        }
    }
}
