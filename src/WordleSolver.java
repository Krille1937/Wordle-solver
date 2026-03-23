import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 */
public class WordleSolver {
    private static final int ENTROPY_THRESHOLD = 500;

    private static final int TOP_N = 7;

    // Word Data

    private final String[] allWords;

    /**
     * letterCounts[i][c] = number of times letter c appears in allWords[i].
     * byte[] since the max value is 5
     */
    private final byte[][] letterCounts;

    // Constraint State

    private final char[] green = new char[5];


    private final int[] minCount = new int[26];

    private final int[] maxCount = new int[26];

    private final boolean[][] forbidden = new boolean[5][26];

    // Candidate Tracking

    private int[] remainingIdx;
    private int remainingSize;

    // Scratch Space

    private final int [] patternScratch = new int[26];

    private final int[] buckets = new int[243];

    private final boolean[] seen = new boolean[26];

    // Session Statistics
    private int guessCount = 0;
    private String scoringMode = "frequency";

    // Constructor

    public WordleSolver(List<String> wordList) {
        int n           = wordList.size();
        allWords        = wordList.toArray(new String[0]);
        letterCounts    = new byte[n][26];

        for (int i = 0; i < n; i++) {
            String w = allWords[i];
            for (int p = 0; p < 5; p++) letterCounts[i][w.charAt(p) - 'a']++;
        }

        remainingIdx = IntStream.range(0, n).toArray();
        remainingSize = n;
        Arrays.fill(maxCount, 5);
    }

    // Applying a Guess

    public boolean applyGuess(String guess, String result) {
        guess = guess.toLowerCase().trim();
        result = result.toLowerCase().trim();

        if (!guess.matches("[a-z]{5}")) {
            System.out.println("  ⚠  Guess must be exactly 5 letters (a-z).");
            return false;
        }
        if (!result.matches("[GYX]{5}")) {
            System.out.println("  ⚠  Result must be exactly 5 characters: G, Y, or X only.");
            return false;
        }

        int[] confirmed = new int[26];
        for (int i = 0; i < 5; i++) {
            int c = guess.charAt(i) - 'a';
            if (result.charAt(i) != 'X') confirmed[c]++;
        }

        for (int i = 0; i < 5; i++) {
            int c = guess.charAt(i) - 'a';
            char clue = result.charAt(i);
            switch (clue) {
                case 'G' -> {
                    green[i] = guess.charAt(i);
                    minCount[c] = Math.max(minCount[c], confirmed[c]);
                }
                case 'Y' -> {
                    forbidden[i][c] = true;
                    minCount[c] = Math.max(minCount[c],  confirmed[c]);
                }
                case 'X' ->
                    maxCount[c] = Math.min(maxCount[c], confirmed[c]);

            }
        }

        refilter();
        guessCount++;
        return true;
    }

    // Fast Candidate Filter

    private void refilter() {
        int write = 0;
        for (int ri = 0; ri < remainingSize; ri++) {
            int idx = remainingIdx[ri];
            if (matches(idx)) remainingIdx[write++] = idx;
        }
        remainingSize = write;
    }

    private boolean matches(int idx) {
        String w = allWords[idx];
        byte[] freq = letterCounts[idx];

        for (int p = 0; p < 5; p++)
            if (green[p] != 0 && w.charAt(p) != green[p]) return false;

        for (int p = 0; p < 5; p++)
            if (forbidden[p][w.charAt(p) - 'a']) return false;

        for (int c = 0; c < 26; c++) {
            if (freq[c] < minCount[c]) return false;
            if (freq[c] > maxCount[c]) return false;
        }

        return true;
    }

    // Pattern Computation

