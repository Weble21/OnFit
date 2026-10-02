"""Run once with network access before starting the private inference service."""
from sentence_transformers import SentenceTransformer
from app import MODEL_ID, MODEL_REVISION

model = SentenceTransformer(MODEL_ID, revision=MODEL_REVISION, trust_remote_code=False, device="cpu")
print(f"Pinned model cached: {MODEL_ID}@{MODEL_REVISION}; dimensions={model.get_sentence_embedding_dimension()}")
