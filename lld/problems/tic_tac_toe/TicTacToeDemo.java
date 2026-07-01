package lld.problems.tic_tac_toe;

import java.util.*;

/**
 * Tic-Tac-Toe LLD Demo
 * ---------------------
 * Single-file runnable demo with:
 *   - NxN extensible Board with O(1) win detection (counter-based)
 *   - Player abstraction with pluggable MoveStrategy (Strategy Pattern)
 *   - RandomStrategy and MinimaxStrategy (with alpha-beta pruning)
 *   - Game loop with turn management
 *   - Undo support on the Board
 *
 * Run:
 *   javac TicTacToeDemo.java
 *   java lld.problems.tic_tac_toe.TicTacToeDemo
 */
public class TicTacToeDemo {

    // ─── Board ──────────────────────────────────────────────────────────

    static class Board {
        private final int size;
        private final int[][] grid;
        private final int[] rowCounts;
        private final int[] colCounts;
        private int diagCount;
        private int antiDiagCount;
        private int moveCount;

        Board(int size) {
            this.size = size;
            this.grid = new int[size][size];
            this.rowCounts = new int[size];
            this.colCounts = new int[size];
            this.diagCount = 0;
            this.antiDiagCount = 0;
            this.moveCount = 0;
        }

        int getSize() { return size; }

        boolean isValidMove(int row, int col) {
            return row >= 0 && row < size && col >= 0 && col < size
                    && grid[row][col] == 0;
        }

        /** Place a move. playerValue is +1 or -1. Returns false if invalid. */
        boolean placeMove(int row, int col, int playerValue) {
            if (!isValidMove(row, col)) return false;
            grid[row][col] = playerValue;
            rowCounts[row] += playerValue;
            colCounts[col] += playerValue;
            if (row == col) diagCount += playerValue;
            if (row + col == size - 1) antiDiagCount += playerValue;
            moveCount++;
            return true;
        }

        /** Undo a previously placed move. */
        void undoMove(int row, int col, int playerValue) {
            grid[row][col] = 0;
            rowCounts[row] -= playerValue;
            colCounts[col] -= playerValue;
            if (row == col) diagCount -= playerValue;
            if (row + col == size - 1) antiDiagCount -= playerValue;
            moveCount--;
        }

        /** O(1) win check after placing at (row, col). */
        boolean checkWin(int row, int col, int playerValue) {
            int target = playerValue * size;
            return rowCounts[row] == target
                    || colCounts[col] == target
                    || (row == col && diagCount == target)
                    || (row + col == size - 1 && antiDiagCount == target);
        }

        boolean isFull() {
            return moveCount == size * size;
        }

        int getCellValue(int row, int col) {
            return grid[row][col];
        }

        void display() {
            for (int r = 0; r < size; r++) {
                StringBuilder sb = new StringBuilder(" ");
                for (int c = 0; c < size; c++) {
                    char ch = grid[r][c] == 1 ? 'X' : grid[r][c] == -1 ? 'O' : '.';
                    sb.append(ch);
                    if (c < size - 1) sb.append(" | ");
                }
                System.out.println(sb);
                if (r < size - 1) {
                    System.out.println(" " + "-".repeat(size * 4 - 3));
                }
            }
            System.out.println();
        }
    }

    // ─── MoveStrategy (Strategy Pattern) ────────────────────────────────

    interface MoveStrategy {
        int[] chooseMove(Board board, int playerValue);
    }

    /** Picks a random empty cell. */
    static class RandomStrategy implements MoveStrategy {
        private final Random rng;

        RandomStrategy(long seed) { this.rng = new Random(seed); }
        RandomStrategy() { this.rng = new Random(); }

        @Override
        public int[] chooseMove(Board board, int playerValue) {
            List<int[]> emptyCells = new ArrayList<>();
            for (int r = 0; r < board.getSize(); r++) {
                for (int c = 0; c < board.getSize(); c++) {
                    if (board.isValidMove(r, c)) {
                        emptyCells.add(new int[]{r, c});
                    }
                }
            }
            if (emptyCells.isEmpty()) throw new IllegalStateException("No valid moves");
            return emptyCells.get(rng.nextInt(emptyCells.size()));
        }
    }