    /**
     * Computes the Wordle result pattern for (guess, answer) as a base-3 integer.
     * Encoding: G=2, Y=1, X=0; value = Σ result[i] · 3^(4-i)
     *
     * Duplicate-letter handling is correct:
     *  1. Mark greens first, subtract from available answer letters.
     *  2. For non-green positions, mark yellow only if the answer still has
     *     available copies of that letter (prevents over-marking).
     *
     * Uses this.patternScratch as a reusable int[26] no heap allocation.
     */
    int computePattern(int gIdx, int aIdx) {
        String g = allWords[gIdx];
        String a = allWords[aIdx];
        int[] avail = patternScratch;

        // Build all available counts from the answer
        avail[a.charAt(0)-'a'] = 0; avail[a.charAt(1)-'a'] = 0;
        avail[a.charAt(2)-'a'] = 0; avail[a.charAt(3)-'a'] = 0;
        avail[a.charAt(4)-'a'] = 0;
        avail[a.charAt(0)-'a']++; avail[a.charAt(1)-'a']++;
        avail[a.charAt(2)-'a']++; avail[a.charAt(3)-'a']++;
        avail[a.charAt(4)-'a']++;

        int r0=0, r1=0, r2=0, r3=0, r4=0;
        int gc0=g.charAt(0)-'a', gc1=g.charAt(1)-'a', gc2=g.charAt(2)-'a',
            gc3=g.charAt(3)-'a', gc4=g.charAt(4)-'a';
        int ac0=a.charAt(0)-'a', ac1=a.charAt(1)-'a', ac2=a.charAt(2)-'a',
            ac3=a.charAt(3)-'a', ac4=a.charAt(4)-'a';

        // Pass 1: greens consume all available letter slots
        if (gc0 == ac0) { r0=2; avail[gc0]--; }
        if (gc1 == ac1) { r1=2; avail[gc1]--; }
        if (gc2 == ac2) { r2=2; avail[gc2]--; }
        if (gc3 == ac3) { r3=2; avail[gc3]--; }
        if (gc4 == ac4) { r4=2; avail[gc4]--; }

        // Pass 2: yellows (only if available slots remain)
        if (r0==0 && avail[gc0]>0) { r0=1; avail[gc0]--; }
        if (r1==0 && avail[gc1]>0) { r1=1; avail[gc1]--; }
        if (r2==0 && avail[gc2]>0) { r2=1; avail[gc2]--; }
        if (r3==0 && avail[gc3]>0) { r3=1; avail[gc3]--; }
        if (r4==0 && avail[gc4]>0) { r4=1; avail[gc4]--; }

        return r0*81 + r1*27 + r2*9 + r3*3 + r4;
    }

    // Recommendation Engine

    public List<ScoredWord> getRecommendations(int topN) {
        if (remainingSize == 0) return Collections.emptyList();
        if (remainingSize == 1) {
            scoringMode = "solved";
            return List.of(new ScoredWord(allWords[remainingIdx[0]],
                    Double.MAX_VALUE, 0, 1, true));
        }

        boolean useEntropy = remainingSize <= ENTROPY_THRESHOLD;
        scoringMode = useEntropy ? "entropy" : "frequency";

        return useEntropy ? recommendByEntropy(topN) : recommendByFrequency(topN);
    }

    private List<ScoredWord> recommendByEntropy(int topN) {
        boolean[] isPossible = new boolean[allWords.length];
        for (int ri = 0; ri < remainingSize; ri++) isPossible[remainingIdx[ri]] = true;

        double R = remainingSize;
        double logR = Math.log(R);

        // Min-heap: keep top K by entropy (ascending order so poll removes lowest)
        Comparator<ScoredWord> heapCmp = Comparator.comparing(ScoredWord::score)
                .thenComparing(sw -> sw.isPossibleAnswer() ? 1 : 0);
        PriorityQueue<ScoredWord> heap = new PriorityQueue<>(topN + 1, heapCmp);

        for (int gi = 0; gi < allWords.length; gi++) {
            // Fill buckets: bucket[pattern] = count of remaining words that produce this pattern
            Arrays.fill(buckets, 0);
            for (int ri = 0; ri < remainingSize; ri++) {
                buckets[computePattern(gi, remainingIdx[ri])]++;
            }

            // Entropy H = Σ (count · log(R/count)) / R
            double entropy = 0;
            int maxBucket = 0;
            double expectedRemaining = 0;
            for (int b = 0; b < 243; b++) {
                int c = buckets[b];
                if (c > 0) {
                    entropy += c * (logR - Math.log(c));
                    expectedRemaining += (double) c * c;
                    if (c > maxBucket) maxBucket = c;
                }
            }
            entropy /= R;
            expectedRemaining /= R;

            ScoredWord sw = new ScoredWord(
                    allWords[gi], entropy, expectedRemaining, maxBucket, isPossible[gi]);
            heap.offer(sw);
            if (heap.size() > topN) heap.poll();
        }

        List<ScoredWord> result = new ArrayList<>(heap);
        result.sort(Comparator.comparingDouble(ScoredWord::score).reversed()
                .thenComparing(sw -> sw.isPossibleAnswer() ? 1 : 0));
        return result;
    }

