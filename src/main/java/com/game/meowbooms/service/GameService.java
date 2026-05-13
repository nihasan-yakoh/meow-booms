package com.game.meowbooms.service;

import com.game.meowbooms.model.Card;
import com.game.meowbooms.model.CardType;
import com.game.meowbooms.model.Player;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import static java.time.LocalTime.now;

@Slf4j
public class GameService {

    private final SimpMessagingTemplate messagingTemplate;
    private final String roomTopic;
    private final String roomName;

    public GameService(SimpMessagingTemplate messagingTemplate, String roomId, String roomName) {
        this.messagingTemplate = messagingTemplate;
        this.roomTopic = "/topic/game/" + roomId;
        this.roomName = roomName;
    }

    private Map<String, String> sessionMap = new ConcurrentHashMap<>();

    private Map<String, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();

    private static final long AFK_TIMEOUT_SECONDS = 30;

    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> pendingTask; // ตัวเก็บ Task ที่กำลังนับถอยหลัง

    private List<Player> players = new ArrayList<>();
    private Stack<Card> deck = new Stack<>();
    private boolean isGameStarted = false;
    private int turnsLeft = 1;
    private String currentPlayerName = "";
    private String pendingActionPlayer = null; // ชื่อคนที่ต้องทำ Action (เหยื่อ)
    private String pendingActionType = null; // ประเภท Action (เช่น "GIVE_CARD")
    private String actionInitiator = null; // ใครเป็นคนต้นเรื่อง (คนขอ)

    private Player pendingActionSourcePlayer; // ใครเป็นคนใช้
    private CardType pendingActionCardType; // การ์ดอะไร
    private String pendingActionTargetName; // เป้าหมายคือใคร
    private String pendingActionRequestedType; // (สำหรับ 3 ใบ)
    private boolean isActionNoped = false; // สถานะว่าโดน NOPE ไปหรือยัง? (จำนวนคู่/คี่)
    private int pendingComboMode = 0;

    private List<Card> tempFutureCards = new ArrayList<>();
    private Stack<Card> discardPile = new Stack<>();

    private List<String> gameLogs = new ArrayList<>();

    private void logMsg(String message) {
        String timeStamp = now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String fullMessage = "[" + timeStamp + "] " + message;

        // เก็บลง List (เอาอันใหม่ขึ้นก่อน จะได้เห็นล่าสุดข้างบน)
        gameLogs.add(0, fullMessage);

        if (gameLogs.size() > 50) {
            gameLogs.remove(gameLogs.size() - 1);
        }

        log.info(fullMessage);
    }

    public Player joinGame(String sessionId, String name, String token) {
        if (name.length() > 20) {
            throw new RuntimeException("ชื่อพ่อชื่อแม่อ่ะยาวขนาดนี้ < 20 นะครับ");
        }

        Player existingPlayer = players.stream()
                .filter(p -> p.getToken().equals(token))
                .findFirst()
                .orElse(null);

        if (existingPlayer != null) {

            logMsg("♻️ " + existingPlayer.getName() + " กลับมาเชื่อมต่อใหม่! (ยกเลิกระเบิดเวลา)");

            if (disconnectTimers.containsKey(existingPlayer.getName())) {
                disconnectTimers.get(existingPlayer.getName()).cancel(false);
                disconnectTimers.remove(existingPlayer.getName());
            }

            sessionMap.values().removeIf(val -> val.equals(existingPlayer.getName()));
            sessionMap.put(sessionId, existingPlayer.getName());
            existingPlayer.setOnline(true);

            if (!existingPlayer.getName().equals(name)) {
                logMsg(existingPlayer.getName() + " เปลี่ยนชื่อเป็น " + name);
                existingPlayer.setName(name);
            }

            validateHost();

            messagingTemplate.convertAndSend(roomTopic, getGameState());
            return existingPlayer;
        }

        if (players.stream().anyMatch(p -> p.getName().equals(name))) {
            throw new RuntimeException("ชื่อ '" + name + "' มีคนใช้แล้ว! กรุณาเปลี่ยนชื่อ");
        }

        Player newPlayer = new Player(String.valueOf(players.size() + 1), name, token);
        newPlayer.setOnline(true);
        if (players.isEmpty()) {
            newPlayer.setHost(true);
            logMsg("👑 " + name + " ได้เป็นหัวห้อง!");
        }
        if (isGameStarted) {
            newPlayer.setSpectator(true);
            logMsg("👀 " + name + " เข้ามาดูเกม (รอรอบถัดไป)");
        } else {
            logMsg("✅ " + name + " เข้าร่วมห้อง");
        }
        players.add(newPlayer);
        sessionMap.put(sessionId, name); // จำ Session
        messagingTemplate.convertAndSend(roomTopic, getGameState());
        return newPlayer;
    }

