# Wordle Solver — Build Guide (IntelliJ IDEA)

Three iterations, each building on the last. Read the file headers in order —
the comments explain every design decision.

---

## Project Setup (do this once)

1. Open IntelliJ → **New Project** → **Java** (no frameworks needed)
2. Set SDK to **Java 21** (required for records and switch expressions)
3. Under **File → Project Structure → Modules → Sources**,
   mark the `src/` folder as Sources Root if it isn't already
4. Copy `words.txt` into the **project root** (same level as `src/`)
   — this is where the program looks for it by default

To run with the word file:
- **Run → Edit Configurations → Program arguments** → type: `words.txt`
- Or just run with no arguments — it defaults to `words.txt` in the working dir

---

## Iteration 1 — `v1/WordleSolver.java`

**Core concepts introduced:**
- The constraint model: Green / Yellow / Gray → what they each mean
- Duplicate-letter handling (the tricky part most solvers get wrong)
- Letter-frequency scoring for recommendations

**Data structures:**
```
HashMap<Character, Integer>  minCount       — minimum occurrences per letter
HashMap<Character, Integer>  maxCount       — maximum occurrences per letter
HashMap<Character, Set<Int>> forbiddenPos   — Yellow: forbidden positions
char[]                       green          — confirmed letters by position
List<String>                 remaining      — candidate words (rebuilt each round)
```

**Scoring algorithm:**
```
For each remaining candidate word:
    freq[letter] = how many remaining words contain that letter

For each word in the dictionary:
    score = sum of freq[letter] for each UNIQUE letter in the word

Rank by score descending. ★ = word is still a possible answer.
```

**What to notice when running:**
- Start with CRANE, SLATE, or AROSE — they score highest on the first guess
- After 2 guesses the candidate list usually drops to under 50
- The ★ marker shows words that are both good guesses AND possible answers

---

## Iteration 2 — `v2/WordleSolver.java`

**What changed and why:**

### 1. Word list: ~1,000 → 6,787 words
The solver is only as good as its word list. V1 had a hand-curated ~1k set.
V2 uses a real English dictionary filtered to 5-letter words.

### 2. Pre-computed letter counts: `byte[N][26]`
```java
// V1: HashMap lookup inside the filter loop (slow — boxing, hashing)
wordLetterCounts.getOrDefault(letter, 0)

// V2: direct array access (fast — one CPU instruction)
letterCounts[idx][c]   // c = letter - 'a'
```
Built once at startup. The filter loop reads them with zero allocations.

### 3. Constraint state as primitives
```java
// V1
HashMap<Character, Integer> minCount
HashMap<Character, Integer> maxCount
HashMap<Character, Set<Integer>> forbiddenPositions

// V2
int[]       minCount   = new int[26]
int[]       maxCount   = new int[26]    // default 5 = unconstrained
boolean[][] forbidden  = new boolean[5][26]
```
Array indexing by `(char - 'a')` replaces all HashMap lookups in the hot path.

### 4. Index-based candidate tracking
```java
// V1: rebuilds a new List<String> each round (allocation + GC)
remaining = remaining.stream().filter(...).collect(toList())

// V2: compacts an int[] in-place (zero allocation)
int write = 0;
for (int ri = 0; ri < remainingSize; ri++) {
    if (matches(remainingIdx[ri])) remainingIdx[write++] = remainingIdx[ri];
}
remainingSize = write;
```

### 5. Min-heap for top-K scoring
```java
// V1: score all N words, then sort all N  →  O(N log N)
// V2: score all N words, keep heap of K   →  O(N log K)
// With K=7 and N=6787: log(7) ≈ 2.8 vs log(6787) ≈ 12.7 — ~4.5× fewer comparisons
```

### 6. New `analyze` command
Type `analyze` at any point to see:
- Every letter ranked by what % of remaining words contain it
- The best letter at each of the 5 positions

**What to notice when running:**
- Startup now prints `⚡ Pre-computed letter data in Xms` — usually 2–5ms
- Filtering reports `(filtered in 0ms)` — 6.7× more words, still instant
- Scoring reports `(scored in ~90ms)` — min-heap keeps this fast

---

## Iteration 3 — `v3/WordleSolver.java`

**What changed: Entropy Scoring**

### The core idea
Frequency scoring (v1/v2) asks: *"which letters appear most often?"*
Entropy scoring asks: *"which guess gives me the most information?"*

For each candidate guess, simulate every possible Wordle result pattern
(there are 3⁵ = 243 of them: every combination of 🟩🟨⬛ across 5 positions).
Count how many remaining answers would produce each pattern — that's a "bucket."

