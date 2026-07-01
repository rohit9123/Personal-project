package lld.problems.snake_and_ladder;

import java.util.*;

/**
 * Snake & Ladder — Complete LLD Demo (single-file, runnable).
 *
 * Compile: javac -d out SnakeAndLadderDemo.java
 * Run:     java -cp out lld.problems.snake_and_ladder.SnakeAndLadderDemo
 *
 * Design highlights:
 *  - Board uses a merged positionMap for snakes + ladders (chained resolution)
 *  - DiceStrategy interface (Strategy pattern) for swappable dice
 *  - BoardBuilder (Builder pattern) with validation
 *  - Queue-based turn management for multiplayer
 *  - Seeded Random for deterministic/testable runs
 */
public class SnakeAndLadderDemo {

    // ─── Dice Strategy (Strategy Pattern) ──────────────────────────────

    interface DiceStrategy {
        int roll();
    }

    static class StandardDice implements DiceStrategy {
        private final Random random;
        private final int faces;

        StandardDice(int faces, Random random) {
            this.faces = faces;
            this.random = random;
        }

        @Override
        public int roll() {
            return random.nextInt(faces) + 1;
        }
    }

    static class TwoDice implements DiceStrategy {
        private final Random random;

        TwoDice(Random random) {
            this.random = random;
        }

        @Override
        public int roll() {
            return (random.nextInt(6) + 1) + (random.nextInt(6) + 1);
        }
    }

    // ─── Player ────────────────────────────────────────────────────────

    static class Player {
        private final String name;
        private int position;

        Player(String name) {
            this.name = name;
            this.position = 0; // off-board
        }

        String getName() { return name; }
        int getPosition() { return position; }
        void setPosition(int position) { this.position = position; }

        @Override
        public String toString() {
            return name + "(pos=" + position + ")";
        }
    }

    // ─── Board ─────────────────────────────────────────────────────────

    static class Board {
        private final int size;
        private final Map<Integer, Integer> positionMap; // merged snakes + ladders
        private final Map<Integer, Integer> snakes;
        private final Map<Integer, Integer> ladders;

        Board(int size, Map<Integer, Integer> positionMap,
              Map<Integer, Integer> snakes, Map<Integer, Integer> ladders) {
            this.size = size;
            this.positionMap = Collections.unmodifiableMap(positionMap);
            this.snakes = snakes;
            this.ladders = ladders;
        }

        int getSize() { return size; }

        boolean isWinningPosition(int pos) {
            return pos == size;
        }

        /**
         * Resolve chained snakes/ladders. Detects infinite loops via visited set.
         */
        int resolvePosition(int position) {
            Set<Integer> visited = new HashSet<>();
            while (positionMap.containsKey(position)) {
                if (!visited.add(position)) {
                    throw new IllegalStateException(
                        "Infinite loop detected at position " + position);
                }
                int next = positionMap.get(position);
                if (snakes.containsKey(position)) {
                    System.out.printf("    SNAKE! %d -> %d%n", position, next);
                } else {
                    System.out.printf("    LADDER! %d -> %d%n", position, next);
                }
                position = next;
            }
            return position;
        }

        void printBoard() {
            System.out.println("=== Board (size " + size + ") ===");
            System.out.println("Snakes:  " + snakes);
            System.out.println("Ladders: " + ladders);
            System.out.println();
        }
    }

    // ─── Board Builder ─────────────────────────────────────────────────

    static class BoardBuilder {
        private int size = 100;
        private final Map<Integer, Integer> snakes = new LinkedHashMap<>();
        private final Map<Integer, Integer> ladders = new LinkedHashMap<>();

        BoardBuilder size(int size) {
            if (size < 10) throw new IllegalArgumentException("Board size must be >= 10");
            this.size = size;
            return this;
        }

        BoardBuilder addSnake(int head, int tail) {
            validate("Snake", head, tail, true);
            if (head <= tail) {
                throw new IllegalArgumentException(
                    "Snake head (" + head + ") must be greater than tail (" + tail + ")");
            }
            snakes.put(head, tail);
            return this;
        }

        BoardBuilder addLadder(int bottom, int top) {
            validate("Ladder", bottom, top, false);
            if (bottom >= top) {
                throw new IllegalArgumentException(
                    "Ladder bottom (" + bottom + ") must be less than top (" + top + ")");
            }
            ladders.put(bottom, top);
            return this;
        }

        private void validate(String type, int from, int to, boolean isSnake) {
            if (from < 1 || from > size || to < 1 || to > size) {
                throw new IllegalArgumentException(
                    type + " positions out of bounds: " + from + " -> " + to);
            }
            if (from == 1 || to == 1) {
                throw new IllegalArgumentException(type + " cannot start or end at position 1");
            }
            if (from == size) {
                throw new IllegalArgumentException(type + " cannot start at the winning cell");
            }
            int key = isSnake ? from : from;
            if (snakes.containsKey(key) || ladders.containsKey(key)) {
                throw new IllegalArgumentException(
                    "Conflict: position " + key + " already has a snake or ladder");
            }
        }

