package pl.kingscastles.server.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator.Problem;
import pl.kingscastles.server.room.Player;
import pl.kingscastles.server.room.Room;
import pl.kingscastles.server.room.RoomService;
import pl.kingscastles.server.room.RoomService.GameException;
import pl.kingscastles.server.room.RoomSettings;

import java.util.List;

/**
 * The REST side: create a room, join it, start it. The game itself also works over REST
 * (handy for tests and slow phones), but the client normally plays over the WebSocket.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {
    static final String HOST = "X-Host-Token";
    static final String PLAYER = "X-Player-Token";

    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    public record JoinRequest(String name) {
    }

    public record ReadyRequest(boolean ready) {
    }

    @PostMapping
    public Views.Created create(@RequestBody(required = false) RoomSettings settings) {
        Room room = rooms.create(settings == null ? RoomSettings.defaults() : settings);
        return new Views.Created(room.code(), room.record.hostToken);
    }

    @GetMapping("/{code}")
    public Views.Lobby lobby(@PathVariable String code) {
        Room room = rooms.room(code);
        synchronized (room) {
            return ViewBuilder.lobby(room);
        }
    }

    @PostMapping("/{code}/join")
    public Views.Joined join(@PathVariable String code, @RequestBody JoinRequest req) {
        Player p = rooms.join(code, req.name());
        return new Views.Joined(p.token(), p.seat(), Room.kingdomOfSeat(p.seat()));
    }

    @PostMapping("/{code}/start")
    public Views.Lobby start(@PathVariable String code, @RequestHeader(HOST) String hostToken) {
        rooms.start(code, hostToken);
        return lobby(code);
    }

    @PostMapping("/{code}/end-turn")
    public void endTurn(@PathVariable String code, @RequestHeader(HOST) String hostToken) {
        rooms.hostEndTurn(code, hostToken);
    }

    /** The game as this player sees it. Without a player token: the host's or a spectator's view. */
    @GetMapping("/{code}/state")
    public Views.State state(@PathVariable String code,
                             @RequestHeader(value = PLAYER, required = false) String token,
                             @RequestParam(value = "token", required = false) String tokenParam) {
        Room room = rooms.room(code);
        String t = token != null ? token : tokenParam;
        synchronized (room) {
            if (room.game().isEmpty()) {
                throw new GameException("error.notPlaying");
            }
            String me = t == null ? null : rooms.kingdomOf(room, t).orElseThrow(() -> new GameException("error.notInRoom"));
            return ViewBuilder.state(room, me);
        }
    }

    @PostMapping("/{code}/orders")
    public List<Problem> orders(@PathVariable String code, @RequestHeader(PLAYER) String token,
                                @RequestBody List<Order> orders) {
        return rooms.submitOrders(code, token, orders);
    }

    @PostMapping("/{code}/ready")
    public void ready(@PathVariable String code, @RequestHeader(PLAYER) String token, @RequestBody ReadyRequest req) {
        rooms.ready(code, token, req.ready());
    }

    @ExceptionHandler(GameException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Views.Error gameError(GameException e) {
        return new Views.Error(e.key);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Views.Error unreadable(Exception e) {
        return new Views.Error("error.badRequest");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Views.Error badSettings(IllegalArgumentException e) {
        return new Views.Error("error.badSettings");
    }
}
