from decimal import Decimal
import numpy as np
from fastapi.testclient import TestClient
from app import MODEL_VERSION, calibrate, create_app


class FakeEmbedder:
    def encode(self, texts, **kwargs):
        assert kwargs["normalize_embeddings"] is True
        assert texts == ["Python 문서 분류", "보험 문서 AI", "에너지 수요 예측"]
        return np.array([[1, 0], [0.8, 0.6], [0.1, 0.995]], dtype=np.float32)


def test_contract_reproducibility_and_calibration():
    assert calibrate(-1) == Decimal("0.00")
    assert calibrate(.20) == Decimal("0.00")
    assert calibrate(.50) == Decimal("50.00")
    assert calibrate(.80) == Decimal("100.00")
    assert calibrate(1) == Decimal("100.00")
    payload = {"modelVersion": MODEL_VERSION, "profileText": "Python 문서 분류", "jobs": [
        {"jobId": 36, "text": "보험 문서 AI"}, {"jobId": 26, "text": "에너지 수요 예측"}]}
    with TestClient(create_app(FakeEmbedder(), token="private-test-token")) as client:
        assert client.get("/health").json() == {"status": "ok", "modelVersion": MODEL_VERSION}
        assert client.post("/v1/semantic-score", json=payload).status_code == 401
        headers = {"X-OnFit-AI-Token": "private-test-token"}
        first = client.post("/v1/semantic-score", json=payload, headers=headers)
        assert first.status_code == 200
        assert first.json() == {"modelVersion": MODEL_VERSION, "scores": [
            {"jobId": 36, "score": "100.00"}, {"jobId": 26, "score": "0.00"}]}
        assert client.post("/v1/semantic-score", json=payload, headers=headers).json() == first.json()
        assert client.post("/v1/semantic-score", json={**payload, "modelVersion": "wrong"}, headers=headers).status_code == 409


def test_rejects_oversize_duplicate_and_unexpected_fields():
    with TestClient(create_app(FakeEmbedder(), token="private-test-token")) as client:
        headers = {"X-OnFit-AI-Token": "private-test-token"}
        base = {"modelVersion": MODEL_VERSION, "profileText": "text", "jobs": [{"jobId": 1, "text": "job"}]}
        for payload in [
            {**base, "profileText": "x" * 2049},
            {**base, "jobs": [{"jobId": 1, "text": "x"}, {"jobId": 1, "text": "y"}]},
            {**base, "jobs": [{"jobId": 1, "text": "x" * 1025}]},
            {**base, "extra": "email@example.com"},
        ]:
            assert client.post("/v1/semantic-score", json=payload, headers=headers).status_code == 422
