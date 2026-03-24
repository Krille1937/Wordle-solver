# 🟩 Wordle Solver

A command-line Wordle solver built in Java, developed iteratively across three versions.
Each version introduces new data structures and algorithms — from a simple HashMap-based
filter up to an entropy-scoring engine that is mathematically optimal at picking the best guess.

Built as a learning project, so every design decision is explained in the source code comments.

---

## Features

- **6,787-word dictionary** sourced from the system English dictionary
- **Correct duplicate-letter handling** — the edge case most solvers get wrong
- **Letter-frequency scoring** (v1/v2) — fast heuristic covering the most common letters
- **Entropy scoring** (v3) — information-theoretic optimal guesses with auto mode-switching
- **Letter analysis panel** — coverage % per letter, best letter per position
- **Timing output** on every filter and score operation

---

## Repository Structure

```
wordle-solver/
├── v1/
│   └── WordleSolver.java     # HashMap constraints, frequency scoring, ~1k words
├── v2/
│   └── WordleSolver.java     # int[] primitives, 6,787 words, analyze command
├── v3/
│   └── WordleSolver.java     # Entropy scoring, auto mode-switching
├── words.txt                 # Shared 6,787-word dictionary
└── README.md
```

---

## Getting Started

### Requirements

- Java 21+
- IntelliJ IDEA (or any IDE / terminal)

### IntelliJ Setup

1. **File → New Project → Java** (no frameworks needed)
2. Set SDK to **Java 21**
3. Copy `words.txt` into the **project root**
4. Add the version of `WordleSolver.java` you want to the `src/` folder
5. **Run → Edit Configurations → Program Arguments** → set `words.txt`

### Command Line

```bash
# Compile
javac --enable-preview --release 21 WordleSolver.java -d out/

# Run
java --enable-preview -cp out WordleSolver words.txt
```

> **IntelliJ language level note:** If you see a preview warning, go to
> **File → Project Structure → Modules → Language Level → 21**.
> No actual preview features are used — records, switch expressions, and
> text blocks are all stable since Java 16/17.

---

## How to Use

