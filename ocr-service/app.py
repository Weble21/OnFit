"""Private, stateless OCR service for OnFit job posting images (PaddleOCR, Korean + English).

The image arrives in the request body, is read in memory and is never stored or logged.
"""

from contextlib import asynccontextmanager
from dataclasses import dataclass
import hmac
import io
import os
import threading
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from PIL import Image, ImageOps, UnidentifiedImageError

DET_MODEL = "PP-OCRv5_mobile_det"
REC_MODEL = "korean_PP-OCRv5_mobile_rec"
MAX_BYTES = 12 * 1024 * 1024
MAX_PIXELS = 20_000_000
FORMATS = {"image/png": "PNG", "image/jpeg": "JPEG", "image/webp": "WEBP"}
# Long postings are read in horizontal strips: the detector shrinks anything taller than 4000px,
# which makes body text too small to read. Strips overlap so a line cut at one edge is whole in the next.
TILE_HEIGHT = 1600
OVERLAP = 160
MIN_SCORE = 0.75


@dataclass(frozen=True)
class Box:
    x0: float
    y0: float
    x1: float
    y1: float
    text: str
    score: float

    @property
    def cy(self) -> float:
        return (self.y0 + self.y1) / 2

    @property
    def height(self) -> float:
        return max(1.0, self.y1 - self.y0)


def tiles(height: int) -> list[tuple[int, int, int, int]]:
    """(top, bottom, keep_from, keep_to): a box belongs to the strip whose keep range holds its center."""
    if height <= TILE_HEIGHT + OVERLAP:
        return [(0, height, 0, height)]
    result = []
    top = 0
    while True:
        bottom = min(top + TILE_HEIGHT + OVERLAP, height)
        last = bottom == height
        keep_from = 0 if top == 0 else top + OVERLAP // 2
        keep_to = height if last else bottom - OVERLAP // 2
        result.append((top, bottom, keep_from, keep_to))
        if last:
            return result
        top += TILE_HEIGHT


def assemble(boxes: list[Box]) -> list[str]:
    """Joins boxes on the same visual row left to right; a blank line marks a large vertical gap."""
    rows: list[list[Box]] = []
    for box in sorted(boxes, key=lambda b: (b.cy, b.x0)):
        row = rows[-1] if rows else None
        if row and abs(box.cy - row[0].cy) <= 0.5 * min(box.height, row[0].height):
            row.append(box)
        else:
            rows.append([box])
    lines: list[str] = []
    previous: list[Box] | None = None
    for row in rows:
        if previous is not None:
            gap = min(b.y0 for b in row) - max(b.y1 for b in previous)
            if gap > 2 * max(b.height for b in row):
                lines.append("")
        lines.append(" ".join(b.text for b in sorted(row, key=lambda b: b.x0)))
        previous = row
    return lines


def decode(data: bytes, content_type: str) -> Image.Image:
    expected = FORMATS.get(content_type.split(";")[0].strip().lower())
    if expected is None:
        raise HTTPException(status_code=415, detail="Unsupported image type")
    try:
        image = Image.open(io.BytesIO(data))
        if image.format != expected:
            raise HTTPException(status_code=422, detail="Image does not match its type")
        width, height = image.size
        if width * height > MAX_PIXELS:
            raise HTTPException(status_code=422, detail="Image is too large")
        image = ImageOps.exif_transpose(image)
        if image.mode in ("RGBA", "LA") or (image.mode == "P" and "transparency" in image.info):
            # Transparent backgrounds become white, not black, so dark text stays readable.
            rgba = image.convert("RGBA")
            background = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
            image = Image.alpha_composite(background, rgba)
        return image.convert("RGB")
    except HTTPException:
        raise
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        raise HTTPException(status_code=422, detail="Unreadable image")


