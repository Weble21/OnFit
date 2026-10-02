"""Runs real pinned weights; this is the model reproducibility gate in CI."""
from fastapi.testclient import TestClient
from app import MODEL_VERSION, create_app


def test_pinned_model_reproduces_same_scores():
    request = {"modelVersion": MODEL_VERSION, "profileText": "AI 엔지니어. Python PyTorch NLP. 문서 분류와 콘텐츠 추천 실험",
               "jobs": [{"jobId": 20, "text": "콘텐츠 추천 연구원. 콘텐츠 추천 알고리즘을 연구합니다."},
                        {"jobId": 26, "text": "에너지 수요 예측 모델을 학습합니다."}]}
    with TestClient(create_app(token="test-token")) as client:
        first = client.post("/v1/semantic-score", headers={"X-OnFit-AI-Token": "test-token"}, json=request)
        assert first.status_code == 200, first.text
        assert first.json() == client.post("/v1/semantic-score", headers={"X-OnFit-AI-Token": "test-token"}, json=request).json()
        assert first.json()["modelVersion"] == MODEL_VERSION
        assert first.json()["scores"][0]["score"] > first.json()["scores"][1]["score"]
