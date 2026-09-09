;; test/grep_test.cljs — build the command and compare it with the system
;; grep, byte for byte AND by exit status.
;;
;; The comparison is against `grep -F`, and that is not a convenience. POSIX
;; grep reads its pattern as a basic regular expression; this reads it
;; literally. Measured 2026-09-10 on a file holding `a.c` and `abc`,
;; `grep 'a.c'` answers BOTH lines and `grep -F 'a.c'` answers one.
;; Comparing against plain grep would be comparing two different operations
;; and calling the difference a bug.
;;
;; The exit status is asserted with the bytes because for grep it IS the
;; answer: 0 when something matched, 1 when nothing did.

(ns grep-test
  (:require [clojure.string :as str] ["fs" :as fs] ["path" :as path] ["os" :as os]))

(def cp (js/require "node:child_process"))

(defn- run [cmd args opts]
  (let [r (.spawnSync cp cmd (clj->js args)
                      (clj->js (merge {:encoding "buffer"} opts)))]
    {:status (.-status r) :out (.-stdout r) :err (.-stderr r)}))

(defn- refuse [message]
  (println (pr-str {:ok false :phase :setup :message message}))
  (.exit js/process 2))

(def amu-home
  (or (.-AMU_HOME js/process.env)
      (let [guess (.resolve path (.cwd js/process) ".." ".." "kotoba-lang" "amu")]
        (when (.existsSync fs (.join path guess "bin" "amu")) guess))))

(def system-grep "/usr/bin/grep")

;; A directory of fixtures, and the cases over them. Each case is an argv,
;; and each is here because it separates a right implementation from a wrong
;; one that passes the others:
;;
;;   a match / no match  -- exit 0 and exit 1, which grep uses as an answer
;;                          rather than as a health report
;;   an EMPTY file       -- reads as the empty string, which must not end the
;;                          line walk the way "no more lines" does
;;   no trailing newline -- grep ADDS one to a matched last line
;;   a substring match   -- `alpha` matches `alphabet` too
;;   a metacharacter     -- `a.c` matches literally under -F, so the
;;                          comparison is against -F and the README says so
;;   multi-byte          -- matched and unmatched, so the walk is not just
;;                          counting bytes
;;   TWO OR MORE files   -- every matching line gains a `FILE:` prefix, and
;;                          with one file it gains none
;;   a missing operand   -- among good ones, where the error status must beat
;;                          the match status
;;
;; The prefix and the exit precedence are the two that a single-file
;; implementation passes everything else without.
(def fixtures
  {"words"  "alpha\nbeta\ngamma\nalphabet\n"
   "none"   "no match here\n"
   "empty"  ""
   "one"    "x\n"
   ;; No trailing newline, and grep ADDS one to a matched last line -- 20
   ;; bytes in, 21 out. This is the opposite of head, which adds nothing.
   "nonl"   "tail without newline"
   "rep"    "aaa\n"
   ;; A metacharacter, so that -F is doing something observable.
   "meta"   "a.c\nabc\n"
   "utf8"   "\u65e5\u672c\u8a9e\n\u00e9clair\nplain\n"})

;; PATTERN then FILE. Each case separates a right implementation from a wrong
;; one that passes the others:
;;
;;   alpha/words    -- TWO matching lines, and `alphabet` is a substring match
;;   zzz/words      -- no match: exit 1 and nothing on stdout
;;   alpha/none     -- a non-empty file that simply does not match
;;   a/empty        -- an empty file, which is not the same as no match
;;   tail/nonl      -- the newline grep adds
;;   a/rep          -- a line matching more than once is printed ONCE
;;   a.c/meta       -- fixed string, not a pattern
;;   multi-byte     -- a needle and a haystack that are not ASCII
(def cases
  [["alpha" "words"] ["zzz" "words"] ["alpha" "none"] ["a" "empty"]
   ["tail" "nonl"] ["a" "rep"] ["x" "one"] ["beta" "words"]
   ["alphabet" "words"] ["a.c" "meta"] ["abc" "meta"]
   ["\u65e5\u672c" "utf8"] ["\u00e9" "utf8"] ["plain" "utf8"] ["zz" "utf8"]
   ;; A MISSING operand: matched on stderr and exit status since wire 35
   ;; gained an EXISTS form. Every utility words this differently --
   ;; measured on each, not copied from a sibling.
   ["x" "missing"]
   ;; --- two or more operands ------------------------------------------
   ;; With two files every matching line is prefixed `FILE:`; with one it is
   ;; not. The prefix is the operand as written.
   ["alpha" "words" "one"] ["x" "words" "one"]
   ;; A match in only the SECOND file, so the prefix cannot come from the
   ;; first operand by accident.
   ["x" "none" "one"]
   ;; No match in either: exit 1 and nothing written.
   ["zzz" "words" "one"]
   ;; The same operand twice is not de-duplicated, and each line carries the
   ;; prefix separately.
   ["alpha" "words" "words"]
   ;; An empty file among the operands contributes nothing but must not end
   ;; the walk.
   ["x" "empty" "one"] ["x" "one" "empty"]
   ;; Three operands.
   ["a" "words" "rep" "meta"]
   ;; The exit-status precedence: a match AND an unreadable operand. grep
   ;; exits 2, not 0 -- the error outranks the match. A worst-of that simply
   ;; kept the last answer, or that let 0 win, passes every case above.
   ["alpha" "words" "missing"] ["alpha" "missing" "words"]
   ;; No match and an unreadable operand: still 2, not 1.
   ["zzz" "words" "missing"]
   ;; Multi-byte with a prefix.
   ["\u65e5\u672c" "utf8" "words"]])

