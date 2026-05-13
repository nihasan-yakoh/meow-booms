package com.game.meowbooms.model;

public enum CardType {
    BOOMS("แมวระเบิด"),
    DEFUSE("ปลดชนวน"),
    ATTACK("โจมตี"),
    ATTACK_TO("โจมตีล็อคเป้า"),
    SKIP("ข้าม"),
    FAVOR("ขอการ์ด"),
    SHUFFLE("สับกอง"),
    SEE_THE_FUTURE("ดูอนาคต"),
    CHANGE_THE_FUTURE("เปลี่ยนอนาคต"),
    NOPE("ไม่!"),
    UNDER("ใต้กอง"),
    MEOW("แมวปา"),
    MEOW_A("เมี๊ยว เอ"),
    MEOW_B("เมี๊ยว บี"),
    MEOW_C("เมี๊ยว ซี"),
    MEOW_D("เมี๊ยว ดี"),
    MEOW_E("เมี๊ยว อี");

    private final String displayName;

    CardType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
