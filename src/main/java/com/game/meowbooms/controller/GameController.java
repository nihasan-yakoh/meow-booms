package com.game.meowbooms.controller;

import com.game.meowbooms.model.*;
import com.game.meowbooms.service.GameService;
import com.game.meowbooms.service.RoomManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

@Controller
@Slf4j
@AllArgsConstructor
public class GameController {

    private final RoomManager roomManager;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/create-room")
    public void createRoom(@Header("simpSessionId") String sessionId, CreateRoomRequest request) {
        try {
            String roomId = roomManager.createRoom(request.getRoomName(), request.getPlayerName(), request.getToken(), sessionId);
            Map<String, Object> response = new HashMap<>();
            response.put("roomCreated", roomId);
            response.put("targetPlayer", request.getPlayerName());
            response.put("initialState", roomManager.getRoom(roomId).getGameState());
            messagingTemplate.convertAndSend("/topic/lobby", response);
        } catch (Exception e) {
            sendLobbyError(request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/join-room")
    public void joinRoom(@Header("simpSessionId") String sessionId, JoinRequest request) {
        try {
            roomManager.joinRoom(request.getRoomId(), sessionId, request.getName(), request.getToken());
            Map<String, Object> response = new HashMap<>();
            response.put("roomJoined", request.getRoomId());
            response.put("targetPlayer", request.getName());
            response.put("initialState", roomManager.getRoom(request.getRoomId()).getGameState());
            messagingTemplate.convertAndSend("/topic/lobby", response);
        } catch (Exception e) {
            sendLobbyError(request.getName(), e.getMessage());
        }
    }

    @MessageMapping("/list-rooms")
    public void listRooms() {
        roomManager.broadcastRoomList();
    }

    @MessageMapping("/start")
    public void startGame(RoomActionRequest request) {
        try {
            roomManager.getRoom(request.getRoomId()).startGame(request.getPlayerName());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/reset")
    public void resetGame(RoomActionRequest request) {
        try {
            roomManager.getRoom(request.getRoomId()).resetGame(request.getPlayerName());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/draw")
    public void drawCard(RoomActionRequest request) {
        try {
            roomManager.getRoom(request.getRoomId()).drawCard(request.getPlayerName());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/alter-future")
    public void alterFuture(AlterFutureRequest request) {
        try {
            roomManager.getRoom(request.getRoomId()).confirmAlterFuture(request.getPlayerName(), request.getNewCardOrder());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/play-card")
    public void playCard(PlayCardRequest request) {
        try {
            roomManager.getRoom(request.getRoomId()).playCard(
                    request.getPlayerName(),
                    request.getCardIndices(),
                    request.getTargetPlayerName(),
                    request.getRequestedCardType()
            );
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/give-card")
    public void giveCard(PlayCardRequest request) {
        try {
            if (request.getCardIndices() == null || request.getCardIndices().isEmpty()) {
                throw new RuntimeException("ไม่ได้เลือกการ์ดที่จะส่ง!");
            }
            roomManager.getRoom(request.getRoomId()).giveCard(request.getPlayerName(), request.getCardIndices().getFirst());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/pick-discard")
    public void pickFromDiscard(PickDiscardRequest request) {
        try {
            roomManager.getRoom(request.getRoomId()).pickCardFromDiscard(request.getPlayerName(), request.getDiscardIndex());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/place-bomb")
    public void placeBomb(PlayCardRequest request) {
        try {
            roomManager.getRoom(request.getRoomId()).placeBomb(request.getPlayerName(), request.getTargetIndex());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/ready")
    public void toggleReady(RoomActionRequest request) {
        try {
            roomManager.getRoom(request.getRoomId()).toggleReady(request.getPlayerName());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getPlayerName(), e.getMessage());
        }
    }

    @MessageMapping("/leave-room")
    public void leaveRoom(RoomActionRequest request) {
        try {
            roomManager.leaveRoom(request.getRoomId(), request.getPlayerName());
        } catch (Exception ignored) {
        }
    }

    @MessageMapping("/request-state")
    public void requestState(RoomActionRequest request) {
        try {
            GameService game = roomManager.getRoom(request.getRoomId());
            messagingTemplate.convertAndSend("/topic/game/" + request.getRoomId(), game.getGameState());
        } catch (Exception ignored) {
        }
    }

    @MessageMapping("/add-bot")
    public void addBot(AddBotRequest request) {
        try {
            roomManager.addBot(request.getRoomId(), request.getHostName(), request.getDifficulty());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getHostName(), e.getMessage());
        }
    }

    @MessageMapping("/kick")
    public void kickPlayer(KickRequest request) {
        try {
            roomManager.getRoom(request.getRoomId()).kickPlayer(request.getHostName(), request.getTargetName());
        } catch (Exception e) {
            sendError(request.getRoomId(), request.getHostName(), e.getMessage());
        }
    }

    private void sendError(String roomId, String targetName, String errorMessage) {
        Map<String, Object> errorPayload = new HashMap<>();
        errorPayload.put("content", "Error: " + errorMessage);
        errorPayload.put("errorTarget", targetName);
        messagingTemplate.convertAndSend("/topic/game/" + roomId, errorPayload);
    }

    private void sendLobbyError(String targetName, String errorMessage) {
        Map<String, Object> errorPayload = new HashMap<>();
        errorPayload.put("content", "Error: " + errorMessage);
        errorPayload.put("errorTarget", targetName);
        messagingTemplate.convertAndSend("/topic/lobby", errorPayload);
    }
}