        Board build() {
            Map<Integer, Integer> merged = new HashMap<>();
            merged.putAll(snakes);
            merged.putAll(ladders);

            // Detect cycles in the merged position map
            for (int pos : merged.keySet()) {
                Set<Integer> visited = new HashSet<>();
                int current = pos;
                while (merged.containsKey(current)) {
                    if (!visited.add(current)) {
                        throw new IllegalArgumentException(
                            "Cycle detected in position map involving position " + pos);
                    }
                    current = merged.get(current);
                }
            }

            return new Board(size, merged,
                Collections.unmodifiableMap(new LinkedHashMap<>(snakes)),
                Collections.unmodifiableMap(new LinkedHashMap<>(ladders)));
        }
    }

    // ─── Move Record (for undo support) ────────────────────────────────

    static class Move {
        final Player player;
        final int fromPos;
        final int toPos;
        final int diceRoll;

        Move(Player player, int fromPos, int toPos, int diceRoll) {
            this.player = player;
            this.fromPos = fromPos;
            this.toPos = toPos;
            this.diceRoll = diceRoll;
        }

        @Override
        public String toString() {
            return player.getName() + ": " + fromPos + " -> " + toPos +
                   " (rolled " + diceRoll + ")";
        }
    }

    // ─── Game ──────────────────────────────────────────────────────────

    static class Game {
        private final Board board;
        private final Deque<Player> playerQueue;
        private final DiceStrategy dice;
        private final Stack<Move> moveHistory;
        private boolean gameOver;
        private Player winner;
        private int turnCount;

        Game(Board board, List<Player> players, DiceStrategy dice) {
            if (players.size() < 2) {
                throw new IllegalArgumentException("Need at least 2 players");
            }
            this.board = board;
            this.playerQueue = new ArrayDeque<>(players);
            this.dice = dice;
            this.moveHistory = new Stack<>();
            this.gameOver = false;
            this.turnCount = 0;
        }

        void play() {
            board.printBoard();
            System.out.println("Players: " + playerQueue);
            System.out.println("─".repeat(50));

            while (!gameOver) {
                Player current = playerQueue.poll();
                playTurn(current);
                if (!gameOver) {
                    playerQueue.offer(current);
                }

                // Safety: cap turns to prevent runaways in edge cases
                if (turnCount > 1000) {
                    System.out.println("Game exceeded 1000 turns — ending.");
                    break;
                }
            }

            System.out.println("─".repeat(50));
            System.out.println("Game finished in " + turnCount + " turns.");
            if (winner != null) {
                System.out.println("Winner: " + winner.getName());
            }
            System.out.println("\nMove history (" + moveHistory.size() + " moves):");
            for (Move m : moveHistory) {
                System.out.println("  " + m);
            }
        }

        private void playTurn(Player player) {
            turnCount++;
            int roll = dice.roll();
            int oldPos = player.getPosition();
            int newPos = oldPos + roll;

            System.out.printf("[Turn %d] %s rolled %d", turnCount, player.getName(), roll);

            if (newPos > board.getSize()) {
                System.out.printf(" — stays at %d (would exceed %d)%n",
                    oldPos, board.getSize());
                moveHistory.push(new Move(player, oldPos, oldPos, roll));
                return;
            }

            System.out.printf(" — moves %d -> %d%n", oldPos, newPos);

            // Resolve snakes and ladders (may chain)
            newPos = board.resolvePosition(newPos);
            player.setPosition(newPos);
            moveHistory.push(new Move(player, oldPos, newPos, roll));

            if (board.isWinningPosition(newPos)) {
                System.out.printf("*** %s WINS at position %d! ***%n",
                    player.getName(), newPos);
                gameOver = true;
                winner = player;
            }
        }

        /**
         * Undo the last move — demonstrates the concept.
         */
        Move undo() {
            if (moveHistory.isEmpty()) {
                System.out.println("Nothing to undo.");
                return null;
            }
            Move last = moveHistory.pop();
            last.player.setPosition(last.fromPos);
            // In a full implementation, we'd also restore the turn queue order
            System.out.printf("UNDO: %s reverted from %d to %d%n",
                last.player.getName(), last.toPos, last.fromPos);
            return last;
        }
    }

    // ─── Main ──────────────────────────────────────────────────────────

    public static void main(String[] args) {
        // Build a board with well-known snakes and ladders
        Board board = new BoardBuilder()
            .size(100)
            // Snakes (head -> tail)
            .addSnake(99, 10)
            .addSnake(65, 25)
            .addSnake(52, 11)
            .addSnake(36, 6)
            .addSnake(88, 49)
            // Ladders (bottom -> top)
            .addLadder(3, 38)
            .addLadder(14, 57)
            .addLadder(42, 84)
            .addLadder(67, 96)
            .addLadder(28, 74)
            .build();

        // Players
        List<Player> players = List.of(
            new Player("Alice"),
            new Player("Bob")
        );

        // Seeded random for reproducible output
        Random seededRandom = new Random(42L);
        DiceStrategy dice = new StandardDice(6, seededRandom);

        // Run the game
        Game game = new Game(board, players, dice);
        game.play();
    }
}
