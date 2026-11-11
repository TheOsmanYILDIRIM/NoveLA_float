import json
import tempfile
import unittest
from pathlib import Path

from prepare_assets import validate_tokenizer, verify


class OfflineAssetsTest(unittest.TestCase):
    def test_requires_unigram_and_original_markers(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "tokenizer.json"
            valid = {
                "model": {"type": "Unigram", "unk_id": 0, "vocab": [["<unk>", 0.0]]},
                "added_tokens": [{"content": t, "id": 100 + i} for i, t in
                                 enumerate(("[P]", "[E]", "[SEP_TEXT]"))],
            }
            path.write_text(json.dumps(valid), encoding="utf-8")
            validate_tokenizer(path)
            valid["model"]["type"] = "WordPiece"
            path.write_text(json.dumps(valid), encoding="utf-8")
            with self.assertRaises(ValueError):
                validate_tokenizer(path)
            valid["model"]["type"] = "Unigram"
            valid["added_tokens"] = []
            path.write_text(json.dumps(valid), encoding="utf-8")
            with self.assertRaises(ValueError):
                validate_tokenizer(path)

    def test_verify_rejects_missing_model(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            (path / "manifest.json").write_text(json.dumps({
                "model_id": "fastino/gliner2.5-small-v1",
                "source_repo": "nicolasembleton/gliner2.5-small-v1-onnx",
                "model_bytes": 300000000,
            }), encoding="utf-8")
            with self.assertRaises(FileNotFoundError):
                verify(path)


if __name__ == "__main__":
    unittest.main()
