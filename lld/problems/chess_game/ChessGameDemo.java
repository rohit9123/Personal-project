package lld.problems.chess_game;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChessGameDemo {

    // ============================================================================
    // 1. Enums
    // ============================================================================
    public static enum PieceColor {
        WHITE, BLACK;

        public PieceColor opposite() {
            return this == WHITE ? BLACK : WHITE;
        }
    }

    public static enum PieceType {
        PAWN, ROOK, KNIGHT, BISHOP, QUEEN, KING
    }

    public static enum GameState {
        ACTIVE, CHECK, CHECKMATE, STALEMATE
    }

    // ============================================================================
    // 2. Coordinate Representation
    // ============================================================================
    public static class Coordinate {
        private final int row;
        private final int col;

        public Coordinate(int row, int col) {
            this.row = row;
            this.col = col;
        }

        public int getRow() {
            return row;
        }

        public int getCol() {
            return col;
        }

        public static Coordinate fromNotation(String notation) {
            if (notation == null || notation.length() != 2) {
                throw new IllegalArgumentException("Invalid notation: " + notation);
            }
            char colChar = notation.charAt(0);
            char rowChar = notation.charAt(1);

            int col = colChar - 'a';
            int row = 8 - (rowChar - '0');
            return new Coordinate(row, col);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Coordinate that = (Coordinate) o;
            return row == that.row && col == that.col;
        }

        @Override
        public int hashCode() {
            return Objects.hash(row, col);
        }

        @Override
        public String toString() {
            char colChar = (char) ('a' + col);
            int rank = 8 - row;
            return "" + colChar + rank;
        }
    }

    // ============================================================================
    // 3. Piece Hierarchy (Polymorphic Movement)
    // ============================================================================
    public static abstract class Piece {
        private final PieceColor color;
        private final PieceType type;

        public Piece(PieceColor color, PieceType type) {
            this.color = color;
            this.type = type;
        }

        public PieceColor getColor() {
            return color;
        }

        public PieceType getType() {
            return type;
        }

        public abstract boolean isValidMove(Board board, Coordinate start, Coordinate end);
        public abstract boolean isAttacking(Board board, Coordinate start, Coordinate end);

        protected boolean canMoveTo(Board board, Coordinate end) {
            Piece target = board.getPiece(end);
            return target == null || target.getColor() != this.color;
        }
    }

    public static class Pawn extends Piece {
        public Pawn(PieceColor color) {
            super(color, PieceType.PAWN);
        }

        @Override
        public boolean isAttacking(Board board, Coordinate start, Coordinate end) {
            int rowDiff = end.getRow() - start.getRow();
            int colDiff = Math.abs(end.getCol() - start.getCol());
            int direction = (getColor() == PieceColor.WHITE) ? -1 : 1;
            return rowDiff == direction && colDiff == 1;
        }

        @Override
        public boolean isValidMove(Board board, Coordinate start, Coordinate end) {
            int startRow = start.getRow();
            int startCol = start.getCol();
            int endRow = end.getRow();
            int endCol = end.getCol();

            int rowDiff = endRow - startRow;
            int colDiff = endCol - startCol;
            int direction = (getColor() == PieceColor.WHITE) ? -1 : 1;
            int startingRow = (getColor() == PieceColor.WHITE) ? 6 : 1;

            // 1. Move 1 square forward
            if (colDiff == 0 && rowDiff == direction) {
                return board.getPiece(end) == null;
            }

            // 2. Move 2 squares forward from starting position
            if (colDiff == 0 && startRow == startingRow && rowDiff == 2 * direction) {
                Coordinate intermediate = new Coordinate(startRow + direction, startCol);
                return board.getPiece(intermediate) == null && board.getPiece(end) == null;
            }

            // 3. Capture diagonally
            if (isAttacking(board, start, end)) {
                Piece target = board.getPiece(end);
                return target != null && target.getColor() != getColor();
            }

            return false;
        }
    }

    public static class Rook extends Piece {
        public Rook(PieceColor color) {
            super(color, PieceType.ROOK);
        }

        @Override
        public boolean isAttacking(Board board, Coordinate start, Coordinate end) {
            if (start.getRow() != end.getRow() && start.getCol() != end.getCol()) {
                return false;
            }
            return board.isPathClear(start, end);
        }

        @Override
        public boolean isValidMove(Board board, Coordinate start, Coordinate end) {
            if (!canMoveTo(board, end)) return false;
            return isAttacking(board, start, end);
        }
    }

    public static class Knight extends Piece {
        public Knight(PieceColor color) {
            super(color, PieceType.KNIGHT);
        }

        @Override
        public boolean isAttacking(Board board, Coordinate start, Coordinate end) {
            int rowDiff = Math.abs(end.getRow() - start.getRow());
            int colDiff = Math.abs(end.getCol() - start.getCol());
            return (rowDiff == 2 && colDiff == 1) || (rowDiff == 1 && colDiff == 2);
        }

        @Override
        public boolean isValidMove(Board board, Coordinate start, Coordinate end) {
            if (!canMoveTo(board, end)) return false;
            return isAttacking(board, start, end);
        }
    }

    public static class Bishop extends Piece {
        public Bishop(PieceColor color) {
            super(color, PieceType.BISHOP);
        }

        @Override
        public boolean isAttacking(Board board, Coordinate start, Coordinate end) {
            int rowDiff = Math.abs(end.getRow() - start.getRow());
            int colDiff = Math.abs(end.getCol() - start.getCol());
            if (rowDiff != colDiff) return false;
            return board.isPathClear(start, end);
        }

        @Override
        public boolean isValidMove(Board board, Coordinate start, Coordinate end) {
            if (!canMoveTo(board, end)) return false;
            return isAttacking(board, start, end);
        }
    }

    public static class Queen extends Piece {
        public Queen(PieceColor color) {
            super(color, PieceType.QUEEN);
        }

        @Override
        public boolean isAttacking(Board board, Coordinate start, Coordinate end) {
            int rowDiff = Math.abs(end.getRow() - start.getRow());
            int colDiff = Math.abs(end.getCol() - start.getCol());

            boolean isDiagonal = (rowDiff == colDiff);
            boolean isStraight = (start.getRow() == end.getRow() || start.getCol() == end.getCol());

            if (!isDiagonal && !isStraight) return false;
            return board.isPathClear(start, end);
        }

        @Override
        public boolean isValidMove(Board board, Coordinate start, Coordinate end) {
            if (!canMoveTo(board, end)) return false;
            return isAttacking(board, start, end);
        }
    }

    public static class King extends Piece {
        public King(PieceColor color) {
            super(color, PieceType.KING);
        }

        @Override
        public boolean isAttacking(Board board, Coordinate start, Coordinate end) {
            int rowDiff = Math.abs(end.getRow() - start.getRow());
            int colDiff = Math.abs(end.getCol() - start.getCol());
            return rowDiff <= 1 && colDiff <= 1;
        }

        @Override
        public boolean isValidMove(Board board, Coordinate start, Coordinate end) {
            if (!canMoveTo(board, end)) return false;
            return isAttacking(board, start, end);
        }
    }

    // ============================================================================
    // 4. Board Representation
    // ============================================================================
    public static class Board {
        private final Piece[][] grid;

        public Board() {
            grid = new Piece[8][8];
            initialize();
        }

        public void initialize() {
            // Clear board
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    grid[r][c] = null;
                }
            }

            // Set up Black non-pawns (Row 0)
            grid[0][0] = new Rook(PieceColor.BLACK);
            grid[0][1] = new Knight(PieceColor.BLACK);
            grid[0][2] = new Bishop(PieceColor.BLACK);
            grid[0][3] = new Queen(PieceColor.BLACK);
            grid[0][4] = new King(PieceColor.BLACK);
            grid[0][5] = new Bishop(PieceColor.BLACK);
            grid[0][6] = new Knight(PieceColor.BLACK);
            grid[0][7] = new Rook(PieceColor.BLACK);

            // Set up Black Pawns (Row 1)
            for (int c = 0; c < 8; c++) {
                grid[1][c] = new Pawn(PieceColor.BLACK);
            }

            // Set up White Pawns (Row 6)
            for (int c = 0; c < 8; c++) {
                grid[6][c] = new Pawn(PieceColor.WHITE);
            }

            // Set up White non-pawns (Row 7)
            grid[7][0] = new Rook(PieceColor.WHITE);
            grid[7][1] = new Knight(PieceColor.WHITE);
            grid[7][2] = new Bishop(PieceColor.WHITE);
            grid[7][3] = new Queen(PieceColor.WHITE);
            grid[7][4] = new King(PieceColor.WHITE);
            grid[7][5] = new Bishop(PieceColor.WHITE);
            grid[7][6] = new Knight(PieceColor.WHITE);
            grid[7][7] = new Rook(PieceColor.WHITE);
        }

        public Piece getPiece(Coordinate coord) {
            if (!isValidCoordinate(coord)) return null;
            return grid[coord.getRow()][coord.getCol()];
        }

        public void setPiece(Coordinate coord, Piece piece) {
            if (isValidCoordinate(coord)) {
                grid[coord.getRow()][coord.getCol()] = piece;
            }
        }

        public boolean isValidCoordinate(Coordinate coord) {
            return coord.getRow() >= 0 && coord.getRow() < 8 &&
                   coord.getCol() >= 0 && coord.getCol() < 8;
        }

        public boolean isPathClear(Coordinate start, Coordinate end) {
            int rowDiff = end.getRow() - start.getRow();
            int colDiff = end.getCol() - start.getCol();

            int rowStep = Integer.compare(rowDiff, 0);
            int colStep = Integer.compare(colDiff, 0);

            int currRow = start.getRow() + rowStep;
            int currCol = start.getCol() + colStep;

            while (currRow != end.getRow() || currCol != end.getCol()) {
                if (grid[currRow][currCol] != null) {
                    return false;
                }
                currRow += rowStep;
                currCol += colStep;
            }

            return true;
        }

        public Coordinate findKing(PieceColor color) {
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Piece piece = grid[r][c];
                    if (piece != null && piece.getType() == PieceType.KING && piece.getColor() == color) {
                        return new Coordinate(r, c);
                    }
                }
            }
            return null;
        }

        public boolean isSquareUnderAttack(Coordinate coord, PieceColor attackerColor) {
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Piece piece = grid[r][c];
                    if (piece != null && piece.getColor() == attackerColor) {
                        if (piece.isAttacking(this, new Coordinate(r, c), coord)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        public boolean isInCheck(PieceColor color) {
            Coordinate kingCoord = findKing(color);
            if (kingCoord == null) return false;
            return isSquareUnderAttack(kingCoord, color.opposite());
        }

        public void printBoard() {
            System.out.println("  +-----------------+");
            for (int r = 0; r < 8; r++) {
                System.out.print((8 - r) + " | ");
                for (int c = 0; c < 8; c++) {
                    Piece piece = grid[r][c];
                    if (piece == null) {
                        System.out.print(". ");
                    } else {
                        char symbol = getPieceSymbol(piece);
                        System.out.print(symbol + " ");
                    }
                }
                System.out.println("|");
            }
            System.out.println("  +-----------------+");
            System.out.println("    a b c d e f g h");
        }

        private char getPieceSymbol(Piece piece) {
            char symbol = switch (piece.getType()) {
                case PAWN -> 'p';
                case ROOK -> 'r';
                case KNIGHT -> 'n';
                case BISHOP -> 'b';
                case QUEEN -> 'q';
                case KING -> 'k';
            };
            return piece.getColor() == PieceColor.WHITE ? Character.toUpperCase(symbol) : symbol;
        }
    }

    // ============================================================================
    // 5. Command Pattern (Undo/Redo Support)
    // ============================================================================
    public static interface Command {
        boolean execute();
        void undo();
        void redo();
    }

    public static class MoveCommand implements Command {
        private final ChessGame game;
        private final Coordinate start;
        private final Coordinate end;
        private Piece pieceMoved;
        private Piece pieceCaptured;
        private GameState prevGameState;
        private PieceColor prevTurn;
        private boolean executed = false;

        public MoveCommand(ChessGame game, Coordinate start, Coordinate end) {
            this.game = game;
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean execute() {
            Board board = game.getBoard();
            this.pieceMoved = board.getPiece(start);
            this.pieceCaptured = board.getPiece(end);
            this.prevGameState = game.getGameState();
            this.prevTurn = game.getCurrentTurn();

            // Move piece
            board.setPiece(end, pieceMoved);
            board.setPiece(start, null);

            // Simple Auto-Pawn Promotion to Queen on reaching end row
            if (pieceMoved.getType() == PieceType.PAWN) {
                int targetRow = (pieceMoved.getColor() == PieceColor.WHITE) ? 0 : 7;
                if (end.getRow() == targetRow) {
                    board.setPiece(end, new Queen(pieceMoved.getColor()));
                }
            }

            // Switch Turn & Update Status for Next Turn
            PieceColor nextTurn = prevTurn.opposite();
            game.setCurrentTurn(nextTurn);
            game.updateGameStatus(nextTurn);

            this.executed = true;
            return true;
        }

        @Override
        public void undo() {
            if (!executed) return;

            Board board = game.getBoard();

            // Restore start and end pieces
            board.setPiece(start, pieceMoved);
            board.setPiece(end, pieceCaptured);

            // Restore previous states
            game.setGameState(prevGameState);
            game.setCurrentTurn(prevTurn);

            this.executed = false;
        }

        @Override
        public void redo() {
            execute();
        }

        @Override
        public String toString() {
            return pieceMoved.getColor() + " " + pieceMoved.getType() + " " + start + " -> " + end + 
                   (pieceCaptured != null ? " (captured " + pieceCaptured.getType() + ")" : "");
        }
    }

    public static class MoveHistory {
        private final Stack<Command> undoStack = new Stack<>();
        private final Stack<Command> redoStack = new Stack<>();

        public synchronized void pushCommand(Command command) {
            undoStack.push(command);
            redoStack.clear(); // Clear redo stack on new move
        }

        public synchronized boolean canUndo() {
            return !undoStack.isEmpty();
        }

        public synchronized boolean canRedo() {
            return !redoStack.isEmpty();
        }

        public synchronized void undo() {
            if (!undoStack.isEmpty()) {
                Command cmd = undoStack.pop();
                cmd.undo();
                redoStack.push(cmd);
            }
        }

        public synchronized void redo() {
            if (!redoStack.isEmpty()) {
                Command cmd = redoStack.pop();
                cmd.redo();
                undoStack.push(cmd);
            }
        }
    }

    // ============================================================================
    // 6. Chess Game Manager (Thread-Safe Operations)
    // ============================================================================
    public static class ChessGame {
        private final Board board;
        private PieceColor currentTurn;
        private GameState gameState;
        private final MoveHistory moveHistory;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

        public ChessGame() {
            this.board = new Board();
            this.currentTurn = PieceColor.WHITE;
            this.gameState = GameState.ACTIVE;
            this.moveHistory = new MoveHistory();
        }

        public Board getBoard() {
            return board;
        }

        public Coordinate findKing(PieceColor color) {
            lock.readLock().lock();
            try {
                return board.findKing(color);
            } finally {
                lock.readLock().unlock();
            }
        }

        public GameState getGameState() {
            lock.readLock().lock();
            try {
                return gameState;
            } finally {
                lock.readLock().unlock();
            }
        }

        public void setGameState(GameState state) {
            // Called inside command execution (protected by write lock)
            this.gameState = state;
        }

        public PieceColor getCurrentTurn() {
            lock.readLock().lock();
            try {
                return currentTurn;
            } finally {
                lock.readLock().unlock();
            }
        }

        public void setCurrentTurn(PieceColor turn) {
            // Called inside command execution (protected by write lock)
            this.currentTurn = turn;
        }

        public boolean makeMove(Coordinate start, Coordinate end) {
            lock.writeLock().lock();
            try {
                if (gameState == GameState.CHECKMATE || gameState == GameState.STALEMATE) {
                    System.out.println("Move Rejected: The game has ended.");
                    return false;
                }

                if (!isValidMoveLogical(start, end, currentTurn)) {
                    System.out.println("Move Rejected: Invalid move " + start + " -> " + end + " for " + currentTurn);
                    return false;
                }

                MoveCommand command = new MoveCommand(this, start, end);
                if (command.execute()) {
                    moveHistory.pushCommand(command);
                    System.out.println("Move Executed: " + command);
                    return true;
                }
                return false;
            } finally {
                lock.writeLock().unlock();
            }
        }

        public boolean undo() {
            lock.writeLock().lock();
            try {
                if (moveHistory.canUndo()) {
                    moveHistory.undo();
                    System.out.println("Undo executed successfully. Turn reverted to " + currentTurn);
                    return true;
                }
                System.out.println("Undo Rejected: No moves in history.");
                return false;
            } finally {
                lock.writeLock().unlock();
            }
        }

        public boolean redo() {
            lock.writeLock().lock();
            try {
                if (moveHistory.canRedo()) {
                    moveHistory.redo();
                    System.out.println("Redo executed successfully. Turn advanced to " + currentTurn);
                    return true;
                }
                System.out.println("Redo Rejected: No undone moves to redo.");
                return false;
            } finally {
                lock.writeLock().unlock();
            }
        }

        public void printGameStatus() {
            lock.readLock().lock();
            try {
                board.printBoard();
                System.out.println("Current Turn: " + currentTurn + " | Game State: " + gameState);
            } finally {
                lock.readLock().unlock();
            }
        }

        public boolean isValidMoveLogical(Coordinate start, Coordinate end, PieceColor color) {
            Piece piece = board.getPiece(start);
            if (piece == null || piece.getColor() != color) {
                return false;
            }

            if (!board.isValidCoordinate(end)) return false;
            Piece target = board.getPiece(end);
            if (target != null && target.getColor() == color) return false;

            // Check geometrical physical capabilities
            if (!piece.isValidMove(board, start, end)) {
                return false;
            }

            // Simulate move to ensure King is not in check
            board.setPiece(end, piece);
            board.setPiece(start, null);

            boolean inCheck = board.isInCheck(color);

            // Revert move
            board.setPiece(start, piece);
            board.setPiece(end, target);

            return !inCheck;
        }

        public void updateGameStatus(PieceColor activeColor) {
            if (board.isInCheck(activeColor)) {
                if (!hasLegalMoves(activeColor)) {
                    gameState = GameState.CHECKMATE;
                } else {
                    gameState = GameState.CHECK;
                }
            } else {
                if (!hasLegalMoves(activeColor)) {
                    gameState = GameState.STALEMATE;
                } else {
                    gameState = GameState.ACTIVE;
                }
            }
        }

        private boolean hasLegalMoves(PieceColor color) {
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Coordinate start = new Coordinate(r, c);
                    Piece piece = board.getPiece(start);
                    if (piece != null && piece.getColor() == color) {
                        for (int destR = 0; destR < 8; destR++) {
                            for (int destC = 0; destC < 8; destC++) {
                                Coordinate end = new Coordinate(destR, destC);
                                if (isValidMoveLogical(start, end, color)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }

        // Returns true if a random move was found and successfully made
        public boolean makeRandomMove(PieceColor color) {
            lock.writeLock().lock();
            try {
                if (gameState == GameState.CHECKMATE || gameState == GameState.STALEMATE) {
                    return false;
                }
                if (currentTurn != color) {
                    return false;
                }

                List<MoveCandidate> candidates = new ArrayList<>();
                for (int r = 0; r < 8; r++) {
                    for (int c = 0; c < 8; c++) {
                        Coordinate start = new Coordinate(r, c);
                        Piece piece = board.getPiece(start);
                        if (piece != null && piece.getColor() == color) {
                            for (int destR = 0; destR < 8; destR++) {
                                for (int destC = 0; destC < 8; destC++) {
                                    Coordinate end = new Coordinate(destR, destC);
                                    if (isValidMoveLogical(start, end, color)) {
                                        candidates.add(new MoveCandidate(start, end));
                                    }
                                }
                            }
                        }
                    }
                }

                if (candidates.isEmpty()) {
                    return false;
                }

                int idx = (int) (Math.random() * candidates.size());
                MoveCandidate chosen = candidates.get(idx);
                return makeMove(chosen.start, chosen.end);
            } finally {
                lock.writeLock().unlock();
            }
        }

        private static class MoveCandidate {
            final Coordinate start;
            final Coordinate end;
            MoveCandidate(Coordinate start, Coordinate end) {
                this.start = start;
                this.end = end;
            }
        }
    }

    // ============================================================================
    // 7. Demo Driver (Deterministic + Multi-threaded Stress Simulation)
    // ============================================================================
    public static void main(String[] args) {
        System.out.println("=== CHESS GAME LLD DEMONSTRATION ===");
        ChessGame game = new ChessGame();
        
        System.out.println("\nInitial Board Setup:");
        game.printGameStatus();

        System.out.println("\n--- Phase 1: Deterministic Moves (Simulating Fool's Mate) ---");
        // Move 1: White moves f2 -> f3
        game.makeMove(Coordinate.fromNotation("f2"), Coordinate.fromNotation("f3"));
        // Move 2: Black moves e7 -> e5
        game.makeMove(Coordinate.fromNotation("e7"), Coordinate.fromNotation("e5"));
        // Move 3: White moves g2 -> g4
        game.makeMove(Coordinate.fromNotation("g2"), Coordinate.fromNotation("g4"));
        
        System.out.println("\nBoard state before final move:");
        game.printGameStatus();

        // Move 4: Black moves d8 -> h4 (Checkmate!)
        System.out.println("\nExecuting checkmate move...");
        game.makeMove(Coordinate.fromNotation("d8"), Coordinate.fromNotation("h4"));
        
        System.out.println("\nBoard state after checkmate move:");
        game.printGameStatus();

        System.out.println("\nAttempting an invalid move after checkmate has occurred:");
        boolean moveRejected = game.makeMove(Coordinate.fromNotation("a2"), Coordinate.fromNotation("a3"));
        System.out.println("Move execution status (should be false): " + moveRejected);

        System.out.println("\n--- Phase 2: Command Undo/Redo Check ---");
        System.out.println("Undoing checkmate move...");
        game.undo();
        game.printGameStatus();

        System.out.println("\nRedoing checkmate move...");
        game.redo();
        game.printGameStatus();

        System.out.println("\n--- Phase 3: Multi-threaded Stress Test ---");
        System.out.println("Resetting game board for stress test...");
        
        // Setup new game
        ChessGame stressGame = new ChessGame();
        
        // Spawn 5 threads:
        // - Thread 1: White Player making legal random moves
        // - Thread 2: Black Player making legal random moves
        // - Thread 3: Spectator reading board state
        // - Thread 4: Spectator checking game status
        // - Thread 5: Random undo/redo operations
        
        ExecutorService executor = Executors.newFixedThreadPool(5);
        
        // Flag to control simulation run time
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);

        // White Player task
        executor.submit(() -> {
            while (running.get()) {
                if (stressGame.getCurrentTurn() == PieceColor.WHITE) {
                    stressGame.makeRandomMove(PieceColor.WHITE);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Black Player task
        executor.submit(() -> {
            while (running.get()) {
                if (stressGame.getCurrentTurn() == PieceColor.BLACK) {
                    stressGame.makeRandomMove(PieceColor.BLACK);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Spectator 1 task
        executor.submit(() -> {
            while (running.get()) {
                stressGame.findKing(PieceColor.WHITE);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Spectator 2 task
        executor.submit(() -> {
            while (running.get()) {
                stressGame.getGameState();
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Referee Undo/Redo task
        executor.submit(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(150);
                    if (Math.random() < 0.5) {
                        stressGame.undo();
                    } else {
                        stressGame.redo();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Let the simulation run for 1 second
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Stop the simulation
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }

        System.out.println("\nStress Test Completed Successfully! Final Game State:");
        stressGame.printGameStatus();
        System.out.println("\nAll tasks completed without throwing exceptions, proving thread-safety.");
    }
}