    private List<ScoredWord> recommendByFrequency(int topN) {
        // Letter frequency across remaining candidates
        int[] freq = new int[26];
        for (int ri = 0; ri < remainingSize; ri++) {
            byte[] lc = letterCounts[remainingIdx[ri]];
            for (int c = 0; c < 26; c++) if (lc[c] > 0) freq[c]++;
        }

        boolean[] isPossible = new boolean[allWords.length];
        for (int ri = 0; ri < remainingSize; ri++) isPossible[remainingIdx[ri]] = true;

        Comparator<ScoredWord> heapCmp = Comparator.comparingDouble(ScoredWord::score)
                .thenComparing(sw -> sw.isPossibleAnswer() ? 1 : 0);
        PriorityQueue<ScoredWord> heap = new PriorityQueue<>(topN + 1, heapCmp);

        for (int i = 0; i < allWords.length; i++) {
            String w = allWords[i];
            double sc = 0;
            for (int p = 0; p < 5; p++) {
                int c = w.charAt(p) - 'a';
                if (!seen[c]) { seen[c] = true; sc += freq[c]; }
            }
            for (int p = 0; p < 5; p++) seen[w.charAt(p) - 'a'] = false; // Fast reset

            double expRem = remainingSize - sc; // rough proxy
            heap.offer(new ScoredWord(w, sc, expRem, -1, isPossible[i]));
            if (heap.size() > topN) heap.poll();
        }

        List<ScoredWord> result = new ArrayList<>(heap);
        result.sort(Comparator.comparingDouble(ScoredWord::score).reversed()
                .thenComparing(sw -> sw.isPossibleAnswer() ? 1 : 0));
        return result;
    }

    // Letter Frequency Analysis

    public int[] getOverallFrequency() {
        int[] freq = new int[26];
        for (int ri = 0; ri < remainingSize; ri++) {
            byte[] lc = letterCounts[remainingIdx[ri]];
            for (int c = 0; c < 26; c++) if (lc[c] > 0) freq[c]++;
        }
        return freq;
    }

    public int[][] getPositionalFrequency() {
        int[][] pf = new int[5][26];
        for (int ri = 0; ri < remainingSize; ri++) {
            String w = allWords[remainingIdx[ri]];
            for (int p = 0; p < 5; p++) pf[p][w.charAt(p) - 'a']++;
        }
        return pf;
    }

    // Getters

    public List<String> getRemaining() {
        List<String> out = new ArrayList<>(remainingSize);
        for (int ri = 0; ri < remainingSize; ri++) out.add(allWords[remainingIdx[ri]]);
        return out;
    }
    public int remainingCount()     { return remainingSize; }
    public int guessCount()         { return remainingSize; }
    public String scoringMode()     { return scoringMode; }


    // Data Record

    public record ScoredWord(
            String word, double score, double expectedRemaining,
            int worstCase, boolean isPossibleAnswer) {}

    // Word Loader

    public static List<String> loadWords(String filePath) throws IOException {
        return Files.lines(Paths.get(filePath))
                .map(String::trim).map(String::toLowerCase)
                .filter(w -> w.matches("[a-z]{5}"))
                .distinct().sorted()
                .collect(Collectors.toList());
    }

    // Main / CLI

