#!/usr/bin/env python3
"""
A/B test of candidate command phrasings vs current ones across accents.
Reuses the accent stress-test infrastructure. For classifier-routed phrases we
check intent+confidence; for exact-match voice shortcuts (undo/confirm words)
we check that the TRANSCRIPT itself contains the keyword — that is what the
app's VoiceShortcuts matcher sees.
"""
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from accent_stress_test import (CONDITIONS, VOICES, IntentClassifier, add_noise,
                                sanitize_for_classification, synthesize,
                                transcribe, SNR_DB, CONFIDENCE_THRESHOLD)

# (phrase, check_type, expected)
# check_type: "intent" -> classifier label; "keyword" -> word(s) present in transcript
CANDIDATES = [
    # -- открытие карты: текущая vs кандидаты --
    ("open patient chart for jane roe", "intent", "emr_open_patient_chart"),
    ("open patient record for jane roe", "intent", "emr_open_patient_chart"),
    ("open patient file for jane roe", "intent", "emr_open_patient_chart"),
    # -- undo: текущие vs кандидаты (шорткаты -> проверяем транскрипт) --
    ("undo", "keyword", "undo"),
    ("undo the last entry", "intent", "emr_undo_last"),
    ("scratch that", "keyword", "scratch"),
    ("delete the last entry", "intent", "emr_undo_last"),
    # -- confirm: текущие vs кандидаты --
    ("confirm", "keyword", "confirm"),
    ("yes confirm", "keyword", "confirm"),
    ("affirmative", "keyword", "affirmative"),
    ("approve", "keyword", "approve"),
    # -- cancel для полноты --
    ("cancel", "keyword", "cancel"),
    ("negative", "keyword", "negative"),
]


def main():
    clf = IntentClassifier()
    results = {}  # phrase -> [pass, total]
    fails = []
    total_clips = len(CANDIDATES) * len(VOICES) * len(CONDITIONS)
    done = 0

    for phrase, check, expected in CANDIDATES:
        results[phrase] = [0, 0]
        for voice, accent in VOICES:
            for rate, noisy in CONDITIONS:
                done += 1
                with tempfile.TemporaryDirectory() as tmp:
                    wav = os.path.join(tmp, "c.wav")
                    synthesize(phrase, voice, rate, wav)
                    if noisy:
                        nw = os.path.join(tmp, "n.wav")
                        add_noise(wav, nw, SNR_DB)
                        wav = nw
                    transcript = transcribe(wav)
                ok = False
                detail = transcript
                if check == "keyword":
                    ok = expected.lower() in transcript.lower()
                else:
                    label, conf = clf.classify(sanitize_for_classification(transcript))
                    ok = label == expected and conf >= CONFIDENCE_THRESHOLD
                    detail = f"{transcript!r} -> {label} {conf:.2f}"
                results[phrase][1] += 1
                if ok:
                    results[phrase][0] += 1
                else:
                    fails.append((phrase, accent, f"{rate}{'+n' if noisy else ''}", detail))
                if done % 28 == 0:
                    print(f"[{done}/{total_clips}]", flush=True)

    print("\n=== PASS RATE per phrase (7 accents x 4 conditions = 28 clips each) ===")
    for phrase, check, expected in CANDIDATES:
        p, t = results[phrase]
        print(f"{p:3}/{t}  {p/t:4.0%}  {phrase!r}")

    print(f"\n=== FAILURES ({len(fails)}) ===")
    for phrase, accent, cond, detail in fails:
        print(f"  {phrase!r:38} {accent:8} {cond:6} heard: {detail[:80]}")


if __name__ == "__main__":
    main()
