package com.game.meowbooms.model;

import lombok.Data;

@Data
public class CreateRoomRequest {
    private String playerName;
    private String token;
    private String roomName;
}