    public void kickPlayer(String hostName, String targetName) {
        Player host = getPlayerByName(hostName);
        if (host == null || !host.isHost()) throw new RuntimeException("คุณไม่ใช่หัวห้อง! ไม่มีสิทธิ์เตะ");
        if (hostName.equals(targetName)) throw new RuntimeException("จะเตะตัวเองทำไม?");

        Player target = getPlayerByName(targetName);
        if (target == null) return;

        // ลบออกจากระบบ
        players.remove(target);
        sessionMap.values().removeIf(val -> val.equals(targetName));

        // ถ้าเตะตอนเล่นอยู่ ให้ถือว่าตาย
        if (isGameStarted && !target.isSpectator() && !target.isExploded()) {
            target.setExploded(true);
            ensureGameBalance();
            if (currentPlayerName.equals(targetName)) {
                nextTurn();
            }
            checkForWinner();
        }

        logMsg("👢 " + targetName + " ถูกเตะออกจากห้อง!");
        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private Player getCurrentPlayer() {
        if (currentPlayerName.isEmpty()) return null;
        return getPlayerByName(currentPlayerName);
    }

    public void playerDisconnected(String sessionId) {
        String name = sessionMap.get(sessionId);
        if (name == null) return;

        sessionMap.remove(sessionId);

        Player p = getPlayerByName(name);
        if (p == null) return;

        p.setOnline(false);

        if (p.isHost()) {
            p.setHost(false); // ปลดตำแหน่งก่อน
            validateHost();   // หาคนใหม่เสียบ
        }

        if (!isGameStarted) {
            players.remove(p); // ลบออกจาก List

            // ลบ Timer เผื่อมีค้าง (กันเหนียว)
            if (disconnectTimers.containsKey(name)) {
                disconnectTimers.get(name).cancel(false);
                disconnectTimers.remove(name);
            }

            logMsg("👋 " + name + " ออกจากห้อง Lobby");

            // แจ้งทุกคนให้รีเฟรชหน้าจอ (ชื่อเพื่อนจะหายไป)
            messagingTemplate.convertAndSend(roomTopic, getGameState());
            return;
        }

        if (p.isExploded()) return;

        logMsg("🔌 " + name + " หลุดการเชื่อมต่อ! (จะระเบิดตัวเองใน " + AFK_TIMEOUT_SECONDS + " วิ)");

        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            handleAFKTimeout(name);
        }, AFK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        disconnectTimers.put(name, timer);

        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private void handleAFKTimeout(String name) {
        disconnectTimers.remove(name);

        Player p = getPlayerByName(name);
        if (p == null || p.isExploded()) return;

        logMsg("💀 " + name + " หายไปนานเกิน! ระบบจึงส่งระเบิดให้กิน");

        p.setExploded(true);

        ensureGameBalance();

        if (currentPlayerName.equals(name)) {
            nextTurn(); // ข้ามไปเลย
        }

        checkForWinner();

        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    public void toggleReady(String playerName) {
        if (isGameStarted) throw new RuntimeException("เกมเริ่มแล้ว กดพร้อมไม่ได้!");

        Player p = getPlayerByName(playerName);
        if (p == null) return;

        p.setReady(!p.isReady()); // สลับสถานะ

        logMsg((p.isReady() ? "✅ " : "❌ ") + p.getName() + (p.isReady() ? " พร้อมแล้ว!" : " ยกเลิกความพร้อม"));

        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    public void startGame(String requesterName) {
        Player host = getPlayerByName(requesterName);
        if (host == null || !host.isHost()) throw new RuntimeException("รอหัวห้องกดเริ่มเกม!");

        host.setReady(true);
        long readyCount = players.stream().filter(Player::isReady).count();
        if (readyCount < 2) {
            throw new RuntimeException("คนพร้อมไม่พอ! (ต้องการ 2 คนขึ้นไป)");
        }

        players.forEach(p -> {
            if (p.isReady()) {
                // ถ้าพร้อม -> ได้เล่น
                p.setSpectator(false);
                p.setExploded(false);
                p.getHand().clear();
            } else {
                // ถ้าไม่พร้อม -> เป็นคนดู
                p.setSpectator(true);
                p.setExploded(false); // กันเหนียว
                p.getHand().clear();
            }
        });

        players.removeIf(p -> !sessionMap.containsValue(p.getName()) && !p.isOnline());

        cleanupState();
        turnsLeft = 1;
        deck.clear();
        isGameStarted = true;

        List<Player> activePlayers = players.stream().filter(p -> !p.isSpectator()).toList();
        int startIndex = (int) (Math.random() * activePlayers.size());
        currentPlayerName = activePlayers.get(startIndex).getName();

        int deckMultiplier = activePlayers.size() > 10 ? 2 : 1;

        List<Card> safeCards = new ArrayList<>();
        for (int i = 0; i < 5 * deckMultiplier; i++) safeCards.add(Card.of(CardType.ATTACK));
        for (int i = 0; i < 5 * deckMultiplier; i++) safeCards.add(Card.of(CardType.ATTACK_TO));
        for (int i = 0; i < 10 * deckMultiplier; i++) safeCards.add(Card.of(CardType.SKIP));
        for (int i = 0; i < 6 * deckMultiplier; i++) safeCards.add(Card.of(CardType.SEE_THE_FUTURE));
        for (int i = 0; i < 6 * deckMultiplier; i++) safeCards.add(Card.of(CardType.CHANGE_THE_FUTURE));
        for (int i = 0; i < 6 * deckMultiplier; i++) safeCards.add(Card.of(CardType.SHUFFLE));
        for (int i = 0; i < 7 * deckMultiplier; i++) safeCards.add(Card.of(CardType.UNDER));
        for (int i = 0; i < 6 * deckMultiplier; i++) safeCards.add(Card.of(CardType.FAVOR));
        for (int i = 0; i < 9 * deckMultiplier; i++) safeCards.add(Card.of(CardType.NOPE));

        for (int i = 0; i < 7 * deckMultiplier; i++) safeCards.add(Card.of(CardType.MEOW_A));
        for (int i = 0; i < 7 * deckMultiplier; i++) safeCards.add(Card.of(CardType.MEOW_B));
        for (int i = 0; i < 7 * deckMultiplier; i++) safeCards.add(Card.of(CardType.MEOW_C));
        for (int i = 0; i < 7 * deckMultiplier; i++) safeCards.add(Card.of(CardType.MEOW_D));
        for (int i = 0; i < 7 * deckMultiplier; i++) safeCards.add(Card.of(CardType.MEOW_E));

        for (int i = 0; i < 6 * deckMultiplier; i++) safeCards.add(Card.of(CardType.MEOW));

        int extraDefuse = (10 * deckMultiplier) - activePlayers.size();
        for (int i = 0; i < extraDefuse; i++) {
            safeCards.add(Card.of(CardType.DEFUSE));
        }

        // สับเฉพาะการ์ดปลอดภัยก่อนแจก
        Collections.shuffle(safeCards);

        for (Player p : activePlayers) {
            p.getHand().add(Card.of(CardType.DEFUSE));
//            p.getHand().add(Card.of(CardType.SEE_THE_FUTURE));
//            p.getHand().add(Card.of(CardType.CHANGE_THE_FUTURE));
//            p.getHand().add(Card.of(CardType.MEOW));
//            p.getHand().add(Card.of(CardType.MEOW));
//            p.getHand().add(Card.of(CardType.MEOW_A));
//            p.getHand().add(Card.of(CardType.MEOW_B));
//            p.getHand().add(Card.of(CardType.MEOW_C));
//            p.getHand().add(Card.of(CardType.MEOW_D));
//            p.getHand().add(Card.of(CardType.MEOW_E));
//            p.getHand().add(Card.of(CardType.ATTACK));
//            p.getHand().add(Card.of(CardType.ATTACK_TO));
//            p.getHand().add(Card.of(CardType.CHANGE_THE_FUTURE));
//            p.getHand().add(Card.of(CardType.SEE_THE_FUTURE));
//            p.getHand().add(Card.of(CardType.NOPE));
//            p.getHand().add(Card.of(CardType.NOPE));
//            p.getHand().add(Card.of(CardType.NOPE));
//            p.getHand().add(Card.of(CardType.NOPE));

            // 2.2 แจกการ์ดปลอดภัยอีก 4 ใบ
            for (int k = 0; k < 4; k++) {
                if (!safeCards.isEmpty()) {
                    p.getHand().add(safeCards.remove(0)); // ดึงออกจาก List ชั่วคราวไปใส่ในมือ
                }
            }
            sortHand(p.getHand());
        }

        // --- Step 3: ประกอบระเบิดเข้ากอง (Deck Building) ---
        // เอาการ์ดปลอดภัยที่เหลือจากการแจก ใส่กลับเข้ากองกลาง
        deck.addAll(safeCards);

        int bombCount = activePlayers.size() - 1;
//        int bombCount = 100;
        for (int i = 0; i < bombCount; i++) deck.add(Card.of(CardType.BOOMS)); //ระเบิด

        Collections.shuffle(deck);

        gameLogs = new ArrayList<>();

        String deckInfo = deckMultiplier > 1 ? " 🃏×2 สองกองไพ่!" : "";
        logMsg("🎉 เริ่มเกม!" + deckInfo + " (ในกองมีระเบิด " + bombCount + " ใบ, Defuse พิเศษ " + extraDefuse + " ใบ)");

        Player player = getCurrentPlayer();
        if (player == null) {
            return;
        }

        logMsg("👉 ผู้โชคดีได้เริ่มคนแรกคือ: " + player.getName());
        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    public Object getGameState() {
        return new Object() {
            public List<Player> allPlayers = players;
            public int deckSize = deck.size();
            public List<Card> discardPile = GameService.this.discardPile;
            public String currentTurn = (GameService.this.isGameStarted) ? getCurrentPlayer().getName() : null;
            public List<String> logs = gameLogs;
            public int turnsToPlay = turnsLeft;
            public String pendingActionPlayer = GameService.this.pendingActionPlayer; // ต้องใช้ GameService.this อ้างถึงตัวแปร class
            public String pendingActionType = GameService.this.pendingActionType;
            public boolean isGameStarted = GameService.this.isGameStarted;
            public List<Card> cardList = Arrays.stream(CardType.values())
                    .map(Card::of)
                    .toList();
        };
    }

    public void resetGame(String requesterName) {
        Player req = getPlayerByName(requesterName);
        boolean hasHost = players.stream().anyMatch(Player::isHost);
        if (hasHost && (req == null || !req.isHost())) throw new RuntimeException("คุณไม่ใช่หัวห้อง!");
//        players.clear();
        deck.clear();
        disconnectTimers.values().forEach(t -> t.cancel(false));
        disconnectTimers.clear();
        discardPile.clear();
//        sessionMap.clear();

        players.forEach(p -> {
            p.getHand().clear();
            p.setExploded(false);
            p.setSpectator(false);
            p.setReady(false); // 🔥 รีเซ็ตให้ทุกคนต้องกดพร้อมใหม่
        });

        this.pendingActionPlayer = null;
        this.pendingActionType = null;

        cleanupState();

        isGameStarted = false;

        gameLogs.clear();

        logMsg("🔄 ยกเลิกเกม! กลับสู่ Lobby");
        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private boolean isActionPending() {
        return pendingTask != null && !pendingTask.isDone();
    }

    public void drawCard(String playerName) {
        // 1. เช็คว่าเกมเริ่มหรือยัง
        if (!isGameStarted) throw new RuntimeException("เกมยังไม่เริ่มใจเย็นๆ ไอสอง~");

        if (isActionPending()) {
            throw new RuntimeException("กรุณารอ Action ปัจจุบันให้จบก่อน! (รอ 5 วิ)");
        }

        // 2. เช็คว่าเป็นเทิร์นของคนนี้จริงไหม
        if (!playerName.equals(currentPlayerName)) {
            throw new RuntimeException("ไม่ใช่ตาของคุณ! (ตอนนี้ตาของ " + currentPlayerName + ")");
        }

        Player player = getCurrentPlayer();

        // 3. จั่วการ์ดจากกอง
        Card drawnCard = deck.pop();
        player.getHand().add(drawnCard);
        sortHand(player.getHand());
        logMsg("🎴 " + player.getName() + " จั่วการ์ด 1 ใบ");

        if (drawnCard.getType() == CardType.BOOMS) {
            handleBoom(player, drawnCard);
        } else {
            turnsLeft--;

            logMsg(player.getName() + " จั่วการ์ด... ปลอดภัย");
            if (turnsLeft <= 0) {
                nextTurn();
            } else {
                logMsg("⏳ " + player.getName() + " ยังเหลือต้องเล่นอีก " + turnsLeft + " ตา");
            }
        }

        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private void handleBoom(Player player, Card bombCard) {
        Card defuseCard = player.getHand().stream()
                .filter(c -> c.getType() == CardType.DEFUSE)
                .findFirst()
                .orElse(null);

        if (defuseCard != null) {
            player.getHand().remove(defuseCard);
            discardPile.add(defuseCard);

            logMsg("🛠️ " + player.getName() + " ใช้ Defuse! (กำลังเลือกที่ซ่อนระเบิด...)");

            this.pendingActionPlayer = player.getName();
            this.pendingActionType = "PLACE_BOMB";

            player.getHand().remove(bombCard);
            logMsg(player.getName() + " ใช้ Defuse รอดตายหวุดหวิด!");

            messagingTemplate.convertAndSend(roomTopic, getGameState());

            // เอา Defuse ออกจากเกม แล้วเอาระเบิดยัดกลับเข้ากอง (สุ่มตำแหน่ง)
            // หมายเหตุ: เกมจริงคนเล่นเลือกตำแหน่งได้ แต่เพื่อความง่ายเราสุ่มเอาครับ
            // int randomIndex = (int) (Math.random() * deck.size());
            // deck.add(randomIndex, bombCard);

            // คนที่ใช้ Defuse ถือว่าจบเทิร์น
            // nextTurn();
        } else {
            // 💥 ตู้ม! ตายจริง
            player.setExploded(true);
            logMsg(player.getName() + " ระเบิดตัวแตก!");

            discardPile.add(bombCard);

            // --- เพิ่ม Logic: เช็คผู้ชนะ (Win Condition) ตรงนี้ ---
            checkForWinner();

            if (isGameStarted) {
                turnsLeft--;
                if (turnsLeft <= 0) {
                    nextTurn();
                } else {
                    logMsg("💥" + player.getName() + " ตายห่าไปแล้วแต่ยังเหลือต้องเล่นอีก " + turnsLeft + " ตาส่งต่อให้คนถัดไป~~");
                    nextTurn();
                }
            }
        }
    }

    public void placeBomb(String playerName, int targetIndex) {
        if (!playerName.equals(pendingActionPlayer) || !"PLACE_BOMB".equals(pendingActionType)) {
            throw new RuntimeException("ไม่ได้อยู่ในขั้นตอนวางระเบิด");
        }

        if (targetIndex < 0) targetIndex = 0;
        if (targetIndex > deck.size()) targetIndex = deck.size();

        deck.add(targetIndex, Card.of(CardType.BOOMS));

        logMsg("🤫 " + playerName + " ซ่อนระเบิดลงในกองแล้ว!");

        pendingActionPlayer = null;
        pendingActionType = null;

        turnsLeft--;

        if (turnsLeft > 0) {
            logMsg("😅 " + playerName + " รอดตาย! แต่ยังเหลือต้องเล่นอีก " + turnsLeft + " ตา");
            // ไม่เรียก nextTurn() ให้เล่นต่อ
        } else {
            nextTurn(); // หมดโควต้าแล้ว เปลี่ยนคน
        }

        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    // ฟังก์ชันใหม่: หาผู้ชนะ
    private void checkForWinner() {
        // นับจำนวนคนที่ยังรอด (isExploded == false)
        long survivors = players.stream().filter(p -> !p.isExploded()).filter(p -> !p.isSpectator()).count();

        if (survivors == 1) {
            // เจอผู้ชนะแล้ว!
            Player winner = players.stream()
                    .filter(p -> !p.isExploded())
                    .filter(p -> !p.isSpectator())
                    .toList().getFirst();

            isGameStarted = false; // จบเกม
            logMsg("🎉 จบเกม! ผู้ชนะคือ " + winner.getName());

            // (Optional) อาจจะล้างกองไพ่ทิ้ง หรือประกาศชื่อผู้ชนะไปที่ Frontend ให้ขึ้นตัวใหญ่ๆ
            deck.clear();
            messagingTemplate.convertAndSend(roomTopic, getGameState());
        }

    }

    private void nextTurn() {

        List<Player> activePlayers = players.stream().filter(p -> !p.isSpectator()).toList();

        if (activePlayers.isEmpty()) return;

        cleanupState();

        ensureGameBalance();

        int currentIndex = -1;
        for (int i = 0; i < activePlayers.size(); i++) {
            if (activePlayers.get(i).getName().equals(currentPlayerName)) {
                currentIndex = i;
                break;
            }
        }

        int nextIndex = currentIndex;
        for (int i = 0; i < activePlayers.size(); i++) {
            nextIndex = (nextIndex + 1) % activePlayers.size(); // ขยับไปข้างหน้า (วนกลับมา 0 ถ้าเกิน)

            Player candidate = activePlayers.get(nextIndex);
            if (!candidate.isExploded()) {
                // เจอแล้ว! คนนี้แหละคือคนต่อไป
                currentPlayerName = candidate.getName();

                // Reset ค่าต่างๆ
                if (turnsLeft <= 0) {
                    turnsLeft = 1;
                }
                // ถ้ามี Attack stack ก็คูณไป (ตาม logic เดิมของคุณ)

                logMsg("👉 ตาของ: " + currentPlayerName);

                // แจ้งเตือน Frontend
                messagingTemplate.convertAndSend(roomTopic, getGameState());
                return;
            }
        }
    }

    public void playCard(String playerName, List<Integer> cardIndices, String targetPlayerName, String requestedCardType) {
        if (!isGameStarted) throw new RuntimeException("เกมยังไม่เริ่มใจเย็นๆ ไอสอง~");

        Player requester = getPlayerByName(playerName);
        if (requester == null) throw new RuntimeException("ไม่พบผู้เล่น");

        if (requester.isExploded()) {
            throw new RuntimeException("คุณตายห่าแล้ว! (ผีห้ามเล่นการ์ด 👻)");
        }

        if (isActionPending()) {
            if(cardIndices.size() != 1 || requester.getHand().get(cardIndices.getFirst()).getType() != CardType.NOPE) {
                throw new RuntimeException("รอ Action จบก่อน");
            }
            // ถ้าเป็น NOPE ปล่อยให้ผ่านไปทำงานข้างล่าง
        } else {
            if (this.pendingActionType != null) throw new RuntimeException("🚫 ห้ามเล่นแทรก!");
        }

        if (targetPlayerName != null) {
            Player target = getPlayerByName(targetPlayerName);

            if (target == null) {
                throw new RuntimeException("ไม่พบผู้เล่นเป้าหมาย!");
            }

            // ☠️ เช็คว่าศพหรือไม่?
            if (target.isExploded()) {
                throw new RuntimeException("คนนี้ตายไปแล้ว! จะไปปล้นศพไม่ได้นะ (ผิดผี!)");
            }
        }

        Player currentPlayer = getCurrentPlayer();

        if (cardIndices.isEmpty()) throw new RuntimeException("เลือกการ์ดด้วย");

        int firstIndex = cardIndices.getFirst();
        if (firstIndex >= requester.getHand().size()) throw new RuntimeException("การ์ดผิดใบ!");

        Card cardToCheck = requester.getHand().get(firstIndex);

        if (cardToCheck.getType() == CardType.NOPE) {
            if (pendingTask == null || pendingTask.isDone()) {
                throw new RuntimeException("ไม่มีอะไรให้ NOPE! (หรือหมดเวลาไปแล้ว)");
            }
        }
        else {
            if (!currentPlayer.getName().equals(playerName)) {
                throw new RuntimeException("ไม่ใช่เทิร์นของเอ๋ง! (ตอนนี้ตาของ " + currentPlayer.getName() + ")");
            }

            // เพิ่ม: ถ้ามี Action ค้างอยู่ (Timer นับอยู่) ห้ามคนเจ้าของเทิร์นเล่นการ์ดแทรก (ยกเว้นกด Nope ตัวเอง)
            if (isActionPending()) {
                throw new RuntimeException("รอ Action เก่าจบก่อน หรือกด NOPE เพื่อยกเลิก");
            }
        }

        cardIndices.sort(Collections.reverseOrder());

        List<Card> cardsToPlay = new ArrayList<>();
        for (int idx : cardIndices) {
            if (idx >= requester.getHand().size()) throw new RuntimeException("เลือกการ์ดผิดใบ!");
            cardsToPlay.add(requester.getHand().get(idx));
        }

        if (cardsToPlay.size() == 1) {
            Card cardToPlay = cardsToPlay.getFirst();

            // กฎ: ห้ามใช้ Defuse หรือ Exploding Kitten เล่น (Defuse ทำงานอัตโนมัติเมื่อระเบิด)
            if (cardToPlay.getType() == CardType.DEFUSE || cardToPlay.getType() == CardType.BOOMS) {
                throw new RuntimeException("การ์ดใบนี้กดใช้ไม่ได้!");
            }

            if (cardToPlay.getType().toString().startsWith("MEOW")) {
                throw new RuntimeException("การ์ดแมวใช้ใบเดียวไม่ได้! ต้องใช้เป็นคู่ (2, 3, 5 ใบ)");
            }

            if (pendingActionPlayer != null) {
                throw new RuntimeException("รอ " + pendingActionPlayer + " ส่งการ์ดอยู่ อย่าเพิ่งใจร้อน!");
            }

            requester.getHand().remove((int)cardIndices.getFirst());

            discardPile.add(cardToPlay);

            logMsg("🃏 " + requester.getName() + " ใช้การ์ด: " + cardToPlay.getName());

            if (isNopeable(cardToPlay.getType())) {
                pendingComboMode = 0; // เป็นการ์ดใบเดียว
                scheduleAction(requester, cardToPlay.getType(), targetPlayerName, null);
            }

            else if (cardToPlay.getType() == CardType.NOPE) {
                handleNopeCard(requester);
            }

            else {
                // 2. ทำงานตามผลของการ์ด
                executeCardEffect(cardToPlay.getType(), playerName, targetPlayerName);
            }

        }

        else if (cardsToPlay.size() == 2) {
            Card c1 = cardsToPlay.getFirst();
            Card c2 = cardsToPlay.get(1);

            boolean isC1Meow = c1.getType().name().startsWith("MEOW");
            boolean isC2Meow = c2.getType().name().startsWith("MEOW");

            if (!isC1Meow || !isC2Meow) {
                throw new RuntimeException("ต้องใช้การ์ดเมี๊ยวเท่านั้นในการทำ Combo!");
            }

            boolean isValidPair = false;

            if (c1.getType() == c2.getType()) {
                isValidPair = true;
            }
            else if (c1.getType() == CardType.MEOW || c2.getType() == CardType.MEOW) {
                isValidPair = true;
            }


            if (!isValidPair) {
                throw new RuntimeException("การ์ดแมวคู่นี้จับคู่กันไม่ได้! (ต้องเหมือนกัน หรือใช้คู่กับ 'เมี๊ยวๆ')");
            }
            if (targetPlayerName == null || targetPlayerName.equals(playerName)) {
                throw new RuntimeException("ต้องระบุคนที่จะขโมย!");
            }
            for (int idx : cardIndices) {
                requester.getHand().remove(idx);
            }

            discardPile.addAll(cardsToPlay);

            logMsg("👯 " + requester.getName() + " ใช้คู่ " + c1.getName() + " + " + c2.getName() + " สุ่มขโมยการ์ดจาก " + targetPlayerName);
            pendingComboMode = 2;
//            performRandomSteal(requester, targetPlayerName);
            scheduleAction(requester, c1.getType(), targetPlayerName, requestedCardType);
        }
        else if (cardsToPlay.size() == 3) {
            Card c1 = cardsToPlay.getFirst();
            Card c2 = cardsToPlay.get(1);
            Card c3 = cardsToPlay.get(2);

            boolean isC1Meow = c1.getType().name().startsWith("MEOW");
            boolean isC2Meow = c2.getType().name().startsWith("MEOW");
            boolean isC3Meow = c3.getType().name().startsWith("MEOW");

            if (!isC1Meow || !isC2Meow || !isC3Meow) {
                throw new RuntimeException("ต้องใช้การ์ดเมี๊ยวเท่านั้นในการทำ Combo!");
            }

            boolean hasWildcard =
                    c1.getType() == CardType.MEOW ||
                    c2.getType() == CardType.MEOW ||
                    c3.getType() == CardType.MEOW;

            boolean isValidCombo;

            if (hasWildcard) {
                // เอาเฉพาะใบที่ไม่ใช่ MEOW มาเทียบกัน
                CardType baseType = null;

                for (Card c : cardsToPlay) {
                    if (c.getType() != CardType.MEOW) {
                        if (baseType == null) {
                            baseType = c.getType();
                        } else if (c.getType() != baseType) {
                            isValidCombo = false;
                            throw new RuntimeException("Combo แมวไม่ถูกต้อง!");
                        }
                    }
                }
                isValidCombo = true;
            } else {
                // ไม่มี wildcard → ต้องเหมือนกันทั้ง 3 ใบ
                isValidCombo =
                        c1.getType() == c2.getType() &&
                                c2.getType() == c3.getType();
            }

            if (!isValidCombo) {
                throw new RuntimeException("การ์ดแมวคู่นี้จับคู่กันไม่ได้! (ต้องเหมือนกัน หรือใช้คู่กับ 'เมี๊ยวๆ')");
            }

            if (targetPlayerName == null || targetPlayerName.equals(playerName)) {
                throw new RuntimeException("ต้องระบุคนที่จะขโมย!");
            }
            if (requestedCardType == null || requestedCardType.isEmpty()) {
                throw new RuntimeException("ต้องระบุชื่อการ์ดที่อยากได้!");
            }

            for (int idx : cardIndices) {
                requester.getHand().remove(idx);
            }

            discardPile.addAll(cardsToPlay);

            logMsg("🕵️‍♂️ " + requester.getName() + " ใช้ตอง " + c1.getName() + " + " + c2.getName() + " + " + c3.getName() + " ขอการ์ดจาก " + targetPlayerName);

            pendingComboMode = 3; // จำว่าเป็น Combo 3 ใบ
            scheduleAction(requester, c1.getType(), targetPlayerName, requestedCardType);
        } else if (cardsToPlay.size() == 5) {
            for (Card c : cardsToPlay) {
                if (!c.getType().name().startsWith("MEOW")) {
                    throw new RuntimeException("ต้องใช้การ์ดเมี๊ยวเท่านั้นในการทำ Combo!");
                }
            }

            Set<CardType> nonWildSet = new HashSet<>();

            for (Card c : cardsToPlay) {
                if (c.getType() != CardType.MEOW) {
                    if (!nonWildSet.add(c.getType())) {
                        throw new RuntimeException("Combo 5 ใบ: การ์ดปกติต้องห้ามซ้ำประเภทกัน! (ใช้ เมี๊ยว ๆ มาเติมได้)");
                    }
                }
            }

            if (discardPile.isEmpty()) {
                throw new RuntimeException("กองทิ้งว่างเปล่า! ไม่มีอะไรให้เก็บ");
            }

            for (int idx : cardIndices) requester.getHand().remove(idx);

            discardPile.addAll(cardsToPlay);

            logMsg("🖐️ " + requester.getName() + " ลง 5 ใบต่างกัน! (ขอขุดศพจากกองทิ้ง)");

            pendingActionPlayer = playerName;
            pendingActionType = "PICK_DISCARD";

            // ส่งรายชื่อการ์ดในกองทิ้งไปให้เลือก (แปลงเป็น List String)
            // ส่งเฉพาะใบที่น่าสนใจ (หรือส่งทั้งหมดก็ได้ถ้าไม่เยอะ)
            List<String> discardList = new ArrayList<>();

            // ส่ง index คู่กับชื่อไปด้วยเผื่อมีไพ่ซ้ำกันเยอะๆ จะได้ระบุถูกใบ
            // format: "INDEX:TYPE"
            for (int i = 0; i < discardPile.size(); i++) {
                discardList.add(i + ":" + discardPile.get(i).getType());
            }

            pendingComboMode = 5; // จำว่าเป็น Combo 5 ใบ
            // ใช้ CardType.MEOW หรืออะไรก็ได้เป็นตัวแทน (เพราะ 5 ใบอาจคละกัน)
            scheduleAction(requester, CardType.MEOW, null, null);

//            String msg = "PICK_DISCARD|" + UUID.randomUUID().toString() + "|" + playerName + "|" + String.join(",", discardList);
//            gameLogs.add(0, msg);
        }

        else {
            throw new RuntimeException("จำนวนการ์ดไม่ถูกต้อง (รองรับแค่ 1 ถึง 3 และ 5 ใบ)");
        }
        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private void executeCardEffect(CardType type, String playerName, String targetPlayerName) {
        switch (type) {
            case SKIP :
                turnsLeft--;
                if (turnsLeft == 0) {
                    nextTurn();
                    logMsg(getCurrentPlayer().getName() + " ใช้ Skip รอดตัวไป!");
                } else {
                    logMsg("เหลือต้องเล่นอีก " + turnsLeft + " ครั้ง");
                }
                break;

            case ATTACK:
                logMsg("⚔ ATTACK!!! คนต่อไปโดนหนักแน่");
                int attacksToPass;
                if (turnsLeft == 1) {
                    // กรณีโจมตีปกติ: คนต่อไปโดน 2 ที
                    attacksToPass = 2;
                } else {
                    // กรณีโดน Attack มาแล้วสวนกลับ (Stacking): โยนของเก่า + 2
                    attacksToPass = turnsLeft + 2;
                }

                turnsLeft = 1; // รีเซ็ตของตัวเองก่อนผ่าน (เพราะเราไม่ได้เล่นแล้ว)
                logMsg("⚔️ " + getCurrentPlayer().getName() + " โจมตี!! (ส่งต่อ " + attacksToPass + " เทิร์น)");

                nextTurn(); // เปลี่ยนคน

                turnsLeft = attacksToPass;
                logMsg("😱 " + getCurrentPlayer().getName() + " โดนโจมตี! ต้องเล่น " + turnsLeft + " รอบ!");
                break;

            case SHUFFLE:
                Collections.shuffle(deck);
                logMsg("🔀 " + getCurrentPlayer().getName() + " สับกองการ์ดแล้ว! (ลำดับเปลี่ยนหมด)");
                break;

            case SEE_THE_FUTURE:
                // อันนี้ยากหน่อย ต้องส่งข้อมูลกลับไปแค่คนกด เดี๋ยวค่อยทำ
                int deckSize = deck.size();
                int count = Math.min(3, deckSize);

                List<Map<String, Object>> cardNames = new ArrayList<>();
                for (int i=0; i<count; i++){
                    Card c = deck.get(deckSize - 1 - i);
                    Map<String, Object> card = new HashMap<>();
                    card.put("type", c.getType().toString());
                    card.put("name", c.getName());
                    cardNames.add(card);
                }

                logMsg("👀 " + getCurrentPlayer().getName() + " แอบดูอนาคต...");

                // ส่งข้อมูลลับ (Private Message) กลับไปบอกคนกด
                // วิธีแบบง่าย: เก็บใส่ตัวแปร global ชั่วคราว หรือส่งผ่าน Exception/Return พิเศษ
                // แต่เพื่อให้ง่ายกับ Code เดิม เราจะส่งผ่าน Log พิเศษที่ Frontend จะดักจับเอง

                sendPrivateFutureInfo(getCurrentPlayer().getName(), cardNames);
                break;

            case FAVOR:
                if (targetPlayerName == null || targetPlayerName.equals(getCurrentPlayer().getName())) throw new RuntimeException("ต้องระบุชื่อคนที่จะขอการ์ดด้วย!");

                pendingActionPlayer = targetPlayerName;
                pendingActionType = "GIVE_CARD";
                actionInitiator = playerName;

                logMsg("🫴 " + playerName + " ใช้ Favor ขอการ์ดจาก " + targetPlayerName);
                logMsg("⏳ รอ " + targetPlayerName + " เลือกการ์ด...");
                break;

            case ATTACK_TO:
                if (targetPlayerName == null) throw new RuntimeException("ต้องระบุคนที่จะโจมตี!");

                Player target = players.stream().filter(p -> p.getName().equals(targetPlayerName)).findFirst().orElseThrow();
                int targetIndex = players.indexOf(target);

                logMsg("🔫 " + playerName + " ล็อคเป้าโจมตีใส่ " + targetPlayerName + "!");

                if (turnsLeft == 1) attacksToPass = 2;
                else attacksToPass = turnsLeft + 2;

                turnsLeft = 1;

                // ย้าย Index ไปที่เป้าหมายทันที
                // (ต้องลบ 1 เพราะเดี๋ยว nextTurn() จะ +1 ให้กลายเป็น targetIndex พอดี)
                // แต่เนื่องจาก nextTurn ของเรา logic ซับซ้อน เราตั้งค่าตรงๆ แล้วข้าม nextTurn แบบปกติไปเลยดีกว่า

                currentPlayerName = target.getName();
                turnsLeft = attacksToPass;

                logMsg("😱 " + targetPlayerName + " โดนล็อคเป้า! ต้องเล่น " + turnsLeft + " รอบ!");
                break;
            case UNDER:
                logMsg("⬇️ " + playerName + " เลือกจั่วจากใต้กองการ์ด...");
                drawBottomCard(getCurrentPlayer());
                break;
            case CHANGE_THE_FUTURE:
                int deckSize1 = deck.size();
                if (deckSize1 == 0) throw new RuntimeException("การ์ดหมดกองแล้ว!");

                tempFutureCards.clear();

                int count1 = Math.min(3, deckSize1);

                for(int i=0; i<count1; i++){
                    Card c = deck.get(deckSize1 - 1 - i);
                    tempFutureCards.add(c);
                }

                pendingActionPlayer = playerName;
                pendingActionType = "ALTER_FUTURE";

                Gson gson = new Gson();

                List<Map<String, Object>> cardNames1 = new ArrayList<>();
                tempFutureCards.forEach(c -> {
                    Map<String, Object> card = new HashMap<>();
                    card.put("type", c.getType().toString());
                    card.put("name", c.getName());
                    cardNames1.add(card);
                });

                String uuid = UUID.randomUUID().toString();
                String msg = "ALTER|" + uuid + "|" + playerName + "|" + gson.toJson(cardNames1);
                gameLogs.add(0, msg);

                logMsg("⏳ รอ " + playerName + " จัดเรียงอนาคต...");
                break;
            default:
                logMsg("การ์ดใบนี้ยังไม่รองรับ");
        }

        if (type != CardType.FAVOR) {

        }
    }

    private void sendPrivateFutureInfo(String targetPlayer, List<Map<String, Object>> cards) {
        String uuid = UUID.randomUUID().toString();
        gameLogs.add(0, "SECRET|" + uuid + "|" + targetPlayer + "|" + new Gson().toJson(cards));
    }

    public void giveCard(String victimName, int cardIndex) {
        if (pendingActionPlayer == null || !pendingActionPlayer.equals(victimName)) {
            throw new RuntimeException("คุณไม่ได้ถูกขอการ์ด หรือไม่ได้อยู่ในสถานะส่งการ์ด");
        }

        Player victim = players.stream().filter(p -> p.getName().equals(victimName)).findFirst().get();
        Player initiator = players.stream().filter(p -> p.getName().equals(actionInitiator)).findFirst().get();

        Card cardToGive = victim.getHand().remove(cardIndex);

        initiator.getHand().add(cardToGive);
        sortHand(initiator.getHand());
        logMsg("🎁 " + victimName + " จำใจส่งการ์ดให้ " + initiator.getName());

        String uuid = UUID.randomUUID().toString();

        String secretMsg = "GIVE|" + uuid + "|" + initiator.getName() + "|" + victim.getName() + "|" + cardToGive.getName();
        gameLogs.add(0, secretMsg);

        // 4. เคลียร์สถานะ
        cleanupState();
        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private void performRandomSteal(Player thief, String victimName) {
        Player victim = players.stream().filter(p -> p.getName().equals(victimName)).findFirst().orElseThrow();

        if (victim.getHand().isEmpty()) {
            logMsg("😅 " + victimName + " ไม่มีการ์ดให้ขโมย!");
            return;
        }

        int randomIndex = (int) (Math.random() * victim.getHand().size());
        Card stolenCard = victim.getHand().remove(randomIndex);

        thief.getHand().add(stolenCard);
        sortHand(thief.getHand());

        logMsg("🕵️ " + thief.getName() + " ขโมยได้การ์ด 1 ใบจาก " + victimName);

        gameLogs.add(0, "STEAL|" + java.util.UUID.randomUUID() + "|" + victimName + "|" + thief.getName() + "|" + stolenCard.getName());
    }

    private void performNamedSteal(Player thief, String victimName, String cardTypeName) {
        Player victim = players.stream().filter(p -> p.getName().equals(victimName)).findFirst().orElseThrow();

        CardType targetType;
        try {
            targetType = CardType.valueOf(cardTypeName);
            Card foundCard = victim.getHand().stream().filter(c -> c.getType() == targetType).findFirst().orElse(null);

            if (foundCard != null) {
                victim.getHand().remove(foundCard);
                thief.getHand().add(foundCard);
                sortHand(thief.getHand());
                logMsg("✅ สำเร็จ! " + victimName + " มีการ์ดและถูกยึดไป");
                gameLogs.add(0, "STEAL|" + java.util.UUID.randomUUID() + "|" + victimName + "|" + thief.getName() + "|" + foundCard.getName());
            } else {
                logMsg("❌ วืด! " + victimName + " ไม่มี");
            }
        } catch (IllegalArgumentException e) {
            logMsg("System: " + thief.getName() + " เรียกชื่อการ์ดผิดๆ ถูกๆ เลยไม่ได้อะไรเลย");
        }
    }

    private void drawBottomCard(Player player) {
        if (deck.isEmpty()) return;

        Card bottomCard = deck.firstElement();
        deck.remove(0);

        if (bottomCard.getType() == CardType.BOOMS) {
            logMsg("💥 ซวยจัด! จั่วใต้กองก็เจอระเบิด!");
            handleBoom(player, bottomCard);
        } else {
            player.getHand().add(bottomCard);
            sortHand(player.getHand());
            logMsg(player.getName() + " จั่วใต้กองแล้วรอด");
            //nextTurn(); // จบเทิร์นปกติ
            turnsLeft--;
            if (turnsLeft > 0) {
                logMsg("😅 " + player.getName() + " รอดตาย! แต่ยังเหลือต้องเล่นอีก " + turnsLeft + " ตา");
                // ไม่เรียก nextTurn() ให้เล่นต่อ
            } else {
                nextTurn(); // หมดโควต้าแล้ว เปลี่ยนคน
            }
        }
    }

    public synchronized void confirmAlterFuture(String playerName, List<String> newOrderType) {
        if (!pendingActionType.equals("ALTER_FUTURE") || !pendingActionPlayer.equals(playerName)) {
            throw new RuntimeException("ไม่ได้อยู่ในสถานะเปลี่ยนอนาคต!");
        }

        if (newOrderType.size() != tempFutureCards.size()) {
            throw new RuntimeException("จำนวนการ์ดไม่ถูกต้อง!");
        }

        Stack<Card> toPush = new Stack<>();
        for (int i = newOrderType.size() - 1; i >= 0; i--) {
            String type = newOrderType.get(i);
            Card found = tempFutureCards.stream().filter(c -> c.getType().toString().equals(type)).findFirst().orElseThrow();
            toPush.push(found);
            tempFutureCards.remove(found);
        }

        int countToRemove = toPush.size();
        for(int i=0; i<countToRemove; i++) deck.pop();

        for(Card c : toPush) deck.push(c);

        cleanupState();
        logMsg("🔮 " + playerName + " เปลี่ยนแปลงอนาคตเรียบร้อย!");
        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private void discardCards(List<Card> cards) {
        discardPile.addAll(cards);
    }

    public void pickCardFromDiscard(String playerName, int discardIndex) {
        if (!pendingActionType.equals("PICK_DISCARD") || !pendingActionPlayer.equals(playerName)) {
            throw new RuntimeException("ไม่ได้อยู่ในสถานะเลือกกองทิ้ง!");
        }

        if (discardIndex < 0 || discardIndex >= discardPile.size()) {
            throw new RuntimeException("เลือกการ์ดผิดใบ!");
        }

        Player player = players.stream().filter(p -> p.getName().equals(playerName)).findFirst().get();

        // หยิบการ์ดออกมา
        Card picked = discardPile.remove(discardIndex);
        player.getHand().add(picked);
        sortHand(player.getHand());
        cleanupState();
        logMsg("🧟 " + playerName + " ขุด " + picked.getName() + " ขึ้นมาจากหลุม!");
        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private boolean isNopeable(CardType type) {
        return type == CardType.ATTACK || type == CardType.ATTACK_TO || type == CardType.SKIP ||
                type == CardType.FAVOR || type == CardType.SHUFFLE || type == CardType.SEE_THE_FUTURE ||
                type == CardType.CHANGE_THE_FUTURE || type == CardType.UNDER;
    }

    private void scheduleAction(Player player, CardType type, String target, String extraData) {
        if (pendingTask != null && !pendingTask.isDone()) {
            pendingTask.cancel(false);
        }

        pendingActionSourcePlayer = player;
        pendingActionCardType = type;
        pendingActionTargetName = target;
        pendingActionRequestedType = extraData;
        isActionNoped = false;

        String actionName;
        if (pendingComboMode == 0) {
            if (Card.of(type).getType() == CardType.ATTACK_TO || Card.of(type).getType() == CardType.FAVOR || Card.of(type).getType() == CardType.NOPE) {
                actionName = Card.of(type).getName() + " ใส่ " + pendingActionTargetName;
            } else {
                actionName = Card.of(type).getName();
            }

        } else if (pendingComboMode == 5) {
            actionName = "Combo " + pendingComboMode + " ใบ";
        } else {
            actionName = "Combo " + pendingComboMode + " ใบใส่ " + pendingActionTargetName;
        }

        logMsg("⏳ " + player.getName() + " จะใช้ " + actionName + " (รอ 5 วิ...) ใครจะค้านรีบกด NOPE!");

        gameLogs.add(0, "TIMER_START|" + java.util.UUID.randomUUID() + "|5|" + type);

        // เมื่อเวลาหมด...
        pendingTask = scheduler.schedule(this::runPendingAction, 5, TimeUnit.SECONDS);
        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private void runPendingAction() {
        if (!isActionNoped) {
            logMsg("✅ ไม่มีใครค้าน/ค้านไม่สำเร็จ! " + Card.of(pendingActionCardType).getName() + " ทำงาน!");

            try {

                if (pendingComboMode == 0) {
                    // กรณีการ์ดใบเดียวปกติ
                    logMsg("✅ " + Card.of(pendingActionCardType).getName() + " ทำงาน!");
                    executeCardEffect(pendingActionCardType, pendingActionSourcePlayer.getName(), pendingActionTargetName);
                }
                else if (pendingComboMode == 2) {
                    // กรณี Combo 2 ใบ
                    logMsg("✅ Combo 2 ใบทำงาน! (สุ่มขโมย)");
                    performRandomSteal(pendingActionSourcePlayer, pendingActionTargetName);
                }
                else if (pendingComboMode == 3) {
                    // กรณี Combo 3 ใบ
                    logMsg("✅ Combo 3 ใบทำงาน! (ขอดูการ์ด)");
                    performNamedSteal(pendingActionSourcePlayer, pendingActionTargetName, pendingActionRequestedType);
                }
                else if (pendingComboMode == 5) {
                    // กรณี Combo 5 ใบ -> เข้าสู่โหมดเลือกกองทิ้ง
                    logMsg("✅ Combo 5 ใบทำงาน! (เลือกการ์ดจากหลุม)");
                    setupPickDiscard(pendingActionSourcePlayer.getName());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            String actionName = (pendingComboMode == 0) ? pendingActionCardType.toString() : "Combo " + pendingComboMode + " ใบ";
            logMsg("❌ " + actionName + " ถูกยกเลิกด้วย NOPE!");
            // การ์ด Combo ที่ลงไปแล้ว เสียฟรีทั้งหมด! (เพราะเราลบจากมือและลงกองทิ้งไปแล้วใน playCard)
        }

        boolean isInteractiveState = "ALTER_FUTURE".equals(pendingActionType)
                || "PICK_DISCARD".equals(pendingActionType)
                || "GIVE_CARD".equals(pendingActionType)
                || "PLACE_BOMB".equals(pendingActionType);

        if (isInteractiveState) {
            // ถ้าเป็นสถานะที่ต้องรอ user ทำต่อ -> ห้ามล้าง pendingActionType/Player
            // ล้างแค่ตัวแปรของ Timer/Combo ก็พอ
            this.pendingTask = null;
            this.pendingActionSourcePlayer = null;
            this.pendingActionTargetName = null;
            this.pendingActionRequestedType = null;
            this.isActionNoped = false;
            this.pendingComboMode = 0;
        } else {
            // ถ้าเป็นการ์ดจบในตัว (Attack, Skip, Shuffle) -> ล้างทิ้งให้หมดเตรียมเริ่มใหม่
            cleanupState();
        }

        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private void handleNopeCard(Player player) {
        if (pendingTask == null || pendingTask.isDone()) {
            throw new RuntimeException("ไม่มี Action ให้ Nope! (หรือช้าไปแล้ว)");
        }

        pendingTask.cancel(false);

        isActionNoped = !isActionNoped;

        String status = isActionNoped ? "❌ ถูกคัดค้าน!" : "✅ กลับมาทำงาน!";
        logMsg("⛔ " + player.getName() + " ใช้ NOPE! -> สถานะตอนนี้: " + status);
        gameLogs.add(0, "TIMER_RESET|" + java.util.UUID.randomUUID() + "|5"); // บอก Frontend ให้รีเซ็ตเวลา

        pendingTask = scheduler.schedule(this::runPendingAction, 5, TimeUnit.SECONDS);
        messagingTemplate.convertAndSend(roomTopic, getGameState());
    }

    private void setupPickDiscard(String playerName) {
        if (discardPile.isEmpty()) {
            logMsg("😅 กองทิ้งว่างเปล่า! (เสีย Combo 5 ใบฟรี)");
            return;
        }

        pendingActionPlayer = playerName;
        pendingActionType = "PICK_DISCARD";

        List<String> discardList = new ArrayList<>();
        for (int i = 0; i < discardPile.size(); i++) {
            discardList.add(i + ":" + discardPile.get(i).getType());
        }

        String msg = "PICK_DISCARD|" + UUID.randomUUID().toString() + "|" + playerName + "|" + String.join(",", discardList);
        gameLogs.add(0, msg);
    }

    private void cleanupState() {
        this.tempFutureCards.clear(); // 🔥 ล้างการ์ดอนาคตที่ค้างอยู่ทิ้ง
        this.pendingActionPlayer = null;
        this.pendingActionType = null;
        this.pendingActionSourcePlayer = null;
        this.pendingActionCardType = null;
        this.pendingActionTargetName = null;
        this.pendingActionRequestedType = null;
        this.isActionNoped = false;
        this.pendingComboMode = 0;

        // ถ้ามี Task นับถอยหลังค้างอยู่ ให้ยกเลิกด้วย
        if (pendingTask != null && !pendingTask.isDone()) {
            pendingTask.cancel(false);
        }
        pendingTask = null;
    }

    private void sortHand(List<Card> hand) {
        hand.sort((c1, c2) -> {
            int p1 = getCardPriority(c1.getType());
            int p2 = getCardPriority(c2.getType());
            if (p1 != p2) {
                return p1 - p2; // เรียงตามความสำคัญ
            } else {
                return c1.getName().compareTo(c2.getName()); // ถ้าเท่ากัน เรียงตามชื่อ
            }
        });
    }

    private int getCardPriority(CardType type) {
        return switch (type) {
            case BOOMS -> 0;
            case DEFUSE -> 999;
            case NOPE -> 20;
            case ATTACK -> 30;
            case ATTACK_TO -> 31;
            case SKIP -> 32;
            case FAVOR -> 40;
            case SHUFFLE -> 50;
            case SEE_THE_FUTURE -> 60;
            case CHANGE_THE_FUTURE -> 61;
            case UNDER -> 62;
            case MEOW -> 100;
            case MEOW_A -> 101;
            case MEOW_B -> 102;
            case MEOW_C -> 103;
            case MEOW_D -> 104;
            case MEOW_E -> 105;
            default ->
                    999;
        };
    }

    private void ensureGameBalance() {
        if (!isGameStarted) return;

        // 1. นับจำนวนผู้เล่นที่ยังรอด
        long activePlayers = players.stream().filter(p -> !p.isExploded() && !p.isSpectator() && p.isOnline()).count();

        // ถ้าเหลือคนเดียวคือจบเกมแล้ว ไม่ต้องทำอะไร
        if (activePlayers <= 1) return;

        // 2. เป้าหมาย: ระเบิดต้องมีเท่ากับ (คนรอด - 1)
        long requiredBombs = activePlayers - 1;

        // 3. นับระเบิดที่มีอยู่จริงตอนนี้ (ในกอง + ในมือทุกคน)
        long bombsInDeck = deck.stream().filter(c -> c.getType() == CardType.BOOMS).count();
        long bombsInHand = players.stream()
                .filter(p -> !p.isExploded()) // นับเฉพาะคนเป็น
                .flatMap(p -> p.getHand().stream())
                .filter(c -> c.getType() == CardType.BOOMS)
                .count();

        long currentTotalBombs = bombsInDeck + bombsInHand;

        // 4. เปรียบเทียบและแก้ไข
        if (currentTotalBombs < requiredBombs) {
            long missing = requiredBombs - currentTotalBombs;
            logMsg("🔧 System: ตรวจพบระเบิดหายไป " + missing + " ใบ! (กำลังกู้คืน...)");

            for (int i = 0; i < missing; i++) {
                // เสกใส่จุดสุ่มในกอง
                int randomIndex = (int) (Math.random() * deck.size());
                deck.add(randomIndex, Card.of(CardType.BOOMS));
            }
        }
        else if (currentTotalBombs > requiredBombs) {
            // กรณีหายาก: ระเบิดเกิน (เช่น บัคปั๊มการ์ด) -> ลบออกจากกอง
            long excess = currentTotalBombs - requiredBombs;
            logMsg("🔧 System: ตรวจพบระเบิดเกิน " + excess + " ใบ! (กำลังลบออก...)");

            for (int i = 0; i < excess; i++) {
                Card bomb = deck.stream().filter(c -> c.getType() == CardType.BOOMS).findFirst().orElse(null);
                if(bomb != null) deck.remove(bomb);
            }
        }
    }

    private Player getPlayerByName(String name) {
        return players.stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
    }

    private void validateHost() {
        // 1. เช็คว่ามีใครเป็น Host อยู่ไหม (รวมคน Offline ด้วยก็ได้ กันเหนียว)
        boolean hasHost = players.stream().anyMatch(p -> p.isHost() && !p.isSpectator() && p.isOnline());

        if (!hasHost) {
            // 2. ถ้าไม่มี Host เลย -> หาคนที่เป็น Online มาเป็น
            Player newHost = players.stream()
                    .filter(Player::isOnline)
                    .filter(p -> !p.isSpectator())
                    .findFirst()
                    .orElse(null);

            // 3. ถ้าไม่มีคน Online เลย (ห้องร้าง) -> เอาคนแรกในลิสต์ก็ได้ (เดี๋ยวพอเขาต่อเน็ตมาก็ได้เป็นเอง)
            if (newHost == null && !players.isEmpty()) {
                newHost = players.getFirst();
            }

            if (newHost != null) {
                newHost.setHost(true);
                logMsg("👑 ระบบแต่งตั้ง " + newHost.getName() + " เป็นหัวห้อง (เนื่องจากตำแหน่งว่าง)");
            }
        }
    }

    public boolean isEmpty() {
        return players.stream().noneMatch(Player::isOnline);
    }

    public com.game.meowbooms.model.Room getRoomInfo(String roomId) {
        String host = players.stream()
                .filter(Player::isHost)
                .map(Player::getName)
                .findFirst()
                .orElse("-");
        long count = players.stream().filter(Player::isOnline).count();
        return new com.game.meowbooms.model.Room(roomId, roomName, host, (int) count, isGameStarted);
    }

}
