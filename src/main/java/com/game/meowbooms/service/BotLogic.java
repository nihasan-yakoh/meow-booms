package com.game.meowbooms.service;

import com.game.meowbooms.model.Card;
import com.game.meowbooms.model.CardType;
import com.game.meowbooms.model.Player;

import java.util.*;

/**
 * Stateless utility class — all bot AI decisions live here.
 * GameService calls these methods and executes the result.
 */
public class BotLogic {

    // ───────────────────────── Action record ─────────────────────────

    public enum ActionType { DRAW, PLAY }

    public static class BotAction {
        public final ActionType type;
        public final List<Integer> cardIndices;
        public final String targetName;
        public final String requestedCardType;

        private BotAction(ActionType t, List<Integer> idx, String target, String requested) {
            this.type = t;
            this.cardIndices = idx;
            this.targetName = target;
            this.requestedCardType = requested;
        }

        public static BotAction draw() {
            return new BotAction(ActionType.DRAW, null, null, null);
        }
        public static BotAction play(List<Integer> idx) {
            return new BotAction(ActionType.PLAY, idx, null, null);
        }
        public static BotAction play(List<Integer> idx, String target) {
            return new BotAction(ActionType.PLAY, idx, target, null);
        }
        public static BotAction play(List<Integer> idx, String target, String requested) {
            return new BotAction(ActionType.PLAY, idx, target, requested);
        }
    }

    // ───────────────────────── RNG ─────────────────────────

    private static final Random RNG = new Random();

    // ───────────────────────── Main entry: decide turn ─────────────────────────

    /**
     * Called when it is the bot's turn to act.
     *
     * @param bot       the bot player
     * @param players   all players in the room
     * @param deck      current deck (Hard bot may inspect)
     * @param turnsLeft turns the bot must play this round (>1 = under attack)
     */
    public static BotAction decideTurn(Player bot, List<Player> players, Stack<Card> deck, int turnsLeft) {
        return switch (bot.getBotDifficulty()) {
            case "EASY"   -> decideEasy(bot, players, turnsLeft);
            case "MEDIUM" -> decideMedium(bot, players, deck, turnsLeft);
            case "HARD"   -> decideHard(bot, players, deck, turnsLeft);
            default       -> BotAction.draw();
        };
    }

    // ───────────────────────── Easy (Kitten) ─────────────────────────

    /** 70% draw, 30% random playable non-MEOW card. */
    private static BotAction decideEasy(Player bot, List<Player> players, int turnsLeft) {
        if (RNG.nextInt(100) < 70) return BotAction.draw();

        List<Card> hand = bot.getHand();
        List<Integer> playable = singlePlayableIndices(hand);
        if (playable.isEmpty()) return BotAction.draw();

        int idx = playable.get(RNG.nextInt(playable.size()));
        Card card = hand.get(idx);

        if (needsTarget(card.getType())) {
            String target = pickRandomTarget(bot, players);
            if (target == null) return BotAction.draw();
            return BotAction.play(List.of(idx), target);
        }
        return BotAction.play(List.of(idx));
    }

    // ───────────────────────── Medium (Cat) ─────────────────────────

