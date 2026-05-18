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

    /**
     * Plays like a beginner: mostly draws, occasionally panics, rarely does anything smart.
     * Fun because: unpredictable, chaotic, occasionally surprises you.
     */
    private static BotAction decideEasy(Player bot, List<Player> players, int turnsLeft) {
        List<Card> hand = bot.getHand();

        // Under attack: beginner might panic and SKIP (35%), otherwise just takes the hits
        if (turnsLeft > 1) {
            if (RNG.nextInt(100) < 35) {
                int idx = find(hand, CardType.SKIP);
                if (idx >= 0) return BotAction.play(List.of(idx));
            }
            return BotAction.draw(); // confused, stumbles into extra draws
        }

        // 70% just draw (naive/adventurous — "how bad could it be?")
        if (RNG.nextInt(100) < 70) return BotAction.draw();

        // 30%: play a random card chaotically (might help, might not)
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

    /**
     * Plays like a casual player who's done a few games:
     * aware of danger, uses attacks strategically, but misses some plays.
     * Fun because: gives a real challenge with exploitable gaps.
     */
    private static BotAction decideMedium(Player bot, List<Player> players, Stack<Card> deck, int turnsLeft) {
        List<Card> hand = bot.getHand();
        int deckSize = deck.size();

        // Under attack → try to escape
        if (turnsLeft > 1) {
            BotAction escape = tryEscape(hand, bot, players);
            if (escape != null) return escape;
            // No escape cards → draw bravely
        }

        // Small deck → nervous; try to peek/fix/shuffle/skip/attack
        if (deckSize <= 8) {
            int idx;
            if ((idx = find(hand, CardType.SEE_THE_FUTURE)) >= 0)    return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.CHANGE_THE_FUTURE)) >= 0)  return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.SHUFFLE)) >= 0)            return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.SKIP)) >= 0)               return BotAction.play(List.of(idx));
            idx = find(hand, CardType.ATTACK_TO);
            if (idx >= 0) {
                String target = pickRandomTarget(bot, players);
                if (target != null) return BotAction.play(List.of(idx), target);
            }
            if ((idx = find(hand, CardType.ATTACK)) >= 0)             return BotAction.play(List.of(idx));
        }

        // Proactive plays (each checked independently)
        // STF 35%: "let me see what's coming..."
        if (RNG.nextInt(100) < 35) {
            int idx = find(hand, CardType.SEE_THE_FUTURE);
            if (idx >= 0) return BotAction.play(List.of(idx));
        }
        // ATTACK 45%: try ATTACK_TO first (random target), then regular ATTACK
        if (RNG.nextInt(100) < 45) {
            int idx = find(hand, CardType.ATTACK_TO);
            if (idx >= 0) {
                String target = pickRandomTarget(bot, players);
                if (target != null) return BotAction.play(List.of(idx), target);
            }
            if ((idx = find(hand, CardType.ATTACK)) >= 0) return BotAction.play(List.of(idx));
        }
        // FAVOR 20%: occasionally steal — medium player knows this is useful
        if (RNG.nextInt(100) < 20) {
            int idx = find(hand, CardType.FAVOR);
            if (idx >= 0) {
                String target = pickRandomTarget(bot, players);
                if (target != null) return BotAction.play(List.of(idx), target);
            }
        }

        return BotAction.draw();
    }

    // ───────────────────────── Hard (Tiger) — Human AI ─────────────────────────

    /**
     * Plays like a seasoned, calculating human who wants to WIN:
     *  • Does NOT panic at bomb-in-top-6 — evaluates actual risk first
     *  • Factors in whether it holds DEFUSE (safety net = more aggression)
     *  • Prioritises attacking/stealing over compulsive intel gathering
     *  • Uses STF sparingly (25%) — only when it changes the decision
     *  • Places bomb in the danger zone (2–5 from top) to punish next drawers
     */
    private static BotAction decideHard(Player bot, List<Player> players, Stack<Card> deck, int turnsLeft) {
        List<Card> hand = bot.getHand();
        int deckSize = deck.size();

        if (deckSize == 0) return BotAction.draw();

        // ── Situational awareness ──
        int bombPos    = nearestBombFromTop(deck);   // 0 = top, -1 = no bomb
        boolean noBomb    = bombPos < 0;
        boolean bombTop1  = bombPos == 0;
        boolean bombTop3  = !noBomb && bombPos < 3;
        boolean bombTop6  = !noBomb && bombPos < 6;
        boolean bottomSafe = deck.get(0).getType() != CardType.BOOMS;
        boolean hasDefuse  = find(hand, CardType.DEFUSE) >= 0;

        // ── UNDER ATTACK: escape or turn the tables ──
        if (turnsLeft > 1) {
            int idx = find(hand, CardType.SKIP);
            if (idx >= 0) return BotAction.play(List.of(idx));

            idx = find(hand, CardType.ATTACK_TO);
            if (idx >= 0) {
                String target = pickThreat(bot, players);
                if (target != null) return BotAction.play(List.of(idx), target);
            }
            if ((idx = find(hand, CardType.ATTACK)) >= 0) return BotAction.play(List.of(idx));
            if (bombTop1 && bottomSafe && (idx = find(hand, CardType.UNDER)) >= 0)
                return BotAction.play(List.of(idx));
            return BotAction.draw();
        }

        // ── CRITICAL: bomb right on top ──
        if (bombTop1) {
            int idx;
            if ((idx = find(hand, CardType.CHANGE_THE_FUTURE)) >= 0) return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.SHUFFLE)) >= 0)            return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.SKIP)) >= 0)               return BotAction.play(List.of(idx));
            idx = find(hand, CardType.ATTACK_TO);
            if (idx >= 0) {
                String target = pickThreat(bot, players);
                if (target != null) return BotAction.play(List.of(idx), target);
            }
            if ((idx = find(hand, CardType.ATTACK)) >= 0)              return BotAction.play(List.of(idx));
            if (bottomSafe && (idx = find(hand, CardType.UNDER)) >= 0)  return BotAction.play(List.of(idx));
            return BotAction.draw(); // have DEFUSE — will survive
        }

        // ── HIGH DANGER: bomb is 2nd or 3rd card ──
        // CTF/SHUFFLE/SKIP/ATTACK — NOT STF (knowing it's pos 1-2 doesn't help, we need action)
        if (bombTop3) {
            int idx;
            if ((idx = find(hand, CardType.CHANGE_THE_FUTURE)) >= 0)     return BotAction.play(List.of(idx));
            if (RNG.nextInt(100) < 70 && (idx = find(hand, CardType.SHUFFLE)) >= 0)
                return BotAction.play(List.of(idx));
            if ((idx = find(hand, CardType.SKIP)) >= 0)                   return BotAction.play(List.of(idx));
            idx = find(hand, CardType.ATTACK_TO);
            if (idx >= 0) {
                String target = pickThreat(bot, players);
                if (target != null) return BotAction.play(List.of(idx), target);
            }
            if ((idx = find(hand, CardType.ATTACK)) >= 0)                 return BotAction.play(List.of(idx));
            if (bottomSafe && (idx = find(hand, CardType.UNDER)) >= 0)    return BotAction.play(List.of(idx));
            // STF only as last resort — at least confirm position before drawing
            if ((idx = find(hand, CardType.SEE_THE_FUTURE)) >= 0)         return BotAction.play(List.of(idx));
            return BotAction.draw();
        }

        // ── MODERATE DANGER: bomb in positions 3–5 from top ──
        // Key decision driver: do I have a DEFUSE?
        if (bombTop6) {
            int idx;
            if (!hasDefuse) {
                // No safety net — be proactive but measured (NOT frantic)
                if (RNG.nextInt(100) < 40 && (idx = find(hand, CardType.SEE_THE_FUTURE)) >= 0)
                    return BotAction.play(List.of(idx));                     // scout 40%
                if (RNG.nextInt(100) < 55) {                                 // attack 55%
                    idx = find(hand, CardType.ATTACK_TO);
                    if (idx >= 0) {
                        String target = pickThreat(bot, players);
                        if (target != null) return BotAction.play(List.of(idx), target);
                    }
                    if ((idx = find(hand, CardType.ATTACK)) >= 0) return BotAction.play(List.of(idx));
                }
                if (RNG.nextInt(100) < 35 && (idx = find(hand, CardType.SKIP)) >= 0)
                    return BotAction.play(List.of(idx));                     // skip 35%
            } else {
                // Have DEFUSE → play aggressively, don't waste resources on defense
                if (RNG.nextInt(100) < 45) {
                    idx = find(hand, CardType.ATTACK_TO);
                    if (idx >= 0) {
                        String target = pickThreat(bot, players);
                        if (target != null) return BotAction.play(List.of(idx), target);
                    }
                    if ((idx = find(hand, CardType.ATTACK)) >= 0) return BotAction.play(List.of(idx));
                }
                if (RNG.nextInt(100) < 30 && (idx = find(hand, CardType.FAVOR)) >= 0) {
                    String target = pickRichestTarget(bot, players);
                    if (target != null) return BotAction.play(List.of(idx), target);
                }
                // Fall through — brave enough to draw with DEFUSE in hand
            }
        }

        // ── SAFE / DEEP BOMB: dominant game-control mode ──
        // A real player doesn't just peek all day — they apply pressure.

        // 1. Attack the most dangerous opponent (50%)
        if (RNG.nextInt(100) < 50) {
            int idx = find(hand, CardType.ATTACK_TO);
            if (idx >= 0) {
                String target = pickThreat(bot, players);
                if (target != null) return BotAction.play(List.of(idx), target);
            }
            if ((idx = find(hand, CardType.ATTACK)) >= 0) return BotAction.play(List.of(idx));
        }

        // 2. Resource denial: FAVOR from the richest (35%)
        if (RNG.nextInt(100) < 35) {
            int idx = find(hand, CardType.FAVOR);
            if (idx >= 0) {
                String target = pickRichestTarget(bot, players);
                if (target != null) return BotAction.play(List.of(idx), target);
            }
        }

        // 3. Intel: SEE_THE_FUTURE (25%) — only when it actually changes the plan
        if (RNG.nextInt(100) < 25) {
            int idx = find(hand, CardType.SEE_THE_FUTURE);
            if (idx >= 0) return BotAction.play(List.of(idx));
        }

        // 4. Rearrange for long-term advantage (10%)
        if (RNG.nextInt(100) < 10) {
            int idx = find(hand, CardType.CHANGE_THE_FUTURE);
            if (idx >= 0) return BotAction.play(List.of(idx));
        }

        // 5. Draw — calculated, confident
        return BotAction.draw();
    }

    // ───────────────────────── NOPE decision ─────────────────────────

    /**
     * Should the bot NOPE the pending action?
     */
    public static boolean decideNope(Player bot, Player actionPlayer, CardType actionType,
                                     String actionTarget, List<Player> players, boolean isCurrentlyNoped) {
        if (bot.isExploded() || bot.isSpectator()) return false;
        boolean hasNope = bot.getHand().stream().anyMatch(c -> c.getType() == CardType.NOPE);
        if (!hasNope) return false;

        boolean isTargeted = bot.getName().equals(actionTarget);

        return switch (bot.getBotDifficulty()) {
            case "EASY" -> RNG.nextInt(100) < 15;  // 15% random chaos

            case "MEDIUM" -> {
                if (isCurrentlyNoped) yield isTargeted && RNG.nextInt(100) < 25;
                if (isTargeted && (actionType == CardType.ATTACK_TO || actionType == CardType.FAVOR))
                    yield RNG.nextInt(100) < 60;
                if (actionType == CardType.ATTACK && isNextInLine(bot, actionPlayer, players))
                    yield RNG.nextInt(100) < 35;
                yield RNG.nextInt(100) < 8;
            }

            case "HARD" -> {
                // Counter-NOPE: re-enable our own card (fight back when targeted)
                if (isCurrentlyNoped) {
                    if (isTargeted) yield RNG.nextInt(100) < 60;  // fight back
                    yield RNG.nextInt(100) < 10;                   // rarely help others
                }

                // ATTACK_TO aimed at us — almost always block it
                if (isTargeted && actionType == CardType.ATTACK_TO)
                    yield RNG.nextInt(100) < 85;

                // FAVOR targeting us — protect DEFUSE at all costs
                if (isTargeted && actionType == CardType.FAVOR) {
                    boolean botHasDefuse = bot.getHand().stream()
                            .anyMatch(c -> c.getType() == CardType.DEFUSE);
                    yield botHasDefuse ? RNG.nextInt(100) < 85 : RNG.nextInt(100) < 40;
                }

                // Regular ATTACK that hits us next — block it 70%
                if (actionType == CardType.ATTACK && isNextInLine(bot, actionPlayer, players))
                    yield RNG.nextInt(100) < 70;

                // SHUFFLE: if we have no DEFUSE, a shuffle might bring bomb closer — NOPE it 30%
                if (actionType == CardType.SHUFFLE) {
                    boolean botHasDefuse = bot.getHand().stream()
                            .anyMatch(c -> c.getType() == CardType.DEFUSE);
                    yield !botHasDefuse && RNG.nextInt(100) < 30;
                }

                // Chaos: disrupt someone else's ATTACK_TO when we're not the target
                if (actionType == CardType.ATTACK_TO && !isTargeted)
                    yield RNG.nextInt(100) < 12;

                // Random disruption
                yield RNG.nextInt(100) < 8;
            }

            default -> false;
        };
    }

    // ───────────────────────── Place-bomb decision ─────────────────────────

    /**
     * Returns the deck index at which the bot places the bomb.
     * Index 0 = bottom, deckSize = top (just drawn position).
     */
    public static int decidePlaceBomb(Player bot, int deckSize) {
        if (deckSize == 0) return 0;
        return switch (bot.getBotDifficulty()) {
            case "HARD" -> {
                // Place bomb 2–5 positions from the top — punishes the next 2–5 draws.
                // Not on top (obvious) but solidly in the danger zone.
                if (deckSize <= 1) yield 0;
                int maxFromTop = Math.min(5, deckSize - 1);
                int fromTop = 2 + RNG.nextInt(Math.max(1, maxFromTop - 1)); // 2..maxFromTop
                yield Math.max(0, deckSize - fromTop);
            }
            default -> RNG.nextInt(deckSize + 1); // random
        };
    }

    // ───────────────────────── Give-card decision ─────────────────────────

    /** All difficulties: give the least valuable card (keep DEFUSE/NOPE). */
    public static int decideGiveCard(Player bot) {
        List<Card> hand = bot.getHand();
        if (hand.isEmpty()) return 0;

        // 1st priority: give a MEOW card (least useful)
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
     * Index 0 in result = next card to be drawn (top of deck).
     */
    public static List<String> decideAlterFuture(Player bot, List<Card> futureCards) {
        List<Card> sorted = new ArrayList<>(futureCards);

        if ("HARD".equals(bot.getBotDifficulty())) {
            // Push bomb to position 2 (last of the revealed 3 = furthest from top)
            sorted.sort((a, b) -> {
                if (a.getType() == CardType.BOOMS) return 1;
                if (b.getType() == CardType.BOOMS) return -1;
                return 0;
            });
        } else {
            // Easy/Medium: random re-order
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
            String target = pickThreat(bot, players);
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
            if (t.name().startsWith("MEOW")) continue;
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

    /**
     * Returns the 0-based index of the nearest bomb from the top of the deck.
     * 0 = top card (about to be drawn), -1 = no bomb in deck.
     */
    private static int nearestBombFromTop(Stack<Card> deck) {
        int size = deck.size();
        for (int i = 0; i < size; i++) {
            if (deck.get(size - 1 - i).getType() == CardType.BOOMS) return i;
        }
        return -1;
    }

    private static boolean bombNearTop(Stack<Card> deck, int depth) {
        return nearestBombFromTop(deck) >= 0 && nearestBombFromTop(deck) < depth;
    }

    // ── Targeting helpers ──

    private static String pickRandomTarget(Player bot, List<Player> players) {
        List<Player> targets = alivePlayers(players, bot.getName());
        if (targets.isEmpty()) return null;
        return targets.get(RNG.nextInt(targets.size())).getName();
    }

    /**
     * Pick the most threatening alive opponent — the one with the most cards.
     * More cards = more tools = most dangerous.
     */
    private static String pickThreat(Player bot, List<Player> players) {
        return alivePlayers(players, bot.getName()).stream()
                .max(Comparator.comparingInt(p -> p.getHand().size()))
                .map(Player::getName)
                .orElse(null);
    }

    /** Pick the player with the fewest cards (easiest to finish off). */
    private static String pickWeakestTarget(Player bot, List<Player> players) {
        return alivePlayers(players, bot.getName()).stream()
                .min(Comparator.comparingInt(p -> p.getHand().size()))
                .map(Player::getName)
                .orElse(null);
    }

    /** Pick the player with the most cards (best FAVOR target for resource denial). */
    private static String pickRichestTarget(Player bot, List<Player> players) {
        return alivePlayers(players, bot.getName()).stream()
                .max(Comparator.comparingInt(p -> p.getHand().size()))
                .map(Player::getName)
                .orElse(null);
    }

    private static List<Player> alivePlayers(List<Player> players, String excludeName) {
        return players.stream()
                .filter(p -> !p.getName().equals(excludeName))
                .filter(p -> !p.isExploded() && !p.isSpectator())
                .toList();
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
