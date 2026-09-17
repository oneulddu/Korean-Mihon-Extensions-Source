from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from list_extensions import needs_prune, parse_changed_extensions


class ChangedExtensionsTest(unittest.TestCase):
    def test_removed_extension_requests_prune_without_rebuilding_others(self):
        paths = ["src/ko/removed/build.gradle", "src/ko/removed/src/Source.kt"]
        self.assertEqual(set(), parse_changed_extensions(paths, {"remaining"}))
        self.assertTrue(needs_prune(paths, {"remaining"}))

    def test_ordinary_source_change_does_not_request_prune(self):
        paths = ["src/ko/remaining/src/Source.kt"]
        self.assertEqual({"remaining"}, parse_changed_extensions(paths, {"remaining"}))
        self.assertFalse(needs_prune(paths, {"remaining"}))


if __name__ == "__main__":
    unittest.main()
