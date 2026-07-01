import java.util.*;
import java.util.stream.Collectors;

/**
 * Google Sheets LLD — Runnable Demo
 * Demonstrates: Observer (dependency recalc), Command (undo/redo), Strategy (formula functions),
 *               DAG-based recalculation, circular reference detection.
 *
 * Compile & Run:
 *   javac GoogleSheetsDemo.java && java GoogleSheetsDemo
 */
public class GoogleSheetsDemo {

    // ─── Cell ────────────────────────────────────────────────────────────────

    static class Cell {
        private final String cellRef;
        private String rawValue;
        private Object computedValue;
        private boolean isFormula;
        private List<String> dependencies = new ArrayList<>();

        Cell(String cellRef) {
            this.cellRef = cellRef;
            this.rawValue = "";
            this.computedValue = "";
            this.isFormula = false;
        }

        String getCellRef() { return cellRef; }
        String getRawValue() { return rawValue; }
        Object getComputedValue() { return computedValue; }
        boolean isFormula() { return isFormula; }
        List<String> getDependencies() { return dependencies; }

        void setRawValue(String value) { this.rawValue = value; }
        void setComputedValue(Object value) { this.computedValue = value; }
        void setFormula(boolean f) { this.isFormula = f; }
        void setDependencies(List<String> deps) { this.dependencies = deps; }

        @Override
        public String toString() {
            if (isFormula) return cellRef + " = " + rawValue + " → " + computedValue;
            return cellRef + " = " + computedValue;
        }
    }

    // ─── Dependency Graph ────────────────────────────────────────────────────

    static class DependencyGraph {
        // A1 → {C1, E1} means "C1 and E1 depend on A1"
        private final Map<String, Set<String>> dependents = new HashMap<>();
        // C1 → {A1, B1} means "C1's formula references A1 and B1"
        private final Map<String, Set<String>> dependencies = new HashMap<>();

        void addDependency(String cell, String dependsOn) {
            dependencies.computeIfAbsent(cell, k -> new HashSet<>()).add(dependsOn);
            dependents.computeIfAbsent(dependsOn, k -> new HashSet<>()).add(cell);
        }

        void removeDependencies(String cell) {
            Set<String> deps = dependencies.remove(cell);
            if (deps != null) {
                for (String dep : deps) {
                    Set<String> set = dependents.get(dep);
                    if (set != null) { set.remove(cell); }
                }
            }
        }

        Set<String> getDependents(String cell) {
            return dependents.getOrDefault(cell, Collections.emptySet());
        }

        /**
         * Check if adding newDeps for 'cell' would create a cycle.
         * DFS from each dependency to see if it can reach back to 'cell'.
         */
        boolean wouldCauseCycle(String cell, List<String> newDeps) {
            for (String dep : newDeps) {
                if (dep.equals(cell)) return true;
                if (canReach(dep, cell, new HashSet<>())) return true;
            }
            return false;
        }

        private boolean canReach(String from, String target, Set<String> visited) {
            if (from.equals(target)) return true;
            if (!visited.add(from)) return false;
            for (String dep : dependencies.getOrDefault(from, Collections.emptySet())) {
                if (canReach(dep, target, visited)) return true;
            }
            return false;
        }

        /**
         * BFS to get all cells that need recalculation in topological order.
         */
        List<String> getRecalcOrder(String changedCell) {
            // 1. Find all cells affected by the change (transitive dependents)
            Set<String> affected = new HashSet<>();
            dfsFindAffected(changedCell, affected);
            affected.remove(changedCell); // We don't recalculate the changed cell itself

            // 2. Calculate in-degrees within the affected subgraph
            Map<String, Integer> inDegree = new HashMap<>();
            for (String node : affected) {
                inDegree.put(node, 0);
            }

            for (String node : affected) {
                // Incoming edges are dependencies that are also in the affected set
                Set<String> deps = dependencies.getOrDefault(node, Collections.emptySet());
                for (String dep : deps) {
                    if (affected.contains(dep)) {
                        inDegree.put(node, inDegree.get(node) + 1);
                    }
                }
            }

            // 3. Kahn's Algorithm: Queue all nodes with in-degree 0 within the affected subgraph
            Queue<String> queue = new LinkedList<>();
            for (String node : affected) {
                if (inDegree.get(node) == 0) {
                    queue.add(node);
                }
            }

            List<String> order = new ArrayList<>();
            while (!queue.isEmpty()) {
                String u = queue.poll();
                order.add(u);
                for (String v : getDependents(u)) {
                    if (affected.contains(v)) {
                        inDegree.put(v, inDegree.get(v) - 1);
                        if (inDegree.get(v) == 0) {
                            queue.add(v);
                        }
                    }
                }
            }
            return order;
        }

