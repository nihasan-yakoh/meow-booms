package com.game.meowbooms.service;

import com.game.meowbooms.model.Room;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RoomManager {

    private final Map<String, GameService> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToRoom = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;

    public RoomManager(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public String createRoom(String roomName, String hostName, String token, String sessionId) {
        String roomId = generateRoomId();
        String name = (roomName == null || roomName.isBlank()) ? hostName + "'s Room" : roomName;
        GameService game = new GameService(messagingTemplate, roomId, name);
        rooms.put(roomId, game);
        game.joinGame(sessionId, hostName, token);
        sessionToRoom.put(sessionId, roomId);
        broadcastRoomList();
        return roomId;
    }

    public void joinRoom(String roomId, String sessionId, String playerName, String token) {
        GameService game = getRoom(roomId);
        game.joinGame(sessionId, playerName, token);
        sessionToRoom.put(sessionId, roomId);
        broadcastRoomList();
    }

    public GameService getRoom(String roomId) {
        GameService game = rooms.get(roomId);
        if (game == null) throw new RuntimeException("ไม่พบห้อง '" + roomId + "' กรุณาตรวจสอบรหัส");
        return game;
    }

    public void playerDisconnected(String sessionId) {
        String roomId = sessionToRoom.remove(sessionId);
        if (roomId == null) return;
        GameService game = rooms.get(roomId);
        if (game == null) return;
        game.playerDisconnected(sessionId);
        if (game.isEmpty()) {
            rooms.remove(roomId);
            log.info("Room {} removed (empty)", roomId);
        }
        broadcastRoomList();
    }

    public List<Room> getRoomList() {
        return rooms.entrySet().stream()
                .map(e -> e.getValue().getRoomInfo(e.getKey()))
                .collect(Collectors.toList());
    }

    public void leaveRoom(String roomId, String playerName) {
        GameService game = getRoom(roomId);
        game.leaveGame(playerName);
        if (game.isEmpty()) {
            rooms.remove(roomId);
            log.info("Room {} removed (empty after leave)", roomId);
        }
        broadcastRoomList();
    }

    public void broadcastRoomList() {
        messagingTemplate.convertAndSend("/topic/lobby", getRoomList());
    }

    private String generateRoomId() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random random = new Random();
        String id;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
            id = sb.toString();
        } while (rooms.containsKey(id));
        return id;
    }
}