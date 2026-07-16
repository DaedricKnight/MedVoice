#!/usr/bin/env python3
"""
MedVoice accent & intonation stress test.

Synthesizes every test phrase with multiple macOS TTS voices (US/GB/AU/IE/IN/ZA
accents), speech rates (slow/normal/fast as an intonation proxy) and an added
white-noise condition, then runs each clip through the SAME pipeline the app
uses: whisper.cpp (base.en-q5_1, medical prompt, scaled audio_ctx) -> transcript
sanitization + patient-name stripping -> ONNX intent classifier -> 0.85
confidence gate. Produces a per-accent accuracy report and a failure list.

Run from the MedVoice repo root:  python3 tools/accent_stress_test.py
"""
import csv
import json
import os
import re
import subprocess
import sys
import tempfile
import wave

import numpy as np
import onnxruntime as ort
from transformers import BertTokenizerFast

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WHISPER_CLI = os.path.join(ROOT, "third_party/whisper.cpp/build-host/bin/whisper-cli")
WHISPER_MODEL = os.environ.get("MV_WHISPER_MODEL",
    os.path.join(ROOT, "app/src/main/assets/ggml-base.en-q5_1.bin"))
INTENT_MODEL = os.path.join(ROOT, "app/src/main/assets/medical_intent.onnx")
CONFIG = os.path.join(ROOT, "app/src/main/assets/model_config_medical.json")
OUT_DIR = os.environ.get("MV_OUT_DIR", os.path.join(ROOT, "tools/accent_test_out"))

PROMPT = ("Clinical voice commands: log pain level, log heart rate, log temperature, "
          "record blood pressure, record weight, oxygen saturation, respiratory rate, "
          "prescribe medication, discharge patient, code blue, defibrillator, "
          "sepsis protocol, triage. Patients: John Doe, Jane Roe, Robert Miles.")

PATIENT_NAME_TOKENS = {"john", "doe", "jane", "roe", "robert", "miles"}
CONFIDENCE_THRESHOLD = 0.85

PHRASES = [
    ("record blood pressure 140 over 90", "vitals_log_blood_pressure"),
    ("log heart rate 72", "vitals_log_heart_rate"),
    ("temperature is 38.5", "vitals_log_temperature"),
    ("oxygen saturation 82", "vitals_log_oxygen_saturation"),
    ("pain level 7 out of 10", "symptom_log_pain_level"),
    ("please discharge the patient", "staff_discharge_patient"),
    ("open patient chart for jane roe", "emr_open_patient_chart"),
    ("add a new patient", "emr_add_patient"),
    ("undo the last entry", "emr_undo_last"),
    ("call code blue", "emrgy_call_code_blue"),
]

VOICES = [
    ("Samantha", "US"),
    ("Daniel", "GB"),
    ("Karen", "AU"),
    ("Moira", "IE"),
    ("Rishi", "IN"),
    ("Tessa", "ZA"),
    ("Grandma (English (UK))", "GB-aged"),
]

# (rate wpm, add_noise) — rates approximate slow / normal / fast intonation
CONDITIONS = [(140, False), (180, False), (220, False), (180, True)]

SNR_DB = 10.0


def synthesize(text, voice, rate, out_wav):
    subprocess.run(
        ["say", "-v", voice, "-r", str(rate),
         "--file-format=WAVE", "--data-format=LEI16@16000", "-o", out_wav, text],
        check=True, capture_output=True)


def add_noise(in_wav, out_wav, snr_db):
    with wave.open(in_wav) as w:
        params = w.getparams()
        pcm = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float64)
    signal_power = np.mean(pcm ** 2) or 1.0
    noise_power = signal_power / (10 ** (snr_db / 10))
    noise = np.random.default_rng(42).normal(0, np.sqrt(noise_power), len(pcm))
    noisy = np.clip(pcm + noise, -32768, 32767).astype(np.int16)
    with wave.open(out_wav, "w") as w:
        w.setparams(params)
        w.writeframes(noisy.tobytes())


def wav_duration(path):
    with wave.open(path) as w:
        return w.getnframes() / w.getframerate()


def transcribe(path):
    dur = wav_duration(path)
    ac = min(1500, max(192, int(dur * 16000 / 320) + 128))
    result = subprocess.run(
        [WHISPER_CLI, "-m", WHISPER_MODEL, "-f", path,
         "--prompt", PROMPT, "-l", "en", "-nt", "-ac", str(ac)],
        capture_output=True, text=True)
    text = result.stdout.strip().replace("\n", " ")
    # TranscriptCleaner.stripNonSpeech
    text = re.sub(r"\([^)]*\)|\[[^]]*]|♪[^♪]*♪|♪", " ", text)
    return re.sub(r"\s+", " ", text).strip()