        private void dfsFindAffected(String cell, Set<String> visited) {
            if (!visited.add(cell)) return;
            for (String dep : getDependents(cell)) {
                dfsFindAffected(dep, visited);
            }
        }
    }

    // ─── Formula Parser ──────────────────────────────────────────────────────

    static class FormulaParser {
        /**
         * Extracts cell references from a formula string.
         * Handles: =A1+B1, =SUM(A1:A5), =A1*2+B2
         */
        static List<String> extractCellRefs(String formula, Map<String, Cell> cells) {
            String expr = formula.substring(1); // Remove '='
            List<String> refs = new ArrayList<>();

            // Handle range functions like SUM(A1:A5)
            java.util.regex.Matcher rangeMatcher =
                java.util.regex.Pattern.compile("([A-Z]+\\d+):([A-Z]+\\d+)").matcher(expr);
            Set<String> rangeRefs = new HashSet<>();
            while (rangeMatcher.find()) {
                List<String> expanded = expandRange(rangeMatcher.group(1), rangeMatcher.group(2));
                rangeRefs.addAll(expanded);
                refs.addAll(expanded);
            }

            // Handle individual cell refs like A1, B2
            java.util.regex.Matcher cellMatcher =
                java.util.regex.Pattern.compile("([A-Z]+\\d+)").matcher(expr);
            while (cellMatcher.find()) {
                String ref = cellMatcher.group(1);
                if (!rangeRefs.contains(ref)) {
                    refs.add(ref);
                }
            }

            return refs.stream().distinct().collect(Collectors.toList());
        }

        static List<String> expandRange(String start, String end) {
            char startCol = start.charAt(0), endCol = end.charAt(0);
            int startRow = Integer.parseInt(start.substring(1));
            int endRow = Integer.parseInt(end.substring(1));
            List<String> result = new ArrayList<>();
            for (char c = startCol; c <= endCol; c++) {
                for (int r = startRow; r <= endRow; r++) {
                    result.add("" + c + r);
                }
            }
            return result;
        }
    }

    // ─── Strategy: Formula Functions ─────────────────────────────────────────

    interface FormulaFunction {
        double apply(List<Double> values);
    }

    // ─── Formula Evaluator ───────────────────────────────────────────────────

    static class FormulaEvaluator {
        private final Map<String, FormulaFunction> functions = new HashMap<>();

        FormulaEvaluator() {
            functions.put("SUM", vals -> vals.stream().mapToDouble(d -> d).sum());
            functions.put("AVG", vals -> vals.stream().mapToDouble(d -> d).average().orElse(0));
            functions.put("MIN", vals -> vals.stream().mapToDouble(d -> d).min().orElse(0));
            functions.put("MAX", vals -> vals.stream().mapToDouble(d -> d).max().orElse(0));
            functions.put("COUNT", vals -> (double) vals.size());
        }

