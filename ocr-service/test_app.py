import io

import pytest
from fastapi.testclient import TestClient
from PIL import Image

import app as ocr_app
from app import Box, assemble, create_app, tiles

TOKEN = "test-token"


def png(width=200, height=100, mode="RGB", color=(255, 255, 255)):
    output = io.BytesIO()
    Image.new(mode, (width, height), color).save(output, format="PNG")
    return output.getvalue()


class FakeEngine:
    """Returns fixed boxes in image coordinates of the whole page, shifted into each strip it is given."""

    def __init__(self, page_boxes=(), page_height=None):
        self.page_boxes = list(page_boxes)
        self.calls = []
        self.top = 0

    def read(self, bgr):
        height = bgr.shape[0]
        self.calls.append(bgr)
        top = self.top
        self.top += ocr_app.TILE_HEIGHT
        return [Box(b.x0, b.y0 - top, b.x1, b.y1 - top, b.text, b.score)
                for b in self.page_boxes if b.y0 >= top and b.y1 <= top + height]


def client(engine):
    # Entering the client runs the app's startup, which installs the engine.
    return TestClient(create_app(engine=engine, token=TOKEN)).__enter__()


def post(test_client, body, content_type="image/png", token=TOKEN):
    headers = {"Content-Type": content_type}
    if token:
        headers["X-OnFit-OCR-Token"] = token
    return test_client.post("/v1/ocr", content=body, headers=headers)


def test_strips_cover_the_page_once_and_overlap():
    assert tiles(1000) == [(0, 1000, 0, 1000)]
    strips = tiles(5000)
    assert strips[0][0] == 0 and strips[-1][1] == 5000
    # Keep ranges are contiguous: every row belongs to exactly one strip.
    assert [s[2] for s in strips[1:]] == [s[3] for s in strips[:-1]]
    assert strips[0][2] == 0 and strips[-1][3] == 5000
    assert all(bottom - top <= ocr_app.TILE_HEIGHT + ocr_app.OVERLAP for top, bottom, _, _ in strips)


def test_rows_join_left_to_right_and_large_gaps_become_blank_lines():
    lines = assemble([
        Box(300, 12, 400, 40, "개발자", 0.9), Box(10, 10, 280, 42, "백엔드", 0.9),
        Box(10, 60, 300, 90, "Java Spring", 0.9), Box(10, 300, 200, 330, "우대 사항", 0.9),
    ])
    assert lines == ["백엔드 개발자", "Java Spring", "", "우대 사항"]


def test_long_page_lines_in_the_overlap_are_read_once_and_low_scores_dropped():
    height = 4000
    boxes = [Box(10, 100, 300, 130, "회사 소개", 0.99),
             Box(10, 1640, 300, 1670, "주요 업무", 0.98),  # inside the first overlap: seen by two strips
             Box(10, 1700, 30, 1730, "2", 0.6),  # decoration read with low confidence
             Box(10, 3900, 300, 3930, "접수 방법", 0.97)]
    engine = FakeEngine(boxes)
    response = post(client(engine), png(800, height))
    assert response.status_code == 200
    body = response.json()
    assert body["text"].split("\n") == ["회사 소개", "", "주요 업무", "", "접수 방법"]
    assert body["tiles"] == len(engine.calls) == len(tiles(height))


def test_rejects_missing_token_wrong_type_mismatch_and_oversize_without_reading():
    engine = FakeEngine([Box(0, 0, 10, 10, "x", 1.0)])
    test_client = client(engine)
    assert post(test_client, png(), token=None).status_code == 401
    assert post(test_client, png(), token="wrong").status_code == 401
    assert post(test_client, png(), content_type="application/pdf").status_code == 415
    assert post(test_client, png(), content_type="image/jpeg").status_code == 422
    assert post(test_client, b"not an image").status_code == 422
    too_big = test_client.post("/v1/ocr", content=b"0" * (ocr_app.MAX_BYTES + 1),
                               headers={"Content-Type": "image/png", "X-OnFit-OCR-Token": TOKEN})
    assert too_big.status_code == 413
    assert engine.calls == []


def test_too_many_pixels_and_blank_images_are_unprocessable(monkeypatch):
    monkeypatch.setattr(ocr_app, "MAX_PIXELS", 100)
    assert post(client(FakeEngine()), png(20, 20)).status_code == 422
    monkeypatch.undo()
    assert post(client(FakeEngine()), png()).status_code == 422


def test_transparent_background_is_read_as_white():
    engine = FakeEngine()
    post(client(engine), png(10, 10, mode="RGBA", color=(0, 0, 0, 0)))
    assert engine.calls[0][0, 0].tolist() == [255, 255, 255]


def test_service_refuses_to_start_without_a_token(monkeypatch):
    monkeypatch.delenv("OCR_SERVICE_TOKEN", raising=False)
    monkeypatch.delenv("OCR_SERVICE_TOKEN_FILE", raising=False)
    with pytest.raises(RuntimeError):
        create_app(engine=FakeEngine())
