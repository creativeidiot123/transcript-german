#!/usr/bin/env python3
"""Reproducible German Nemotron streaming corpus benchmark."""

from __future__ import annotations

import argparse
import csv
import gc
import io
import json
import math
import random
import statistics
import time
import unicodedata
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

import jiwer
import numpy as np
import pyarrow.parquet as pq
import sherpa_onnx
import soundfile as sf

SAMPLE_RATE = 16_000
LANGUAGE = "de"
SEED = 20260924
DEFAULT_THREADS = 4
DEFAULT_CHUNK_MS = 100
DEFAULT_ENDPOINT_S = 0.8
DEFAULT_BLANK_PENALTY = 1.0
RULE1_S = 2.4
RULE3_S = 20.0
MAX_TAIL_S = 3.0
STOP_FLUSH_S = 0.3
STRESS_ATTENUATION_DB = (0, 12, 24, 30, 36, 42, 48)
SWEEP_ATTENUATION_DB = (0, 30)


@dataclass(frozen=True)
class BenchmarkConfig:
    family: str
    label: str
    threads: int = DEFAULT_THREADS
    chunk_ms: int = DEFAULT_CHUNK_MS
    endpoint_s: float = DEFAULT_ENDPOINT_S
    blank_penalty: float = DEFAULT_BLANK_PENALTY
    attenuation_db: int = 0


@dataclass
class Sample:
    index: int
    reference: str
    audio: np.ndarray


@dataclass
class SampleResult:
    sample_index: int
    reference: str
    hypothesis: str
    audio_ms: float
    decode_compute_ms: float
    rtf: float
    first_partial_audio_ms: float | None
    first_partial_compute_ms: float | None
    finalization_extra_ms: float | None
    decode_step_p95_ms: float
    segments: int


def percentile(values: Iterable[float], q: float) -> float:
    items = sorted(float(v) for v in values)
    if not items:
        return math.nan
    if len(items) == 1:
        return items[0]
    pos = (len(items) - 1) * q
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return items[lo]
    weight = pos - lo
    return items[lo] * (1.0 - weight) + items[hi] * weight


def normalize_text(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).casefold()
    cleaned = []
    for char in text:
        category = unicodedata.category(char)
        cleaned.append(" " if category[0] in {"P", "S"} else char)
    return " ".join("".join(cleaned).split())


def pcm16_attenuate(samples: np.ndarray, attenuation_db: int) -> np.ndarray:
    gain = 10.0 ** (-attenuation_db / 20.0)
    scaled = np.clip(samples.astype(np.float32) * gain, -1.0, 32767.0 / 32768.0)
    quantized = np.rint(scaled * 32768.0)
    quantized = np.clip(quantized, -32768, 32767).astype(np.int16)
    return quantized.astype(np.float32) / 32768.0


def decode_audio_blob(blob: bytes) -> np.ndarray:
    audio, sample_rate = sf.read(io.BytesIO(blob), dtype="float32", always_2d=False)
    if sample_rate != SAMPLE_RATE:
        raise RuntimeError(f"FLEURS sample rate {sample_rate} != {SAMPLE_RATE}")
    if audio.ndim != 1:
        raise RuntimeError(f"Expected mono FLEURS audio, got shape={audio.shape}")
    return np.ascontiguousarray(audio, dtype=np.float32)


def load_samples(parquet_path: Path, count: int, seed: int) -> list[Sample]:
    parquet = pq.ParquetFile(parquet_path)
    total_rows = parquet.metadata.num_rows
    chosen = sorted(random.Random(seed).sample(range(total_rows), min(count, total_rows)))
    chosen_set = set(chosen)
    output: list[Sample] = []
    row_index = 0

    for batch in parquet.iter_batches(
        batch_size=16,
        columns=["audio", "transcription"],
    ):
        audio_col = batch.column(0)
        text_col = batch.column(1)
        for offset in range(batch.num_rows):
            current = row_index + offset
            if current not in chosen_set:
                continue
            audio_value = audio_col[offset].as_py()
            if not isinstance(audio_value, dict) or not audio_value.get("bytes"):
                raise RuntimeError(f"Missing embedded audio bytes for FLEURS row {current}")
            output.append(
                Sample(
                    index=current,
                    reference=str(text_col[offset].as_py()),
                    audio=decode_audio_blob(audio_value["bytes"]),
                ),
            )
        row_index += batch.num_rows
        if len(output) == len(chosen):
            break

    if len(output) != len(chosen):
        raise RuntimeError(f"Loaded {len(output)} of {len(chosen)} selected FLEURS rows")
    return output