        Object evaluate(String formula, Map<String, Cell> cells) {
            String expr = formula.substring(1); // Remove '='

            // Check for function calls: SUM(A1:A3), AVG(A1:A5)
            java.util.regex.Matcher fnMatcher =
                java.util.regex.Pattern.compile("(SUM|AVG|MIN|MAX|COUNT)\\(([A-Z]+\\d+):([A-Z]+\\d+)\\)")
                    .matcher(expr);
            if (fnMatcher.matches()) {
                String fnName = fnMatcher.group(1);
                List<String> range = FormulaParser.expandRange(fnMatcher.group(2), fnMatcher.group(3));
                List<Double> values = range.stream()
                    .map(ref -> getCellNumericValue(ref, cells))
                    .collect(Collectors.toList());
                return functions.get(fnName).apply(values);
            }

            // Simple arithmetic: =A1+B1, =A1*2, =A1+10
            // Replace cell refs with their numeric values
            String evalExpr = expr;
            java.util.regex.Matcher cellMatcher =
                java.util.regex.Pattern.compile("([A-Z]+\\d+)").matcher(expr);
            // Sort refs by length desc to avoid partial replacements (e.g., A10 before A1)
            List<String> refs = new ArrayList<>();
            while (cellMatcher.find()) refs.add(cellMatcher.group(1));
            refs.sort((a, b) -> b.length() - a.length());
            for (String ref : refs) {
                evalExpr = evalExpr.replace(ref, String.valueOf(getCellNumericValue(ref, cells)));
            }
            return evalSimpleExpression(evalExpr);
        }

        private double getCellNumericValue(String ref, Map<String, Cell> cells) {
            Cell cell = cells.get(ref);
            if (cell == null) return 0.0;
            Object val = cell.getComputedValue();
            if (val instanceof Number) return ((Number) val).doubleValue();
            try { return Double.parseDouble(val.toString()); }
            catch (NumberFormatException e) { return 0.0; }
        }

        /**
         * Evaluates simple arithmetic expressions with +, -, *, /
         * (Supports basic two-operand expressions for demo purposes)
         */
        private double evalSimpleExpression(String expr) {
            expr = expr.trim();
            // Handle addition/subtraction (lowest precedence)
            int pos = -1;
            int depth = 0;
            for (int i = expr.length() - 1; i >= 0; i--) {
                char c = expr.charAt(i);
                if (c == ')') depth++;
                else if (c == '(') depth--;
                else if (depth == 0 && (c == '+' || c == '-') && i > 0) {
                    pos = i;
                    break;
                }
            }
            if (pos > 0) {
                double left = evalSimpleExpression(expr.substring(0, pos));
                double right = evalSimpleExpression(expr.substring(pos + 1));
                return expr.charAt(pos) == '+' ? left + right : left - right;
            }
            // Handle multiplication/division
            for (int i = expr.length() - 1; i >= 0; i--) {
                char c = expr.charAt(i);
                if (c == ')') depth++;
                else if (c == '(') depth--;
                else if (depth == 0 && (c == '*' || c == '/') && i > 0) {
                    pos = i;
                    break;
                }
            }
            if (pos > 0) {
                double left = evalSimpleExpression(expr.substring(0, pos));
                double right = evalSimpleExpression(expr.substring(pos + 1));
                return expr.charAt(pos) == '*' ? left * right : left / right;
            }
            // Base case: parse number
            return Double.parseDouble(expr);
        }
    }

    // ─── Command Pattern: Undo/Redo ──────────────────────────────────────────

    interface Command {
        void execute();
        void undo();
        String describe();
    }

    static class SetCellCommand implements Command {
        private final Spreadsheet sheet;
        private final String cellRef;
        private final String newValue;
        private String oldRawValue;
        private Object oldComputedValue;
        private boolean oldIsFormula;
        private List<String> oldDependencies;

        SetCellCommand(Spreadsheet sheet, String cellRef, String newValue) {
            this.sheet = sheet; this.cellRef = cellRef; this.newValue = newValue;
        }

        @Override
        public void execute() {
            Cell cell = sheet.getOrCreateCell(cellRef);
            // Save old state for undo
            this.oldRawValue = cell.getRawValue();
            this.oldComputedValue = cell.getComputedValue();
            this.oldIsFormula = cell.isFormula();
            this.oldDependencies = new ArrayList<>(cell.getDependencies());
            // Apply new value
            sheet.internalSetCell(cellRef, newValue);
        }

        @Override
        public void undo() {
            sheet.internalSetCell(cellRef, oldRawValue);
        }

        @Override
        public String describe() {
            return "SET " + cellRef + " = " + newValue + " (was: " + oldRawValue + ")";
        }
    }

    static class CommandHistory {
        private final Deque<Command> undoStack = new ArrayDeque<>();
        private final Deque<Command> redoStack = new ArrayDeque<>();

