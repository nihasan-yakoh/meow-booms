package com.game.meowbooms.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Card {
    private CardType type;
    private String name; // ชื่อที่แสดง (เผื่อไว้ใส่รายละเอียด)

    private Card(CardType type) {
        this.type = type;
        this.name = type.getDisplayName();
    }

    public static Card of(CardType type) {
        return new Card(type);
    }

    public CardType getType() {
        return type;
    }

    public String getName() {
        return name;
    }
    // อนาคตอาจจะมี String imageUrl; ไว้เก็บ path รูปภาพ
}
