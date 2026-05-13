package com.game.meowbooms.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Room {
    private String roomId;
    private String roomName;
    private String hostName;
    private int playerCount;
    private boolean gameStarted;
}