package com.game.meowbooms.model;

import lombok.Data;

@Data
public class JoinRequest {
    private String name;
    private String token;
    private String roomId;
}