1. Open [Wordle](https://www.nytimes.com/games/wordle) and make a guess
2. Type that word into the solver
3. Enter the result using `G` / `Y` / `X`:

```
G = 🟩  correct letter, correct position
Y = 🟨  correct letter, wrong position
X = ⬛  letter not in the word
```

**Example session:**

```
Guess: crane
Result: XGYXX

📊  Remaining candidates: 28  (filtered in 0ms)  [guess #1]

💡  Best next guesses  [🧮 entropy scoring, 28 candidates, scored in 98ms]:
    ★ = still a possible answer
    WORD            ENTROPY   EXP. REMAINING  WORST CASE
    ────────────────────────────────────────────────────
    1. TIDAL          2.887      ~1.8            ≤4
    2. THIOL          2.800      ~2.0            ≤4
    3. TOILS          2.797      ~2.1            ≤5
    4. STDIO          2.769      ~2.0            ≤4
    5. LITAS          2.729      ~2.2            ≤5

📋  28 remaining words:
  ARBOR     ARDOR     ARGAL     ARGIL     ARGOT     ARIAS
  ARILS     ARMOR     AROID     AROMA     ARRAS     ARRAY
  ARRIS     ARROW     ARSIS     ARTSY     ARUMS     BRIAR
  BROAD     DRYAD     FRIAR     GROAT     TRIAD     TRIAL  ...
```

### Commands

| Command   | Description                                   |
|-----------|-----------------------------------------------|
| `analyze` | Letter frequency breakdown + best per position |
| `list`    | Show all remaining candidate words            |
| `reset`   | Clear all constraints, start a new puzzle     |
| `quit`    | Exit                                          |

---

## The Three Versions

### v1 — Baseline

The foundation. Gets the constraint logic right, including duplicate letters.

**Data structures:**
```java
char[]                           green            // confirmed positions
Map<Character, Integer>          minCount         // min occurrences per letter
Map<Character, Integer>          maxCount         // max occurrences (for duplicates)
Map<Character, Set<Integer>>     forbiddenPos     // Yellow: banned positions
List<String>                     remaining        // rebuilt each round via stream
```

**Scoring:** For each remaining word, count how many candidates contain each letter.
Score every dictionary word by summing those counts for its unique letters. Highest score = best coverage.

---

### v2 — Efficiency

Same logic, faster data structures, and a much larger word list.

**Key changes:**

| What | v1 | v2 |
|------|----|----|
| Word list | ~1,000 words | 6,787 words |
| Constraint state | `HashMap<Character, ...>` | `int[26]` / `boolean[5][26]` |
| Letter frequencies | Built per-filter-call | Pre-computed `byte[N][26]` at startup |
| Candidate tracking | `List<String>` rebuilt via stream | `int[]` compacted in-place |
| Top-K selection | Sort all N → O(N log N) | Min-heap of K → O(N log K) |

**The hot-path filter** goes from HashMap lookups with boxing to direct array indexing:

```java
// v1 — HashMap in the inner loop
wordLetterCounts.getOrDefault(letter, 0) < minCount.get(letter)

// v2 — array access, zero allocations
letterCounts[idx][c] < minCount[c]   // c = letter - 'a'
```

**New:** `analyze` command showing letter coverage and best letter per position.

---

### v3 — Entropy Scoring

Replaces the frequency heuristic with an information-theoretic scoring model
when the candidate pool is small enough for it to run in time.

#### How entropy scoring works

Every guess produces one of 3⁵ = 243 possible result patterns (every combination
of 🟩🟨⬛ across the 5 positions). For a given guess, simulate what pattern it
would produce against each remaining candidate. Count how many candidates fall
into each bucket.

A perfect guess splits candidates into 243 equal buckets of size 1.
A useless guess drops all candidates into one bucket and tells you nothing.

```
H(guess) = Σ [ count · log(R / count) ] / R

  R     = total remaining candidates
  count = size of each non-empty bucket
  H     = entropy in bits (higher = more information gained)
```

#### Reading the entropy output

```
WORD     ENTROPY   EXP. REMAINING   WORST CASE
TIDAL     2.887       ~1.8             ≤4
```

- **ENTROPY** — bits of information this guess gives you. The theoretical max
  for 28 candidates is log₂(28) ≈ 4.8 bits. TIDAL achieves 2.887 — very strong.
- **EXP. REMAINING** — expected candidates left after this guess.
  `~1.8` means on average fewer than 2 survive, so you'll almost certainly
  identify the answer on the very next guess.
- **WORST CASE** — the largest any single bucket gets. `≤4` means even
  in the unluckiest outcome, at most 4 candidates remain.

When EXP. REMAINING = 1.0 and WORST CASE = 1, the guess is guaranteed to
identify the answer — you can safely play the actual answer next.

#### Pattern computation and duplicate letters

This is the most subtle part of the implementation. Given a guess and an answer:

1. **Pass 1 — Greens:** mark exact position matches, subtract those letters
   from the answer's available pool.
2. **Pass 2 — Yellows:** for each non-green guess position, only assign Yellow
   if the answer still has an unused copy of that letter.

This prevents over-marking in cases like guessing `speed` against `spell`:

```
s p e e d   (guess)
s p e l l   (answer)
G G G X X   ← correct: second 'e' and 'p' are gray because
              the answer's 'e' and 'p' are already consumed by greens
```

The pattern is encoded as a base-3 integer for O(1) bucket access:

```
G=2, Y=1, X=0
pattern = r[0]·81 + r[1]·27 + r[2]·9 + r[3]·3 + r[4]   (range: 0–242)
```

This lets the bucket array be a fixed `int[243]` instead of a HashMap.

#### Auto mode-switching

```
remaining > 500  →  📈 Frequency scoring   ~90ms    good enough heuristic
remaining ≤ 500  →  🧮 Entropy scoring    ~100ms   information-optimal
```

Entropy across all 6,787 words against 6,787 candidates would take ~2 seconds.
At 500 candidates it runs in under 150ms. The switch is invisible to the user —
you just see better recommendations as the puzzle narrows.

---

## Recommended Opening Words

These consistently rank highest on the first guess:

| Word  | Why |
|-------|-----|
| AROSE | Covers A, R, O, S, E — five of the most common Wordle letters |
| RAISE | Same letters, slightly different positions |
| CRANE | Classic choice — strong letter coverage |
| SLATE | S, L, A, T, E — well-distributed across positions |
| AUDIO | Covers 4 vowels in a single guess |

---

## Solving Strategy

```
Guess 1  (6,787 candidates)   Use a known strong opener. Target: < 200 remaining.

Guess 2  (50–200 candidates)  Entropy activates. Pick the top suggestion even if
                               it's not a possible answer — maximising information
                               is more valuable than guessing a possible answer badly.
                               Target: < 10 remaining.

Guess 3  (5–15 candidates)    EXP. REMAINING should be close to 1.
                               If WORST CASE = 1, the answer is guaranteed next guess.

Guess 4+                      The answer should be uniquely identified.
                               Trust the ★ marker — play the possible answer.
```

---

## Java Features Used

All stable in Java 21 — no actual preview features required:

| Feature | Stable since | Usage |
|---------|-------------|-------|
| Records | Java 16 | `record ScoredWord(String word, double score, ...) {}` |
| Switch expressions | Java 14 | `switch (clue) { case 'G' -> { ... } }` |
| Text blocks | Java 15 | Multi-line help text with `""" ... """` |
| `var` inference | Java 10 | `var recs = solver.getRecommendations(7)` |
| `List.of()` | Java 9 | Immutable single-element return value |

---

## License

MIT