        void executeCommand(Command cmd) {
            cmd.execute();
            undoStack.push(cmd);
            redoStack.clear();
        }

        boolean undo() {
            if (undoStack.isEmpty()) return false;
            Command cmd = undoStack.pop();
            cmd.undo();
            redoStack.push(cmd);
            return true;
        }

        boolean redo() {
            if (redoStack.isEmpty()) return false;
            Command cmd = redoStack.pop();
            cmd.execute();
            undoStack.push(cmd);
            return true;
        }

        String lastAction() {
            return undoStack.isEmpty() ? "(none)" : undoStack.peek().describe();
        }
    }

    // ─── Spreadsheet Engine ──────────────────────────────────────────────────

    static class Spreadsheet {
        private final Map<String, Cell> cells = new LinkedHashMap<>();
        private final DependencyGraph graph = new DependencyGraph();
        private final FormulaEvaluator evaluator = new FormulaEvaluator();
        private final CommandHistory history = new CommandHistory();

        void setCellValue(String cellRef, String value) {
            history.executeCommand(new SetCellCommand(this, cellRef, value));
        }

        Cell getOrCreateCell(String cellRef) {
            return cells.computeIfAbsent(cellRef, Cell::new);
        }

        /**
         * Internal method called by Command.execute() and Command.undo().
         * Sets cell value, updates dependencies, evaluates formulas, propagates recalculation.
         */
        void internalSetCell(String cellRef, String value) {
            Cell cell = getOrCreateCell(cellRef);

            // Remove old dependencies
            graph.removeDependencies(cellRef);

            if (value != null && value.startsWith("=")) {
                // Formula cell
                List<String> deps = FormulaParser.extractCellRefs(value, cells);

                // Circular reference detection
                if (graph.wouldCauseCycle(cellRef, deps)) {
                    cell.setRawValue(value);
                    cell.setComputedValue("#CIRCULAR_REF!");
                    cell.setFormula(true);
                    cell.setDependencies(Collections.emptyList());
                    System.out.println("  ⚠ CIRCULAR REFERENCE detected for " + cellRef + " — rejected.");
                    return;
                }

                // Register dependencies
                cell.setFormula(true);
                cell.setDependencies(deps);
                for (String dep : deps) {
                    getOrCreateCell(dep); // Ensure referenced cell exists
                    graph.addDependency(cellRef, dep);
                }

                // Evaluate formula
                cell.setRawValue(value);
                Object result = evaluator.evaluate(value, cells);
                cell.setComputedValue(result);
            } else {
                // Raw value cell
                cell.setFormula(false);
                cell.setDependencies(Collections.emptyList());
                cell.setRawValue(value == null ? "" : value);
                try {
                    cell.setComputedValue(Double.parseDouble(value));
                } catch (Exception e) {
                    cell.setComputedValue(value);
                }
            }

            // Propagate recalculation to all dependent cells (Observer/DAG walk)
            List<String> recalcOrder = graph.getRecalcOrder(cellRef);
            for (String depCellRef : recalcOrder) {
                Cell depCell = cells.get(depCellRef);
                if (depCell != null && depCell.isFormula()) {
                    Object result = evaluator.evaluate(depCell.getRawValue(), cells);
                    depCell.setComputedValue(result);
                }
            }
        }

        Object getCellValue(String cellRef) {
            Cell cell = cells.get(cellRef);
            return cell == null ? "" : cell.getComputedValue();
        }

        void undo() { history.undo(); }
        void redo() { history.redo(); }

        void printSheet(String... cellRefs) {
            System.out.println("  ┌──────────────────────────────────────────┐");
            for (String ref : cellRefs) {
                Cell cell = cells.get(ref);
                if (cell != null) {
                    String display = cell.isFormula()
                        ? ref + " = " + cell.getRawValue() + " → " + cell.getComputedValue()
                        : ref + " = " + cell.getComputedValue();
                    System.out.println("  │  " + display);
                }
            }
            System.out.println("  └──────────────────────────────────────────┘");
        }
    }

    // ─── Main Demo ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       Google Sheets — LLD Demo                  ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        Spreadsheet sheet = new Spreadsheet();