def recognize(engine: Any, image: Image.Image) -> tuple[list[str], int]:
    import numpy as np

    bgr = np.ascontiguousarray(np.asarray(image)[:, :, ::-1])
    kept: list[Box] = []
    strips = tiles(bgr.shape[0])
    for top, bottom, keep_from, keep_to in strips:
        for box in engine.read(bgr[top:bottom]):
            moved = Box(box.x0, box.y0 + top, box.x1, box.y1 + top, box.text, box.score)
            if keep_from <= moved.cy < keep_to and moved.score >= MIN_SCORE and moved.text.strip():
                kept.append(moved)
    return assemble(kept), len(strips)


class PaddleEngine:
    """Detection + recognition models called directly. The PaddleOCR pipeline wrapper was ~10x slower on CPU."""

    def __init__(self, threads: int):
        from paddleocr import TextDetection, TextRecognition

        self.detector = TextDetection(model_name=DET_MODEL, limit_type="min", limit_side_len=64, cpu_threads=threads)
        self.recognizer = TextRecognition(model_name=REC_MODEL, cpu_threads=threads)
        # Paddle predictors are not safe to share between threads.
        self.lock = threading.Lock()

    def read(self, bgr) -> list[Box]:
        with self.lock:
            polygons = self.detector.predict(bgr)[0]["dt_polys"]
            regions = []
            for polygon in polygons:
                x0, y0 = polygon.min(axis=0)
                x1, y1 = polygon.max(axis=0)
                x0, y0 = max(int(x0) - 2, 0), max(int(y0) - 2, 0)
                x1, y1 = min(int(x1) + 2, bgr.shape[1]), min(int(y1) + 2, bgr.shape[0])
                if x1 - x0 >= 4 and y1 - y0 >= 4:
                    regions.append((x0, y0, x1, y1))
            if not regions:
                return []
            results = self.recognizer.predict([bgr[y0:y1, x0:x1] for x0, y0, x1, y1 in regions], batch_size=8)
            return [Box(x0, y0, x1, y1, str(result["rec_text"]), float(result["rec_score"]))
                    for (x0, y0, x1, y1), result in zip(regions, results)]


def service_token() -> str | None:
    token = os.environ.get("OCR_SERVICE_TOKEN")
    path = os.environ.get("OCR_SERVICE_TOKEN_FILE")
    if not token and path:
        with open(path, encoding="utf-8") as file:
            token = file.read().strip()
    return token or None


def create_app(engine: Any = None, token: str | None = None) -> FastAPI:
    expected_token = token or service_token()
    if not expected_token:
        raise RuntimeError("OCR_SERVICE_TOKEN or OCR_SERVICE_TOKEN_FILE is required")

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.engine = engine or PaddleEngine(int(os.environ.get("OCR_THREADS", "4")))
        yield
        app.state.engine = None

    app = FastAPI(title="OnFit OCR", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok", "models": f"{DET_MODEL}+{REC_MODEL}"}

    @app.post("/v1/ocr")
    async def ocr(request: Request) -> dict[str, Any]:
        supplied = request.headers.get("x-onfit-ocr-token")
        if supplied is None or not hmac.compare_digest(supplied, expected_token):
            raise HTTPException(status_code=401, detail="Unauthorized")
        declared = request.headers.get("content-length")
        if declared is not None and (not declared.isdigit() or int(declared) > MAX_BYTES):
            raise HTTPException(status_code=413, detail="Image is too large")
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > MAX_BYTES:
                raise HTTPException(status_code=413, detail="Image is too large")
        image = decode(bytes(body), request.headers.get("content-type", ""))
        lines, strips = await run_in_threadpool(recognize, app.state.engine, image)
        if not any(line.strip() for line in lines):
            raise HTTPException(status_code=422, detail="No text found")
        return {"text": "\n".join(lines), "lines": sum(1 for line in lines if line), "tiles": strips}

    return app


def app() -> FastAPI:
    """uvicorn factory: `uvicorn app:app --factory`."""
    return create_app()