(when-not amu-home (refuse "set AMU_HOME to an amu checkout"))
(let [amu (.join path amu-home "bin" "amu")
      packager (.join path amu-home "scripts" "package-command.cljs")]
  (when-not (.existsSync fs amu) (refuse (str "no amu at " amu)))
  (when-not (.existsSync fs packager) (refuse (str "no packager at " packager)))
  (when-not (.existsSync fs system-grep) (refuse (str "no " system-grep " to compare against")))
  (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "org-ieee-wc-"))
        src (.resolve path (.cwd js/process) "grep" "core.kotoba")
        policy (.join path tmp "policy.edn")
        kexe (.join path tmp "grep.kexe")
        blob (.join path tmp "grep.bin")
        exe (.join path tmp "grep")
        exe-big (.join path tmp "grep-big")]
    (.writeFileSync fs policy "{:allow #{[:cap/call 35] [:cap/call 37] [:cap/call 38] [:cap/call 39]}}" "utf8")
    ;; The fixtures live in the tree the binary is packaged for. The native
    ;; loader refuses a relative request outright, so operands are absolute.
    (let [data (.join path tmp "data")]
      (.mkdirSync fs data)
      (doseq [[name content] fixtures]
        (.writeFileSync fs (.join path data name)
                        content
                        "utf8")))
    (let [c (run "node" [amu "compile" src "--target" "aarch64-macos" "--jvm-free"
                         "--policy" policy "--output" kexe] {})]
      (when (not= 0 (:status c))
        (refuse (str "compile failed: " (str (:err c)) (str (:out c))))))
    (let [e (run "node" [amu "extract-native" kexe "--symbol" "main" "--output" blob] {})
          _ (when (not= 0 (:status e)) (refuse (str "extract failed: " (str (:err e)))))
          report (str (:out e))
          offset (second (re-find #":offset (\d+)" report))]
      (when-not offset (refuse (str "no :offset in the extract report: " report)))
      ;; TWO binaries from the same code: one with the loader's default
      ;; string-arena budget and one with a raised budget. The pair is what
      ;; makes the ceiling below a measurement instead of a claim -- a single
      ;; binary could only show that some size works and some does not, not
      ;; that the bound is the arena and that it moves.
      ;; Fuel and arena are constants of the binary, so they are packaged
      ;; here rather than supplied at run time. Counting words walks one code
      ;; point at a time, so the guest recursion is as long as the file and
      ;; the default 512 fuel counts almost nothing.
      (doseq [[out extra] [[exe ["--fuel" "5000000" "--string-pool" "4000000"]]]]
        (let [p (run "nbb" (into [packager "--code" blob "--offset" offset "--isa" "aarch64"
                                  "--allow" "35,37,38,39"
                                  "--fs-scope" (.realpathSync fs (.join path tmp "data"))
                                  "--output" out]
                                 extra) {})]
          (when (not= 0 (:status p)) (refuse (str "package failed: " (str (:err p))))))))
    ;; Now the only thing that matters: run it.
    (let [results
          (for [names cases]
            ;; [PATTERN FILE...]: the first element is the pattern and
            ;; EVERY remaining one is a path.
            ;;
            ;; This built `[(first names) (second names)]` until 2026-09-10,
            ;; which silently dropped every operand past the second. The
            ;; twelve multi-operand cases added that day all PASSED against
            ;; it, because both implementations were handed one file and
            ;; agreed about it -- a suite that could not have failed. The
            ;; giveaway was in the output: cases naming three files printed
            ;; the first file's lines with no `FILE:` prefix anywhere.
            (let [argv (into [(first names)]
                             (map #(.join path (.realpathSync fs (.join path tmp "data")) %)
                                  (rest names)))
                  k (run exe argv {})
                  s (run system-grep (into ["-F"] argv) {})
                  same? (and (= (.toString (:out k) "base64") (.toString (:out s) "base64"))
                             ;; stderr too: a missing operand differs there
                             ;; and nowhere else, so a suite that compared
                             ;; only stdout and status would call it green.
                             (= (.toString (:err k) "base64") (.toString (:err s) "base64"))
                             (= (:status k) (:status s)))]
              {:argv names :ok same? :kotoba (.toString (:out k) "utf8")
               :system (.toString (:out s) "utf8")
               :exit [(:status k) (:status s)]}))
          bad (remove :ok results)]
      (doseq [r results]
        (println (str (if (:ok r) "  ok   " "  FAIL ")
                      (pr-str (:argv r))
                      " -> " (pr-str (:kotoba r))
                      (when-not (:ok r) (str " but " system-grep " says " (pr-str (:system r))
                                             " exits " (pr-str (:exit r))))))) 
      (println (pr-str {:ok (empty? bad) :cases (count results) :failed (count bad)}))
      (.exit js/process (if (seq bad) 1 0)))))