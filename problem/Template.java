import java.io.*;
import java.util.*;

public class Template {

    public static class Pair {
        public int first;
        public int second;

        Pair(int first, int second) {
            this.first = first;
            this.second = second;
        }
    }

    public static void main(String[] args) {
        FastReader in = new FastReader();
        PrintWriter out = new PrintWriter(new BufferedOutputStream(System.out));
        
        int t = 1;
        // Uncomment the line below if there are multiple test cases
        // t = in.nextInt();
        while (t-- > 0) {
            solve(in, out);
        }
        
        out.close();
    }

    private static void solve(FastReader in, PrintWriter out) {
        int node, connection, animeShop;
        Queue<Pair> q = new LinkedList<>();
    
        node = in.nextInt();
        connection = in.nextInt();
        animeShop = in.nextInt();

        int[] shops = new int[animeShop];
        int[] minDistance = new int[node + 1];
        // int[] parent = new int[node+1];
        Arrays.fill(minDistance, Integer.MAX_VALUE);
        // Arrays.fill(parent, -1);

        for (int i = 0; i < animeShop; i++) {
            shops[i] = in.nextInt();
            minDistance[shops[i]] = 0;
            q.add(new Pair(shops[i], 0)); // Add the shop node with distance 0 to the queue
        }

        List<List<Integer>> graph = new ArrayList<>();
        for (int i = 0; i <= node; i++) {
            graph.add(new ArrayList<>()); 
        }

        for (int i = 0; i < connection; i++) {
            int u = in.nextInt();
            int v = in.nextInt();
            graph.get(u).add(v);
            graph.get(v).add(u);
        }

        while (!q.isEmpty()) {
            int n = q.size();
            for (int i = n - 1; i >= 0; i--) {
                Pair nodeDistance = q.poll();
                int parent = nodeDistance.first;
                int distance = nodeDistance.second;

                for (int j = 0; j < graph.get(parent).size(); j++) {
                    int child = graph.get(parent).get(j);
                    if (minDistance[child] == Integer.MAX_VALUE) {
                        minDistance[child] = distance + 1;
                        q.add(new Pair(child, distance + 1));
                    }
                }
            }
        }

        for (int i = 1; i <= node; i++) {
            if (minDistance[i] == Integer.MAX_VALUE) {
                out.print(-1 + " ");
            } else {
                out.print(minDistance[i] + " ");
            }
        }
        out.println();
    }

    static class FastReader {
        BufferedReader br;
        StringTokenizer st;

        public FastReader() {
            br = new BufferedReader(new InputStreamReader(System.in));
        }

        String next() {
            while (st == null || !st.hasMoreElements()) {
                try {
                    String line = br.readLine();
                    if (line == null) {
                        return null;
                    }
                    st = new StringTokenizer(line);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return st.nextToken();
        }

        int nextInt() {
            return Integer.parseInt(next());
        }

        long nextLong() {
            return Long.parseLong(next());
        }

        double nextDouble() {
            return Double.parseDouble(next());
        }

        String nextLine() {
            String str = "";
            try {
                if (st != null && st.hasMoreTokens()) {
                    str = st.nextToken("\n");
                } else {
                    str = br.readLine();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return str;
        }
    }
}
