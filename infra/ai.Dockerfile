FROM python:3.12-slim-bookworm
RUN groupadd --gid 10003 ai && useradd --uid 10003 --gid ai --no-create-home ai
WORKDIR /app
COPY ai-service/requirements.txt ai-service/requirements-lock.txt ai-service/requirements-test.txt ./
# CPU-only PyTorch wheels keep the image far smaller; the lock file still pins every version.
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu \
        --extra-index-url https://pypi.org/simple -r requirements-test.txt
COPY ai-service/app.py ai-service/job_llm.py ai-service/prepare_model.py ai-service/test_app.py ai-service/test_job_llm.py ./
# The pinned embedding model is baked into the image, so the running service never downloads anything.
# test_job_llm.py uses a fake Claude client; the build never calls the Anthropic API.
ENV HF_HOME=/app/models
RUN python -m pytest test_app.py test_job_llm.py -q && python prepare_model.py && chmod -R a+rX /app/models
USER ai
EXPOSE 8001
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8001/health', timeout=4)"
CMD ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8001", "--no-access-log"]