    /** Minimax with alpha-beta pruning. Optimal for 3x3, depth-limited for larger. */
    static class MinimaxStrategy implements MoveStrategy {
        private final int maxDepth;

        MinimaxStrategy() { this(Integer.MAX_VALUE); }
        MinimaxStrategy(int maxDepth) { this.maxDepth = maxDepth; }

        @Override
        public int[] chooseMove(Board board, int playerValue) {
            int bestScore = Integer.MIN_VALUE;
            int[] bestMove = null;

            for (int r = 0; r < board.getSize(); r++) {
                for (int c = 0; c < board.getSize(); c++) {
                    if (!board.isValidMove(r, c)) continue;
                    board.placeMove(r, c, playerValue);
                    int score;
                    if (board.checkWin(r, c, playerValue)) {
                        score = 100;
                    } else if (board.isFull()) {
                        score = 0;
                    } else {
                        score = minimax(board, 1, false,
                                Integer.MIN_VALUE, Integer.MAX_VALUE, playerValue);
                    }
                    board.undoMove(r, c, playerValue);
                    if (score > bestScore) {
                        bestScore = score;
                        bestMove = new int[]{r, c};
                    }
                }
            }
            return bestMove;
        }

        private int minimax(Board board, int depth, boolean isMaximizing,
                            int alpha, int beta, int aiValue) {
            if (depth >= maxDepth) return 0; // heuristic cutoff

            int currentPlayer = isMaximizing ? aiValue : -aiValue;

            for (int r = 0; r < board.getSize(); r++) {
                for (int c = 0; c < board.getSize(); c++) {
                    if (!board.isValidMove(r, c)) continue;
                    board.placeMove(r, c, currentPlayer);

                    if (board.checkWin(r, c, currentPlayer)) {
                        board.undoMove(r, c, currentPlayer);
                        return isMaximizing ? (100 - depth) : (depth - 100);
                    }

                    int score;
                    if (board.isFull()) {
                        score = 0;
                    } else {
                        score = minimax(board, depth + 1, !isMaximizing,
                                alpha, beta, aiValue);
                    }
                    board.undoMove(r, c, currentPlayer);

                    if (isMaximizing) {
                        alpha = Math.max(alpha, score);
                    } else {
                        beta = Math.min(beta, score);
                    }
                    if (beta <= alpha) {
                        return isMaximizing ? alpha : beta;
                    }
                }
            }
            return isMaximizing ? alpha : beta;
        }
    }

    // ─── Player ─────────────────────────────────────────────────────────

    static class Player {
        final String name;
        final char symbol;
        final int value; // +1 or -1
        private final MoveStrategy strategy;

        Player(String name, char symbol, int value, MoveStrategy strategy) {
            this.name = name;
            this.symbol = symbol;
            this.value = value;
            this.strategy = strategy;
        }

        int[] getMove(Board board) {
            return strategy.chooseMove(board, value);
        }

        @Override
        public String toString() { return name + " (" + symbol + ")"; }
    }

    // ─── Game ───────────────────────────────────────────────────────────

    static class Game {
        private final Board board;
        private final Player[] players;
        private int currentPlayerIndex;

        Game(int boardSize, Player p1, Player p2) {
            this.board = new Board(boardSize);
            this.players = new Player[]{p1, p2};
            this.currentPlayerIndex = 0;
        }

        /** Runs the game to completion. Returns the winner, or null for draw. */
        Player play(boolean verbose) {
            while (true) {
                Player current = players[currentPlayerIndex];
                if (verbose) {
                    System.out.println(current.name + "'s turn:");
                    board.display();
                }

                int[] move = current.getMove(board);
                boolean placed = board.placeMove(move[0], move[1], current.value);
                if (!placed) {
                    throw new IllegalStateException(
                            current.name + " attempted invalid move at ("
                                    + move[0] + "," + move[1] + ")");
                }

                if (verbose) {
                    System.out.println(current.name + " plays at (" + move[0] + "," + move[1] + ")");
                }

                if (board.checkWin(move[0], move[1], current.value)) {
                    if (verbose) {
                        board.display();
                        System.out.println(current.name + " wins!\n");
                    }
                    return current;
                }

                if (board.isFull()) {
                    if (verbose) {
                        board.display();
                        System.out.println("It's a draw!\n");
                    }
                    return null;
                }

                currentPlayerIndex = 1 - currentPlayerIndex;
            }
        }