    /** Strategic play without seeing the deck. Responds to risk and attack pressure. */
    private static BotAction decideMedium(Player bot, List<Player> players, Stack<Card> deck, int turnsLeft) {
        List<Card> hand = bot.getHand();
        int deckSize = deck.size();

        // Under attack → try to escape
        if (turnsLeft > 1) {
            BotAction escape = tryEscape(hand, bot, players);
            if (escape != null) return escape;
        }

        // Small deck → dangerous; try to peek/shuffle/skip
        if (deckSize <= 6) {
            int idx;
            if ((idx = find(hand, CardType.SEE_THE_FUTURE)) >= 0)    return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.SHUFFLE)) >= 0)            return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.SKIP)) >= 0)               return BotAction.play(List.of(idx));
            // Attack someone to pass the hot potato
            if ((idx = find(hand, CardType.ATTACK)) >= 0)             return BotAction.play(List.of(idx));
        }

        // Occasionally be proactive (30% ATTACK, 20% SEE_THE_FUTURE)
        if (RNG.nextInt(100) < 30) {
            int idx = find(hand, CardType.ATTACK);
            if (idx >= 0) return BotAction.play(List.of(idx));
        }
        if (RNG.nextInt(100) < 20) {
            int idx = find(hand, CardType.SEE_THE_FUTURE);
            if (idx >= 0) return BotAction.play(List.of(idx));
        }

        return BotAction.draw();
    }

    // ───────────────────────── Hard (Tiger) ─────────────────────────

    /**
     * Sees the actual deck contents (cheaty).
     * Optimal but has a ~15 % overconfidence flaw to keep it beatable.
     */
    private static BotAction decideHard(Player bot, List<Player> players, Stack<Card> deck, int turnsLeft) {
        List<Card> hand = bot.getHand();

        // Under attack → always try to escape
        if (turnsLeft > 1) {
            BotAction escape = tryEscape(hand, bot, players);
            if (escape != null) return escape;
        }

        // Check bomb proximity
        boolean bombInTop3 = bombNearTop(deck, 3);
        boolean bombInTop6 = bombNearTop(deck, 6);

        if (bombInTop3) {
            // CHANGE_THE_FUTURE: bury the bomb before it reaches us
            int idx = find(hand, CardType.CHANGE_THE_FUTURE);
            if (idx >= 0) return BotAction.play(List.of(idx));

            // SHUFFLE: randomise the danger away
            if ((idx = find(hand, CardType.SHUFFLE)) >= 0) return BotAction.play(List.of(idx));

            // SKIP: skip our own draw
            if ((idx = find(hand, CardType.SKIP)) >= 0) return BotAction.play(List.of(idx));

            // Overconfidence flaw (15%): sometimes the hard bot ignores the bomb and draws anyway
            if (RNG.nextInt(100) < 85) {
                // Pass the bomb to the weakest opponent
                idx = find(hand, CardType.ATTACK_TO);
                if (idx >= 0) {
                    String target = pickWeakestTarget(bot, players);
                    if (target != null) return BotAction.play(List.of(idx), target);
                }
                if ((idx = find(hand, CardType.ATTACK)) >= 0) return BotAction.play(List.of(idx));
            }
        }

        if (bombInTop6 && RNG.nextInt(100) < 55) {
            int idx;
            if ((idx = find(hand, CardType.SEE_THE_FUTURE)) >= 0)         return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.CHANGE_THE_FUTURE)) >= 0)       return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.ATTACK)) >= 0)                  return BotAction.play(List.of(idx));
        }

        // Proactively attack the weakest player 40 % of the time
        if (RNG.nextInt(100) < 40) {
            int idx = find(hand, CardType.ATTACK_TO);
            if (idx >= 0) {
                String target = pickWeakestTarget(bot, players);
                if (target != null) return BotAction.play(List.of(idx), target);
            }
        }

        return BotAction.draw();
    }

    // ───────────────────────── NOPE decision ─────────────────────────

    /**
     * Should the bot NOPE the pending action?
     *
     * @param bot           the bot considering NOPE
     * @param actionPlayer  who played the card
     * @param actionType    card type being played
     * @param actionTarget  intended target (may be null)
     * @param players       all players
     * @param isCurrentlyNoped current NOPE parity (true = action is currently cancelled)
     */
    public static boolean decideNope(Player bot, Player actionPlayer, CardType actionType,
                                     String actionTarget, List<Player> players, boolean isCurrentlyNoped) {
        if (bot.isExploded() || bot.isSpectator()) return false;
        boolean hasNope = bot.getHand().stream().anyMatch(c -> c.getType() == CardType.NOPE);
        if (!hasNope) return false;

        boolean isTargeted = bot.getName().equals(actionTarget);

        return switch (bot.getBotDifficulty()) {
            case "EASY" -> RNG.nextInt(100) < 15;  // 15% random

            case "MEDIUM" -> {
                // Already cancelled? Only counter-NOPE if directly targeted
                if (isCurrentlyNoped) yield isTargeted && RNG.nextInt(100) < 25;

                if (isTargeted && (actionType == CardType.ATTACK_TO || actionType == CardType.FAVOR))
                    yield RNG.nextInt(100) < 60;
                if (actionType == CardType.ATTACK && isNextInLine(bot, actionPlayer, players))
                    yield RNG.nextInt(100) < 35;
                yield RNG.nextInt(100) < 8;
            }

            case "HARD" -> {
                if (isCurrentlyNoped) yield isTargeted && RNG.nextInt(100) < 50;

                if (isTargeted && (actionType == CardType.ATTACK_TO || actionType == CardType.FAVOR))
                    yield RNG.nextInt(100) < 85;
                if (actionType == CardType.ATTACK && isNextInLine(bot, actionPlayer, players))
                    yield RNG.nextInt(100) < 65;
                // Hard bots also NOPE attacks that land on allies when it's strategically useful
                if (actionType == CardType.ATTACK && RNG.nextInt(100) < 15) yield true;
                yield RNG.nextInt(100) < 15;
            }

            default -> false;
        };
    }

    // ───────────────────────── Place-bomb decision ─────────────────────────

    /**
     * Returns the deck index at which the bot places the bomb.
     * Index 0 = bottom, deckSize = top (just drawn).
     */
    public static int decidePlaceBomb(Player bot, int deckSize) {
        if (deckSize == 0) return 0;
        return switch (bot.getBotDifficulty()) {
            case "HARD" -> {
                // Bury deep — at least 4 positions from the top
                int minDepth = Math.min(4, deckSize);
                yield RNG.nextInt(minDepth + 1); // 0…minDepth (0 = bottom)
            }
            default -> RNG.nextInt(deckSize + 1); // random
        };
    }

    // ───────────────────────── Give-card decision ─────────────────────────

    /** All difficulties: give the least valuable card (keep DEFUSE/NOPE). */
    public static int decideGiveCard(Player bot) {
        List<Card> hand = bot.getHand();
        if (hand.isEmpty()) return 0;

        // 1st priority: give a random-colour MEOW (least useful)
        for (int i = 0; i < hand.size(); i++) {
            CardType t = hand.get(i).getType();
            if (t.name().startsWith("MEOW")) return i;
        }
        // 2nd: any non-critical action card
        for (int i = 0; i < hand.size(); i++) {
            CardType t = hand.get(i).getType();
            if (t != CardType.DEFUSE && t != CardType.NOPE && t != CardType.BOOMS) return i;
        }
        // Last resort: anything except DEFUSE
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).getType() != CardType.DEFUSE) return i;
        }
        return 0;
    }

    // ───────────────────────── ALTER_FUTURE order ─────────────────────────

    /**
     * Returns the new card order for ALTER_FUTURE.
     * Index 0 = next card to be drawn (top of deck).
     */
    public static List<String> decideAlterFuture(Player bot, List<Card> futureCards) {
        List<Card> sorted = new ArrayList<>(futureCards);

        if ("HARD".equals(bot.getBotDifficulty())) {
            // Push bomb to position 2 (furthest from top)
            sorted.sort((a, b) -> {
                if (a.getType() == CardType.BOOMS) return 1;
                if (b.getType() == CardType.BOOMS) return -1;
                return 0;
            });
        } else {
            // Easy/Medium: random re-order (may accidentally help or hurt)
            Collections.shuffle(sorted);
        }

        return sorted.stream().map(c -> c.getType().toString()).toList();
    }

    // ───────────────────────── Private helpers ─────────────────────────

    /** Try to escape an attack (turnsLeft > 1): SKIP → ATTACK_TO → ATTACK. */
    private static BotAction tryEscape(List<Card> hand, Player bot, List<Player> players) {
        int idx;
        if ((idx = find(hand, CardType.SKIP)) >= 0) return BotAction.play(List.of(idx));

        idx = find(hand, CardType.ATTACK_TO);
        if (idx >= 0) {
            String target = pickWeakestTarget(bot, players);
            if (target != null) return BotAction.play(List.of(idx), target);
        }
        if ((idx = find(hand, CardType.ATTACK)) >= 0) return BotAction.play(List.of(idx));
        return null;
    }

    /** Indices of single-playable cards (no DEFUSE, BOOMS, NOPE, solo MEOW). */
    private static List<Integer> singlePlayableIndices(List<Card> hand) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < hand.size(); i++) {
            CardType t = hand.get(i).getType();
            if (t == CardType.DEFUSE || t == CardType.BOOMS || t == CardType.NOPE) continue;
            if (t.name().startsWith("MEOW")) continue; // needs combo
            result.add(i);
        }
        return result;
    }

    private static boolean needsTarget(CardType type) {
        return type == CardType.ATTACK_TO || type == CardType.FAVOR;
    }

    private static int find(List<Card> hand, CardType type) {
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).getType() == type) return i;
        }
        return -1;
    }

    private static String pickRandomTarget(Player bot, List<Player> players) {
        List<Player> targets = alivePlayers(players, bot.getName());
        if (targets.isEmpty()) return null;
        return targets.get(RNG.nextInt(targets.size())).getName();
    }

    private static String pickWeakestTarget(Player bot, List<Player> players) {
        return alivePlayers(players, bot.getName()).stream()
                .min(Comparator.comparingInt(p -> p.getHand().size()))
                .map(Player::getName)
                .orElse(null);
    }

    private static List<Player> alivePlayers(List<Player> players, String excludeName) {
        return players.stream()
                .filter(p -> !p.getName().equals(excludeName))
                .filter(p -> !p.isExploded() && !p.isSpectator())
                .toList();
    }

    private static boolean bombNearTop(Stack<Card> deck, int depth) {
        int size = deck.size();
        for (int i = 0; i < Math.min(depth, size); i++) {
            if (deck.get(size - 1 - i).getType() == CardType.BOOMS) return true;
        }
        return false;
    }

    private static boolean isNextInLine(Player bot, Player actionPlayer, List<Player> players) {
        List<Player> active = players.stream().filter(p -> !p.isExploded() && !p.isSpectator()).toList();
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).getName().equals(actionPlayer.getName())) {
                int nextIdx = (i + 1) % active.size();
                return active.get(nextIdx).getName().equals(bot.getName());
            }
        }
        return false;
    }
}
