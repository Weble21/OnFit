import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import probe


class ProbeTests(unittest.TestCase):
    def test_missing_configuration_never_calls_network(self):
        with patch.dict(os.environ, {}, clear=True):
            factory = lambda **kw: self.fail("Unexpected network construction")
            result = probe.probe("saramin", ["Example"], factory=factory)
        self.assertEqual("not_configured", result["state"])
        self.assertEqual(0, result["requests"])

    def test_read_only_probe_maps_samples_and_preserves_partial_state(self):
        class FakeHttp:
            count = 0
            def __init__(self, limit): self.limit = limit
        def adapter(http, companies, pages, report):
            http.count = 1
            report["incomplete"] = 1
            yield {"content": {"companyName": "Example", "title": "Backend", "location": "Seoul", "status": "OPEN"}}
        with patch.dict(os.environ, {name: "fixture" for name in probe.REQUIRED["saramin"]}, clear=True), \
                patch.dict(probe.ADAPTERS, {"saramin": adapter}):
            result = probe.probe("saramin", ["Example"], factory=FakeHttp)
        self.assertEqual("partial", result["state"])
        self.assertEqual(1, result["collected"])
        self.assertEqual(1, result["requests"])

    def test_exception_messages_and_keys_are_not_printed(self):
        class FakeHttp:
            count = 0
            def __init__(self, limit): pass
        with patch.dict(os.environ, {name: "secret-value" for name in probe.REQUIRED["saramin"]}, clear=True), \
                patch.dict(probe.ADAPTERS, {"saramin": lambda *args: (_ for _ in ()).throw(ValueError("secret-value"))}):
            result = probe.probe("saramin", ["Example"], factory=FakeHttp)
        self.assertEqual("failed", result["state"])
        self.assertNotIn("secret-value", json.dumps(result))

    def test_environment_loader_loads_only_source_keys_and_respects_exports(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text('ONFIT_SARAMIN_API_KEY="local"\nDB_PASSWORD=private\nONFIT_SMTP_PASSWORD=private\n', encoding="utf-8")
            with patch.dict(os.environ, {}, clear=True):
                probe.load_environment(path)
                self.assertEqual("local", os.environ["ONFIT_SARAMIN_API_KEY"])
                self.assertNotIn("DB_PASSWORD", os.environ)
                self.assertNotIn("ONFIT_SMTP_PASSWORD", os.environ)
                os.environ["ONFIT_SARAMIN_API_KEY"] = "exported"
                probe.load_environment(path)
                self.assertEqual("exported", os.environ["ONFIT_SARAMIN_API_KEY"])

    def test_main_expands_company_groups_without_needing_backend_auth(self):
        with patch.dict(os.environ, {}, clear=True), patch("probe.load_environment"), \
                patch("probe.probe", return_value={"state": "not_configured"}) as check, patch("builtins.print"):
            code = probe.main(["--source", "saramin", "--company", "카카오"])
        self.assertEqual(2, code)
        self.assertEqual(["카카오", "카카오페이", "카카오뱅크", "카카오모빌리티"], check.call_args.args[1])


if __name__ == "__main__": unittest.main()
