package com.game.meowbooms.model;

import lombok.Data;

@Data
public class AddBotRequest {
    private String roomId;
    private String hostName;
    private String difficulty; // "EASY", "MEDIUM", "HARD"
}
