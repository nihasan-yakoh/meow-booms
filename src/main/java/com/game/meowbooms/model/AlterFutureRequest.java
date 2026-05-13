package com.game.meowbooms.model;

import lombok.Data;

import java.util.List;

@Data
public class AlterFutureRequest {
    private String roomId;
    private String playerName;
    private List<String> newCardOrder;
}