    public static void main(String[] args) throws IOException {
        System.out.println("╔═══════════════════════════════════════════════╗");
        System.out.println("║      🟩   WORDLE SOLVER   🟩                 ║");
        System.out.println("║      Now with Entropy Scoring                 ║");
        System.out.println("╚═══════════════════════════════════════════════╝");

        String wordFile = args.length > 0 ? args[0] : "words.txt";
        List<String> words;
        try {
            words = loadWords(wordFile);
            System.out.printf("✅  Loaded %,d words from '%s'%n", words.size(), wordFile);
        } catch (IOException e) {
            System.err.println("❌  Could not load '" + wordFile + "': " + e.getMessage());
            System.exit(1);
            return;
        }

        long t0 = System.currentTimeMillis();
        WordleSolver solver = new WordleSolver(words);
        System.out.printf("⚡  Pre-computed letter data in %dms%n%n",
                System.currentTimeMillis() - t0);

        printHelp();

        System.out.println("Computing opening recommendations...");
        showRecommendations(solver);

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\n─────────────────────────────────────────────────");
            System.out.print("Guess (or: analyze / list / reset / quit): ");
            String input = scanner.nextLine().trim().toLowerCase();

            switch (input) {
                case "quit", "exit", "q" -> { System.out.println("Good luck!"); return; }
                case "reset", "r" -> {
                    solver = new WordleSolver(words);
                    System.out.println("Resetting...");
                    showRecommendations(solver);
                    continue;
                }
                case "list", "l" -> { printRemainingWords(solver); continue; }
                case "analyze", "a" -> { printAnalysis(solver); continue; }
                case "help", "h" -> { printHelp(); continue; }
                default -> {}
            }

            if (!input.matches("[a-z]{5}")) {
                System.out.println("  Please enter a valid 5-letter word.");
                continue;
            }

            System.out.print("Result G=🟩 Y=🟨 X=⬛  e.g. XGYXX): ");
            String result = scanner.nextLine().trim().toLowerCase();

            if (result.equals("GGGGG")) {
                System.out.printf("%nSolved in %d guess%s! The answer was: %s%n",
                        solver.guessCount() + 1,
                        solver.guessCount() + 1 == 1 ? "" : "es",
                        input.toUpperCase());
                System.out.println("   Type 'reset' to start a new puzzle.");
                continue;
            }

            long filterStart = System.nanoTime();
            boolean ok = solver.applyGuess(input, result);
            long filterMs = (System.nanoTime() - filterStart) / 1_000_000;
            if (!ok) continue;

            int count = solver.remainingCount();
            System.out.printf("%n  Remaining Candidates: %,d  (filtered in %dms)  [guess #%d]%n",
                    count, filterMs, solver.guessCount());

            if (count == 0) {
                System.out.println("  No words match. Double check your inputs or type 'reset'.");
            } else if (count == 1) {
                System.out.println("  The answer must be: "
                        + solver.getRemaining().get(0).toUpperCase());
            } else {
                showRecommendations(solver);
                if (count <= 30) { System.out.println(); printRemainingWords(solver); }
            }
        }
    }

    // Display Helpers

    private static void showRecommendations(WordleSolver solver) {}

    private static void printAnalysis(WordleSolver solver) {}

    private static void printRemainingWords(WordleSolver solver) {}

    private static void printHelp() {
        System.out.println("""
        ┌──────────────────────────────────────────────────────┐
        │  HOW TO USE                                          │
        │                                                      │
        │  1. Make a guess in Wordle                           │
        │  2. Type that word here                              │
        │  3. Enter the result:                                │
        │       G = 🟩 Green   (right letter, right position)  │
        │       Y = 🟨 Yellow  (right letter, wrong position)  │
        │       X = ⬛ Gray    (letter not in word)            │
        │                                                      │
        │  Example:  guess CRANE → result XGYXX                │
        │                                                      │
        │  Scoring modes (auto-selected):                      │
        │    📈 Frequency  fast heuristic for large sets       │
        │    🧮 Entropy    information-optimal for ≤500 left   │
        │                                                      │
        │  Entropy columns explained:                          │
        │    ENTROPY        bits of info gained (higher=better)│
        │    EXP. REMAINING expected candidates after guess    │
        │    WORST CASE     max candidates in any single bucket│
        │                                                      │
        │  Commands:  analyze  list  reset  quit               │
        └──────────────────────────────────────────────────────┘
        """);
    }
}
