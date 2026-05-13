package com.game.meowbooms.model;

import lombok.Data;

@Data
public class KickRequest {
    private String roomId;
    private String hostName;
    private String targetName;
}
