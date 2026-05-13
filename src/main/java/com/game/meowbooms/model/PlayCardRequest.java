package com.game.meowbooms.model;

import lombok.Data;

import java.util.List;

@Data
public class PlayCardRequest {
    private String roomId;
    private String playerName;
    private List<Integer> cardIndices;
    private String targetPlayerName;
    private String requestedCardType;
    private int targetIndex;
}