class IntentClassifier:
    def __init__(self):
        cfg = json.load(open(CONFIG))
        self.labels = cfg["labels"]
        self.max_len = cfg["max_length"]
        self.session = ort.InferenceSession(INTENT_MODEL, providers=["CPUExecutionProvider"])
        self.tokenizer = BertTokenizerFast.from_pretrained("prajjwal1/bert-tiny")

    def classify(self, text):
        inputs = self.tokenizer(text, padding="max_length", truncation=True,
                                max_length=self.max_len, return_tensors="np")
        logits = self.session.run(None, {
            "input_ids": inputs["input_ids"].astype(np.int64),
            "attention_mask": inputs["attention_mask"].astype(np.int64)})[0][0]
        exp = np.exp(logits - logits.max())
        probs = exp / exp.sum()
        idx = int(probs.argmax())
        return self.labels[idx], float(probs[idx])


def sanitize_for_classification(text):
    """VitalsExtractor.sanitizeForClassification + PatientMatcher.stripNames."""
    text = re.sub(r"[,!?;:'\"“”]", " ", text)
    words = [w for w in text.split()
             if w.lower().strip(".") not in PATIENT_NAME_TOKENS]
    return re.sub(r"\s+", " ", " ".join(words)).strip()


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    clf = IntentClassifier()
    rows = []
    total = len(VOICES) * len(PHRASES) * len(CONDITIONS)
    done = 0

    for voice, accent in VOICES:
        for rate, noisy in CONDITIONS:
            for phrase, expected in PHRASES:
                done += 1
                cond = f"{rate}wpm{'+noise' if noisy else ''}"
                with tempfile.TemporaryDirectory() as tmp:
                    wav = os.path.join(tmp, "clip.wav")
                    try:
                        synthesize(phrase, voice, rate, wav)
                    except subprocess.CalledProcessError:
                        rows.append([accent, voice, cond, phrase, expected,
                                     "<synthesis failed>", "", 0.0, "SYNTH_FAIL"])
                        continue
                    if noisy:
                        noisy_wav = os.path.join(tmp, "noisy.wav")
                        add_noise(wav, noisy_wav, SNR_DB)
                        wav = noisy_wav
                    transcript = transcribe(wav)
                label, conf = ("", 0.0)
                if transcript:
                    label, conf = clf.classify(sanitize_for_classification(transcript))
                if not transcript:
                    verdict = "NO_SPEECH"
                elif label == expected and conf >= CONFIDENCE_THRESHOLD:
                    verdict = "PASS"
                elif label == "none" or conf < CONFIDENCE_THRESHOLD:
                    verdict = "REJECTED"      # safe failure: re-prompt
                else:
                    verdict = "WRONG_INTENT"  # dangerous failure
                rows.append([accent, voice, cond, phrase, expected,
                             transcript, label, round(conf, 3), verdict])
                print(f"[{done}/{total}] {accent:8} {cond:12} {verdict:12} {phrase[:34]}",
                      flush=True)

    csv_path = os.path.join(OUT_DIR, "results.csv")
    with open(csv_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["accent", "voice", "condition", "phrase", "expected",
                         "transcript", "predicted", "confidence", "verdict"])
        writer.writerows(rows)

    print("\n=== SUMMARY by accent ===")
    print(f"{'accent':10} {'pass':>5} {'rejected':>9} {'wrong':>6} {'total':>6}  pass-rate")
    for accent in dict.fromkeys(a for a, _ in [(v[1], 0) for v in VOICES]):
        acc_rows = [r for r in rows if r[0] == accent]
        p = sum(r[8] == "PASS" for r in acc_rows)
        rej = sum(r[8] == "REJECTED" for r in acc_rows)
        w = sum(r[8] == "WRONG_INTENT" for r in acc_rows)
        print(f"{accent:10} {p:5} {rej:9} {w:6} {len(acc_rows):6}  {p/len(acc_rows):.0%}")

    print("\n=== SUMMARY by condition ===")
    for rate, noisy in CONDITIONS:
        cond = f"{rate}wpm{'+noise' if noisy else ''}"
        c_rows = [r for r in rows if r[2] == cond]
        p = sum(r[8] == "PASS" for r in c_rows)
        w = sum(r[8] == "WRONG_INTENT" for r in c_rows)
        print(f"{cond:14} pass {p}/{len(c_rows)} ({p/len(c_rows):.0%}), wrong-intent {w}")

    wrong = [r for r in rows if r[8] == "WRONG_INTENT"]
    print(f"\n=== DANGEROUS FAILURES (wrong intent above threshold): {len(wrong)} ===")
    for r in wrong[:20]:
        print(f"  {r[0]:8} {r[2]:12} said={r[3]!r} heard={r[5]!r} -> {r[6]} {r[7]}")
    print(f"\nFull results: {csv_path}")


if __name__ == "__main__":
    main()