def build_recognizer(model_dir: Path, config: BenchmarkConfig):
    started = time.perf_counter()
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(model_dir / "tokens.txt"),
        encoder=str(model_dir / "encoder.int8.onnx"),
        decoder=str(model_dir / "decoder.int8.onnx"),
        joiner=str(model_dir / "joiner.int8.onnx"),
        num_threads=config.threads,
        sample_rate=SAMPLE_RATE,
        feature_dim=80,
        enable_endpoint_detection=True,
        rule1_min_trailing_silence=RULE1_S,
        rule2_min_trailing_silence=config.endpoint_s,
        rule3_min_utterance_length=RULE3_S,
        decoding_method="greedy_search",
        blank_penalty=config.blank_penalty,
        provider="cpu",
    )
    return recognizer, (time.perf_counter() - started) * 1000.0


def decode_one(recognizer, sample: Sample, config: BenchmarkConfig) -> SampleResult:
    audio = pcm16_attenuate(sample.audio, config.attenuation_db)
    stream = recognizer.create_stream()
    stream.set_option("language", LANGUAGE)

    chunk_samples = max(1, SAMPLE_RATE * config.chunk_ms // 1000)
    source_samples = len(audio)
    fed_samples = 0
    decode_compute_ms = 0.0
    decode_steps_ms: list[float] = []
    segments: list[str] = []
    first_partial_audio_ms: float | None = None
    first_partial_compute_ms: float | None = None
    last_nonempty_endpoint_at_ms: float | None = None

    def drain() -> None:
        nonlocal decode_compute_ms
        nonlocal first_partial_audio_ms
        nonlocal first_partial_compute_ms
        nonlocal last_nonempty_endpoint_at_ms

        while recognizer.is_ready(stream):
            before = time.perf_counter()
            recognizer.decode_stream(stream)
            elapsed_ms = (time.perf_counter() - before) * 1000.0
            decode_compute_ms += elapsed_ms
            decode_steps_ms.append(elapsed_ms)

        text = recognizer.get_result(stream).strip()
        if text and first_partial_audio_ms is None:
            first_partial_audio_ms = fed_samples * 1000.0 / SAMPLE_RATE
            first_partial_compute_ms = decode_compute_ms

        if recognizer.is_endpoint(stream):
            if text:
                segments.append(text)
                last_nonempty_endpoint_at_ms = fed_samples * 1000.0 / SAMPLE_RATE
            recognizer.reset(stream)

    for start in range(0, source_samples, chunk_samples):
        chunk = audio[start : start + chunk_samples]
        stream.accept_waveform(SAMPLE_RATE, chunk)
        fed_samples += len(chunk)
        drain()

    max_tail_samples = int(MAX_TAIL_S * SAMPLE_RATE)
    tail = np.zeros(chunk_samples, dtype=np.float32)
    tail_fed = 0

    while tail_fed < max_tail_samples:
        current = tail[: min(chunk_samples, max_tail_samples - tail_fed)]
        stream.accept_waveform(SAMPLE_RATE, current)
        fed_samples += len(current)
        tail_fed += len(current)
        previous_segment_count = len(segments)
        drain()
        if (
            len(segments) > previous_segment_count
            and last_nonempty_endpoint_at_ms is not None
            and last_nonempty_endpoint_at_ms >= source_samples * 1000.0 / SAMPLE_RATE
        ):
            break

    remaining = recognizer.get_result(stream).strip()
    if remaining:
        flush = np.zeros(int(STOP_FLUSH_S * SAMPLE_RATE), dtype=np.float32)
        stream.accept_waveform(SAMPLE_RATE, flush)
        fed_samples += len(flush)
        stream.input_finished()
        drain()
        remaining = recognizer.get_result(stream).strip()
        if remaining:
            segments.append(remaining)
            last_nonempty_endpoint_at_ms = fed_samples * 1000.0 / SAMPLE_RATE

    hypothesis = " ".join(segment for segment in segments if segment).strip()
    audio_ms = source_samples * 1000.0 / SAMPLE_RATE
    finalization_extra_ms = (
        max(0.0, last_nonempty_endpoint_at_ms - audio_ms)
        if last_nonempty_endpoint_at_ms is not None
        else None
    )
    step_p95 = percentile(decode_steps_ms, 0.95)
    return SampleResult(
        sample_index=sample.index,
        reference=normalize_text(sample.reference),
        hypothesis=normalize_text(hypothesis),
        audio_ms=audio_ms,
        decode_compute_ms=decode_compute_ms,
        rtf=decode_compute_ms / audio_ms if audio_ms > 0 else math.nan,
        first_partial_audio_ms=first_partial_audio_ms,
        first_partial_compute_ms=first_partial_compute_ms,
        finalization_extra_ms=finalization_extra_ms,
        decode_step_p95_ms=step_p95,
        segments=len(segments),
    )


def summarize(config: BenchmarkConfig, init_ms: float, results: list[SampleResult]) -> dict:
    refs = [result.reference for result in results]
    hyps = [result.hypothesis for result in results]
    words = jiwer.process_words(refs, hyps)
    finite_first_audio = [
        result.first_partial_audio_ms
        for result in results
        if result.first_partial_audio_ms is not None
    ]
    finite_first_compute = [
        result.first_partial_compute_ms
        for result in results
        if result.first_partial_compute_ms is not None
    ]
    finite_final = [
        result.finalization_extra_ms
        for result in results
        if result.finalization_extra_ms is not None
    ]
    return {
        **asdict(config),
        "samples": len(results),
        "model_init_ms": round(init_ms, 2),
        "wer": round(jiwer.wer(refs, hyps), 6),
        "cer": round(jiwer.cer(refs, hyps), 6),
        "substitutions": words.substitutions,
        "deletions": words.deletions,
        "insertions": words.insertions,
        "reference_words": words.hits + words.substitutions + words.deletions,
        "median_rtf": round(statistics.median(r.rtf for r in results), 6),
        "p95_rtf": round(percentile((r.rtf for r in results), 0.95), 6),
        "median_first_partial_audio_ms": (
            round(statistics.median(finite_first_audio), 2)
            if finite_first_audio else None
        ),
        "p95_first_partial_audio_ms": (
            round(percentile(finite_first_audio, 0.95), 2)
            if finite_first_audio else None
        ),
        "median_first_partial_compute_ms": (
            round(statistics.median(finite_first_compute), 2)
            if finite_first_compute else None
        ),
        "median_finalization_extra_ms": (
            round(statistics.median(finite_final), 2)
            if finite_final else None
        ),
        "p95_finalization_extra_ms": (
            round(percentile(finite_final, 0.95), 2)
            if finite_final else None
        ),
        "p95_decode_step_ms": round(
            percentile((r.decode_step_p95_ms for r in results), 0.95),
            2,
        ),
        "empty_hypotheses": sum(not result.hypothesis for result in results),
    }


def stress_configs() -> list[BenchmarkConfig]:
    return [
        BenchmarkConfig("attenuation", f"production_{db}db_down", attenuation_db=db)
        for db in STRESS_ATTENUATION_DB
    ]


def validation_configs() -> list[BenchmarkConfig]:
    penalties = (0.0, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0)
    return [
        BenchmarkConfig(
            "blank_boundary",
            f"blank_{penalty:g}_{db}db_down",
            blank_penalty=penalty,
            attenuation_db=db,
        )
        for db in SWEEP_ATTENUATION_DB
        for penalty in penalties
    ]


def sweep_configs() -> list[BenchmarkConfig]:
    configs: list[BenchmarkConfig] = []
    for attenuation in SWEEP_ATTENUATION_DB:
        for endpoint in (0.4, 0.6, 0.8, 1.0, 1.2):
            configs.append(
                BenchmarkConfig(
                    family="endpoint",
                    label=f"endpoint_{endpoint:.1f}s_{attenuation}db_down",
                    endpoint_s=endpoint,
                    attenuation_db=attenuation,
                ),
            )
        for chunk_ms in (50, 100, 200):
            configs.append(
                BenchmarkConfig(
                    family="feed_chunk",
                    label=f"chunk_{chunk_ms}ms_{attenuation}db_down",
                    chunk_ms=chunk_ms,
                    attenuation_db=attenuation,
                ),
            )
        for threads in (2, 4, 6):
            configs.append(
                BenchmarkConfig(
                    family="threads",
                    label=f"threads_{threads}_{attenuation}db_down",
                    threads=threads,
                    attenuation_db=attenuation,
                ),
            )
        for penalty in (0.0, 0.2, 0.5, 1.0):
            configs.append(
                BenchmarkConfig(
                    family="blank_penalty",
                    label=f"blank_{penalty:.1f}_{attenuation}db_down",
                    blank_penalty=penalty,
                    attenuation_db=attenuation,
                ),
            )
    return configs


def run_config(
    model_dir: Path,
    config: BenchmarkConfig,
    samples: list[Sample],
) -> tuple[dict, list[dict]]:
    print(
        f"RUN {config.label}: n={len(samples)} threads={config.threads} "
        f"chunk={config.chunk_ms}ms endpoint={config.endpoint_s}s "
        f"blank={config.blank_penalty} attenuation={config.attenuation_db}dB",
        flush=True,
    )
    recognizer, init_ms = build_recognizer(model_dir, config)
    results: list[SampleResult] = []
    try:
        for position, sample in enumerate(samples, start=1):
            results.append(decode_one(recognizer, sample, config))
            if position % 8 == 0 or position == len(samples):
                print(f"  {position}/{len(samples)}", flush=True)
    finally:
        del recognizer
        gc.collect()

    summary = summarize(config, init_ms, results)
    print(
        f"  WER={summary['wer']:.4f} CER={summary['cer']:.4f} "
        f"D={summary['deletions']} I={summary['insertions']} "
        f"medianRTF={summary['median_rtf']:.3f} "
        f"p95step={summary['p95_decode_step_ms']:.1f}ms",
        flush=True,
    )
    detail = [
        {
            **asdict(config),
            **asdict(result),
        }
        for result in results
    ]
    return summary, detail


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--accuracy-count", type=int, default=64)
    parser.add_argument("--sweep-count", type=int, default=12)
    parser.add_argument("--validation-only", action="store_true")
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    print("Loading deterministic FLEURS German sample...", flush=True)
    accuracy_samples = load_samples(args.dataset, args.accuracy_count, SEED)
    sweep_samples = accuracy_samples[: min(args.sweep_count, len(accuracy_samples))]
    print(
        f"Loaded {len(accuracy_samples)} accuracy samples; "
        f"{len(sweep_samples)} used for parameter sweeps",
        flush=True,
    )

    summaries: list[dict] = []
    details: list[dict] = []

    primary_configs = validation_configs() if args.validation_only else stress_configs()
    for config in primary_configs:
        summary, rows = run_config(args.model_dir, config, accuracy_samples)
        summaries.append(summary)
        details.extend(rows)

    if not args.validation_only:
        for config in sweep_configs():
            summary, rows = run_config(args.model_dir, config, sweep_samples)
            summaries.append(summary)
            details.extend(rows)

    write_csv(args.output_dir / "summary.csv", summaries)
    write_csv(args.output_dir / "samples.csv", details)
    (args.output_dir / "summary.json").write_text(
        json.dumps(summaries, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    environment = {
        "seed": SEED,
        "sample_rate": SAMPLE_RATE,
        "accuracy_count": len(accuracy_samples),
        "sweep_count": len(sweep_samples),
        "validation_only": args.validation_only,
        "stress_attenuation_db": STRESS_ATTENUATION_DB,
        "sweep_attenuation_db": SWEEP_ATTENUATION_DB,
        "sherpa_onnx_version": getattr(sherpa_onnx, "__version__", "unknown"),
        "numpy_version": np.__version__,
    }
    (args.output_dir / "environment.json").write_text(
        json.dumps(environment, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Results written to {args.output_dir}", flush=True)

if __name__ == "__main__":
    main()
