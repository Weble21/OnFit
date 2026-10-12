FROM python:3.12-slim-bookworm
# OpenCV (pulled in by PaddleX) needs libGL/glib; Paddle needs OpenMP.
RUN apt-get update -qq && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10002 ocr && useradd --uid 10002 --gid ocr --no-create-home ocr
WORKDIR /app
COPY ocr-service/requirements.txt ocr-service/requirements-paddle.txt ocr-service/requirements-test.txt ./
RUN pip install --no-cache-dir -r requirements-paddle.txt -r requirements-test.txt
COPY ocr-service/app.py ocr-service/prepare_model.py ocr-service/test_app.py ./
# Models are baked into the image, so the running service never downloads anything.
ENV PADDLE_PDX_CACHE_HOME=/app/models PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True OCR_THREADS=4
RUN python -m pytest test_app.py -q && python prepare_model.py && chmod -R a+rX /app/models
USER ocr
EXPOSE 8002
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8002/health', timeout=4)"
CMD ["python", "-m", "uvicorn", "app:app", "--factory", "--host", "0.0.0.0", "--port", "8002", "--no-access-log"]
