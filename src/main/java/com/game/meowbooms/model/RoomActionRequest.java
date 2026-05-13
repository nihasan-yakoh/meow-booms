package com.game.meowbooms.model;

import lombok.Data;

@Data
public class RoomActionRequest {
    private String roomId;
    private String playerName;
}