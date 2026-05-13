package com.game.meowbooms.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Player {
    private String id;
    private String name;
    private String token;
    private List<Card> hand = new ArrayList<>(); // การ์ดในมือ
    private boolean isExploded; // สถานะว่าโดนระเบิดตายหรือยัง
    private boolean online;
    private boolean isHost; // 🔥 เพิ่ม: สถานะหัวห้อง
    private boolean isSpectator; // 🔥 เพิ่ม: สถานะคนดู

    private boolean isReady;

    public Player(String id, String name, String token) {
        this.id = id;
        this.name = name;
        this.token = token;
        this.online = true;
        this.isExploded = false;
        this.isHost = false;
        this.isSpectator = false;
        this.isReady = false;
    }
}