        Board getBoard() { return board; }
    }

    // ─── Scripted Strategy (for testing) ────────────────────────────────

    /** Plays a pre-defined sequence of moves. Useful for deterministic tests. */
    static class ScriptedStrategy implements MoveStrategy {
        private final Queue<int[]> moves;

        ScriptedStrategy(int[]... moves) {
            this.moves = new LinkedList<>(Arrays.asList(moves));
        }

        @Override
        public int[] chooseMove(Board board, int playerValue) {
            if (moves.isEmpty()) throw new IllegalStateException("No more scripted moves");
            return moves.poll();
        }
    }

    // ─── Tests & Demo ───────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("=== Tic-Tac-Toe LLD Demo ===\n");

        testBoardBasics();
        testO1WinDetection();
        testDrawDetection();
        testUndoMove();
        testColumnWin();
        testDiagonalWin();
        testAntiDiagonalWin();
        testRandomVsRandom();
        testMinimaxNeverLoses();
        testMinimaxFindsWin();
        test4x4Board();

        System.out.println("All tests passed!");
    }

    static void testBoardBasics() {
        System.out.print("Test: Board basics ... ");
        Board b = new Board(3);

        assert b.getSize() == 3 : "Size should be 3";
        assert b.isValidMove(0, 0) : "Empty cell should be valid";
        assert b.placeMove(0, 0, 1) : "First move should succeed";
        assert !b.isValidMove(0, 0) : "Occupied cell should be invalid";
        assert !b.placeMove(0, 0, -1) : "Placing on occupied cell should fail";
        assert b.isValidMove(1, 1) : "Other empty cell should be valid";
        assert !b.isValidMove(-1, 0) : "Out of bounds should be invalid";
        assert !b.isValidMove(0, 3) : "Out of bounds should be invalid";

        System.out.println("PASSED");
    }

    static void testO1WinDetection() {
        System.out.print("Test: O(1) win detection (row) ... ");
        Board b = new Board(3);

        // Player +1 fills row 0: (0,0), (0,1), (0,2)
        b.placeMove(0, 0, 1);
        assert !b.checkWin(0, 0, 1) : "Not a win yet";

        b.placeMove(0, 1, 1);
        assert !b.checkWin(0, 1, 1) : "Not a win yet";

        b.placeMove(0, 2, 1);
        assert b.checkWin(0, 2, 1) : "Row 0 complete -- should be a win!";

        System.out.println("PASSED");
    }

    static void testColumnWin() {
        System.out.print("Test: O(1) win detection (column) ... ");
        Board b = new Board(3);

        // Player -1 fills column 2
        b.placeMove(0, 2, -1);
        b.placeMove(1, 2, -1);
        assert !b.checkWin(1, 2, -1) : "Not a win yet";
        b.placeMove(2, 2, -1);
        assert b.checkWin(2, 2, -1) : "Column 2 complete -- should be a win!";

        System.out.println("PASSED");
    }

    static void testDiagonalWin() {
        System.out.print("Test: O(1) win detection (diagonal) ... ");
        Board b = new Board(3);

        b.placeMove(0, 0, 1);
        b.placeMove(1, 1, 1);
        b.placeMove(2, 2, 1);
        assert b.checkWin(2, 2, 1) : "Main diagonal complete -- should be a win!";

        System.out.println("PASSED");
    }

    static void testAntiDiagonalWin() {
        System.out.print("Test: O(1) win detection (anti-diagonal) ... ");
        Board b = new Board(3);

        b.placeMove(0, 2, -1);
        b.placeMove(1, 1, -1);
        b.placeMove(2, 0, -1);
        assert b.checkWin(2, 0, -1) : "Anti-diagonal complete -- should be a win!";

        System.out.println("PASSED");
    }

    static void testDrawDetection() {
        System.out.print("Test: Draw detection ... ");
        // Classic draw:
        //  X | O | X
        //  X | X | O
        //  O | X | O
        Board b = new Board(3);
        int[][] moves = {
            {0,0,1}, {0,1,-1}, {0,2,1},
            {1,0,1}, {1,1,1},  {1,2,-1},
            {2,0,-1},{2,1,1},  {2,2,-1}
        };
        for (int[] m : moves) {
            b.placeMove(m[0], m[1], m[2]);
        }
        assert b.isFull() : "Board should be full";
        // Verify no one won on the last move
        assert !b.checkWin(2, 2, -1) : "Should not be a win";

        System.out.println("PASSED");
    }

    static void testUndoMove() {
        System.out.print("Test: Undo move ... ");
        Board b = new Board(3);

        b.placeMove(0, 0, 1);
        b.placeMove(0, 1, 1);
        b.placeMove(0, 2, 1);
        assert b.checkWin(0, 2, 1) : "Should be a win before undo";

        b.undoMove(0, 2, 1);
        assert b.isValidMove(0, 2) : "Cell should be free after undo";
        assert !b.checkWin(0, 1, 1) : "Should not be a win after undo";
        assert !b.isFull() : "Board should not be full after undo";

        System.out.println("PASSED");
    }

    static void testRandomVsRandom() {
        System.out.print("Test: Random vs Random (100 games) ... ");
        int xWins = 0, oWins = 0, draws = 0;

        for (int i = 0; i < 100; i++) {
            Player p1 = new Player("X", 'X', 1, new RandomStrategy(i * 31L));
            Player p2 = new Player("O", 'O', -1, new RandomStrategy(i * 37L));
            Game game = new Game(3, p1, p2);
            Player winner = game.play(false);
            if (winner == null) draws++;
            else if (winner.value == 1) xWins++;
            else oWins++;
        }

        System.out.println("PASSED (X:" + xWins + " O:" + oWins + " D:" + draws + ")");
        assert (xWins + oWins + draws) == 100 : "All games should complete";
    }

    static void testMinimaxNeverLoses() {
        System.out.print("Test: Minimax never loses vs Random (50 games) ... ");

        for (int i = 0; i < 50; i++) {
            // Minimax as player 1 (X)
            Player minimax = new Player("Minimax", 'X', 1, new MinimaxStrategy());
            Player random = new Player("Random", 'O', -1, new RandomStrategy(i * 41L));
            Game game = new Game(3, minimax, random);
            Player winner = game.play(false);
            assert winner == null || winner.value == 1
                    : "Minimax should never lose as X! Lost in game " + i;
        }

        for (int i = 0; i < 50; i++) {
            // Minimax as player 2 (O)
            Player random = new Player("Random", 'X', 1, new RandomStrategy(i * 43L));
            Player minimax = new Player("Minimax", 'O', -1, new MinimaxStrategy());
            Game game = new Game(3, random, minimax);
            Player winner = game.play(false);
            assert winner == null || winner.value == -1
                    : "Minimax should never lose as O! Lost in game " + i;
        }

        System.out.println("PASSED");
    }

    static void testMinimaxFindsWin() {
        System.out.print("Test: Minimax finds forced win ... ");
        // Set up a board where X can win immediately at (0,2)
        //  X | X | .
        //  O | O | .
        //  . | . | .
        Board b = new Board(3);
        b.placeMove(0, 0, 1);  // X
        b.placeMove(1, 0, -1); // O
        b.placeMove(0, 1, 1);  // X
        b.placeMove(1, 1, -1); // O

        MinimaxStrategy strategy = new MinimaxStrategy();
        int[] move = strategy.chooseMove(b, 1);

        assert move[0] == 0 && move[1] == 2
                : "Minimax should pick (0,2) to win, but picked ("
                + move[0] + "," + move[1] + ")";

        System.out.println("PASSED");
    }

    static void test4x4Board() {
        System.out.print("Test: 4x4 board win detection ... ");
        Board b = new Board(4);

        // Player 1 fills the main diagonal of a 4x4 board
        b.placeMove(0, 0, 1);
        b.placeMove(1, 1, 1);
        b.placeMove(2, 2, 1);
        assert !b.checkWin(2, 2, 1) : "3 in a row on 4x4 should not win";

        b.placeMove(3, 3, 1);
        assert b.checkWin(3, 3, 1) : "4 in a row on 4x4 diagonal should win";

        System.out.println("PASSED");
    }
}