        // ─── Scenario 1: Basic cell values ──────────────────────────────
        System.out.println("\n--- Scenario 1: Set raw values ---");
        sheet.setCellValue("A1", "10");
        sheet.setCellValue("A2", "20");
        sheet.setCellValue("A3", "30");
        sheet.setCellValue("B1", "5");
        sheet.printSheet("A1", "A2", "A3", "B1");

        // ─── Scenario 2: Simple formula ─────────────────────────────────
        System.out.println("\n--- Scenario 2: Formula C1 = A1 + B1 ---");
        sheet.setCellValue("C1", "=A1+B1");
        sheet.printSheet("A1", "B1", "C1");

        // ─── Scenario 3: Chained formulas (DAG propagation) ─────────────
        System.out.println("\n--- Scenario 3: D1 = C1 * 2 (chained dependency) ---");
        sheet.setCellValue("D1", "=C1*2");
        sheet.printSheet("A1", "B1", "C1", "D1");

        // ─── Scenario 4: Change A1 → cascading recalculation ────────────
        System.out.println("\n--- Scenario 4: Change A1 from 10 to 50 → cascading recalc ---");
        sheet.setCellValue("A1", "50");
        System.out.println("  Expected: C1 = 50+5 = 55, D1 = 55*2 = 110");
        sheet.printSheet("A1", "B1", "C1", "D1");

        // ─── Scenario 5: Range function SUM ─────────────────────────────
        System.out.println("\n--- Scenario 5: E1 = SUM(A1:A3) ---");
        sheet.setCellValue("E1", "=SUM(A1:A3)");
        System.out.println("  Expected: SUM(50, 20, 30) = 100");
        sheet.printSheet("A1", "A2", "A3", "E1");

        // ─── Scenario 6: AVG function ───────────────────────────────────
        System.out.println("\n--- Scenario 6: F1 = AVG(A1:A3) ---");
        sheet.setCellValue("F1", "=AVG(A1:A3)");
        System.out.println("  Expected: AVG(50, 20, 30) = 33.33...");
        sheet.printSheet("F1");

        // ─── Scenario 7: Circular reference detection ───────────────────
        System.out.println("\n--- Scenario 7: Circular reference — A1 = =C1 (C1 depends on A1) ---");
        sheet.setCellValue("A1", "=C1");
        sheet.printSheet("A1");

        // Restore A1 to a safe value
        sheet.setCellValue("A1", "50");

        // ─── Scenario 8: Undo / Redo ────────────────────────────────────
        System.out.println("\n--- Scenario 8: Undo (restore A1 to previous) ---");
        System.out.println("  Before undo: A1 = " + sheet.getCellValue("A1"));
        sheet.undo();
        System.out.println("  After undo:  A1 = " + sheet.getCellValue("A1"));
        System.out.println("  (Undid the A1=50 set, so A1 is now the circular ref error)");

        System.out.println("\n--- Redo (re-apply A1 = 50) ---");
        sheet.redo();
        System.out.println("  After redo:  A1 = " + sheet.getCellValue("A1"));
        System.out.println("  C1 = " + sheet.getCellValue("C1") + ", D1 = " + sheet.getCellValue("D1"));

        // ─── Scenario 9: Update propagates through SUM ──────────────────
        System.out.println("\n--- Scenario 9: Change A2 = 100 → SUM(A1:A3) should update ---");
        sheet.setCellValue("A2", "100");
        System.out.println("  Expected: E1 = SUM(50, 100, 30) = 180");
        sheet.printSheet("A2", "E1", "F1");

        // ─── Scenario 10: MAX and MIN ───────────────────────────────────
        System.out.println("\n--- Scenario 10: G1 = MAX(A1:A3), H1 = MIN(A1:A3) ---");
        sheet.setCellValue("G1", "=MAX(A1:A3)");
        sheet.setCellValue("H1", "=MIN(A1:A3)");
        System.out.println("  Expected: MAX(50,100,30)=100, MIN(50,100,30)=30");
        sheet.printSheet("G1", "H1");

        System.out.println("\n✓ Demo complete.");
    }
}
