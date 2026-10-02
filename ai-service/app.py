"""Private, stateless sentence embedding service for OnFit recommendation ranking."""

from contextlib import asynccontextmanager
from decimal import Decimal, ROUND_HALF_UP
import hmac
import os
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

MODEL_ID = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
MODEL_REVISION = "f16484b452bc5449a3ad85665709a2648b51d735"
MODEL_VERSION = f"{MODEL_ID}@{MODEL_REVISION}/text-v1/cal-v1"
COSINE_FLOOR = Decimal("0.20")
COSINE_CEILING = Decimal("0.80")


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class JobText(StrictModel):
    jobId: int = Field(gt=0)
    text: str = Field(min_length=1, max_length=1024)

    @field_validator("text")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("text must not be blank")
        return value


class ScoreRequest(StrictModel):
    modelVersion: str
    profileText: str = Field(min_length=1, max_length=2048)
    jobs: list[JobText] = Field(min_length=1, max_length=50)

    @field_validator("profileText")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("profileText must not be blank")
        return value

    @model_validator(mode="after")
    def unique_jobs(self) -> "ScoreRequest":
        ids = [job.jobId for job in self.jobs]
        if len(ids) != len(set(ids)):
            raise ValueError("jobId must be unique")
        return self


class JobScore(StrictModel):
    jobId: int
    score: Decimal


class ScoreResponse(StrictModel):
    modelVersion: str
    scores: list[JobScore]


def calibrate(cosine: float) -> Decimal:
    """Map cosine <= .20 to 0 and >= .80 to 100, round HALF_UP to two places."""
    value = Decimal(str(float(cosine)))
    if not value.is_finite():
        raise ValueError("nonfinite cosine")
    bounded = min(max(value, COSINE_FLOOR), COSINE_CEILING)
    return ((bounded - COSINE_FLOOR) / (COSINE_CEILING - COSINE_FLOOR) * 100).quantize(
        Decimal("0.01"), rounding=ROUND_HALF_UP
    )


def create_app(embedder: Any = None, token: str | None = None) -> FastAPI:
    expected_token = token or os.environ.get("AI_SERVICE_TOKEN")
    if not expected_token:
        raise RuntimeError("AI_SERVICE_TOKEN is required")

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if embedder is None:
            import torch
            from sentence_transformers import SentenceTransformer

            torch.set_num_threads(int(os.environ.get("AI_TORCH_THREADS", "4")))
            # revision pins the exact weights; no external inference API receives profile text.
            app.state.embedder = SentenceTransformer(
                MODEL_ID, revision=MODEL_REVISION, trust_remote_code=False,
                local_files_only=True, device="cpu"
            )
        else:
            app.state.embedder = embedder
        yield
        app.state.embedder = None

    app = FastAPI(title="OnFit semantic scoring", docs_url=None, redoc_url=None,
                  openapi_url=None, lifespan=lifespan)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok", "modelVersion": MODEL_VERSION}

    @app.post("/v1/semantic-score", response_model=ScoreResponse)
    def score(request: ScoreRequest, x_onfit_ai_token: str | None = Header(default=None)) -> ScoreResponse:
        if x_onfit_ai_token is None or not hmac.compare_digest(x_onfit_ai_token, expected_token):
            raise HTTPException(status_code=401, detail="Unauthorized")
        if request.modelVersion != MODEL_VERSION:
            raise HTTPException(status_code=409, detail="modelVersion mismatch")
        vectors = app.state.embedder.encode(
            [request.profileText] + [job.text for job in request.jobs],
            normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False,
        )
        if len(vectors) != len(request.jobs) + 1:
            raise RuntimeError("Invalid embedding count")
        profile = vectors[0]
        scores = [JobScore(jobId=job.jobId, score=calibrate(float(profile @ vector)))
                  for job, vector in zip(request.jobs, vectors[1:])]
        return ScoreResponse(modelVersion=MODEL_VERSION, scores=scores)

    return app


app = create_app() if os.environ.get("AI_SERVICE_TOKEN") else None
