"""Downloads the pinned PaddleOCR models into PADDLE_PDX_CACHE_HOME once, before the service starts."""
import os
import sys

if not os.environ.get("PADDLE_PDX_CACHE_HOME"):
    sys.exit("Set PADDLE_PDX_CACHE_HOME so the models are cached in a known place.")

from app import DET_MODEL, REC_MODEL, PaddleEngine

PaddleEngine(1)
print(f"Cached {DET_MODEL} and {REC_MODEL} in {os.environ['PADDLE_PDX_CACHE_HOME']}")
