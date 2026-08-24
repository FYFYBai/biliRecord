import argparse
import json
import sys
from pathlib import Path

from faster_whisper import WhisperModel


def emit(payload):
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--device", choices=("cpu", "cuda"), required=True)
    parser.add_argument("--compute-type", required=True)
    parser.add_argument("--model-dir", required=True)
    args = parser.parse_args()

    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    language = manifest.get("language") or None
    files = manifest["segments"]
    emit({"kind": "status", "message": "正在载入语音模型", "progress": 0.02})
    model = WhisperModel(
        args.model,
        device=args.device,
        compute_type=args.compute_type,
        download_root=args.model_dir,
    )

    detected_language = language or "auto"
    for index, item in enumerate(files):
        emit({
            "kind": "status",
            "message": f"正在转录分段 {index + 1}/{len(files)}",
            "progress": index / max(1, len(files)),
        })
        segments, info = model.transcribe(
            item["path"],
            language=language,
            beam_size=5,
            vad_filter=True,
            condition_on_previous_text=True,
        )
        detected_language = info.language or detected_language
        base = int(item["offsetMs"])
        for segment in segments:
            text = segment.text.strip()
            if not text:
                continue
            emit({
                "kind": "segment",
                "segmentPath": item["relativePath"],
                "startOffsetMs": base + round(segment.start * 1000),
                "endOffsetMs": base + round(segment.end * 1000),
                "text": text,
                "language": info.language or detected_language,
            })

    emit({
        "kind": "complete",
        "message": "转录完成",
        "progress": 1.0,
        "language": detected_language,
    })


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"transcription failed: {error}", file=sys.stderr, flush=True)
        raise