A guess that splits remaining candidates into many small equal buckets is ideal.
A terrible guess dumps everything into one bucket and tells you nothing new.

```
Entropy H(guess) = -Σ p(bucket) · log₂(p(bucket))

Equivalently (more numerically stable):
H(guess) = Σ [count · log(R / count)] / R
  where R = total remaining candidates
        count = size of each non-empty bucket
```

Higher H = more bits of information = fewer guesses needed on average.

### The three output columns in entropy mode
```
WORD         ENTROPY    EXP. REMAINING    WORST CASE
TIDAL         2.887        ~1.8              ≤4
```
- **ENTROPY**: bits of info gained. log₂(28) ≈ 4.8 bits would be perfect.
  TIDAL gets 2.887 bits out of a possible 4.8 — very good for 28 candidates.
- **EXP. REMAINING**: E[candidates left] = Σ (count/R)·count = Σ count²/R
  ~1.8 means on average fewer than 2 candidates survive → usually solved next guess
- **WORST CASE**: in the unluckiest outcome, at most this many candidates survive.
  ≤4 means the answer is always identified within 2 more guesses.

### Pattern encoding
Each result is encoded as a base-3 integer (0–242):
```
G=2, Y=1, X=0
value = r[0]·81 + r[1]·27 + r[2]·9 + r[3]·3 + r[4]
```
This lets us use a fixed-size `int[243]` bucket array instead of a HashMap.

### Duplicate-letter pattern correctness
The pattern computation is the most subtle part. Given guess and answer:
1. **Pass 1 (Greens):** mark exact matches, subtract from answer's available pool
2. **Pass 2 (Yellows):** only assign Yellow if the answer still has an available copy

This ensures a guess like "speed" against "spell" correctly gives GGGXX, not GGGYX,
because the second 'e' in the answer is already consumed by position 2's Green.

### Auto-switching between modes
```
remaining > 500  →  Frequency scoring  (~90ms,  good enough heuristic)
remaining ≤ 500  →  Entropy scoring    (~100ms, information-optimal)
```
Entropy at full 6,787 words would take ~2 seconds — not worth it when all
candidates are still in play. At ≤500 it's fast and gives you the best possible guess.

**What to notice when running:**
- After the first guess (typically 28–150 remaining), the mode switches to 🧮 entropy
- Watch EXP. REMAINING drop toward ~1 as the puzzle narrows
- When WORST CASE = 1, the recommended guess is guaranteed to identify the answer

---

## Commands (all versions)

| Command   | What it does                              |
|-----------|-------------------------------------------|
| `analyze` | Letter frequency breakdown (v2 and v3)    |
| `list`    | Show all remaining candidate words        |
| `reset`   | Clear all constraints, start a new puzzle |
| `quit`    | Exit the program                          |

---

## Recommended Opening Words

These consistently score highest on the first guess:

| Word   | Why it's good                               |
|--------|---------------------------------------------|
| AROSE  | Covers A, R, O, S, E — 5 of the 6 most common Wordle letters |
| RAISE  | Same letters in a slightly different order  |
| CRANE  | Classic — C, R, A, N, E with good positions |
| SLATE  | S, L, A, T, E — strong coverage             |
| AUDIO  | Covers 4 vowels in one guess                |

---

## How to Think About the Strategy

```
Guess 1 (6787 candidates):  Use frequency scoring or a known good opener.
                             Target: narrow to <200 candidates.

Guess 2 (50–200 candidates): Entropy kicks in. Pick the highest-entropy guess
                              even if it's not a possible answer — it maximises
                              information. Target: narrow to <10 candidates.

Guess 3 (5–15 candidates):   EXP. REMAINING should be ~1–2.
                              If WORST CASE ≤ 1, you're guaranteed to solve next.

Guess 4+:                    The answer should be clear. If EXP. REMAINING = 1.0
                             and WORST CASE = 1, you can safely guess the answer.
```

---

## Java 21 Features Used

These are all standard Java 21 — no preview flags needed if you set SDK 21:

| Feature              | First used in | Example                          |
|----------------------|---------------|----------------------------------|
| `record`             | Java 14+      | `record ScoredWord(...) {}`      |
| Switch expressions   | Java 14+      | `switch (clue) { case 'G' -> }` |
| Text blocks          | Java 15+      | `""" ... """`                    |
| `var` type inference | Java 10+      | `var recs = solver.getRecs(7)`   |
| `List.of()`          | Java 9+       | `List.of(new ScoredWord(...))`   |

> **Note:** If IntelliJ warns about `--enable-preview`, go to
> **File → Project Structure → Modules → Language Level** and set it to
> **21 (Preview)** — or just set it to 21 without preview, since none of
> these features are actually preview in Java 21.
