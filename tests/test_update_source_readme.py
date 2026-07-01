from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[1]


def _write_module(source_dir: Path, module: str, ext_name: str) -> None:
    module_dir = source_dir / "src" / "ko" / module
    module_dir.mkdir(parents=True)
    (module_dir / "build.gradle").write_text(
        "\n".join([
            "ext {",
            f"    extName = '{ext_name}'",
            "    isNsfw = false",
            "}",
            "",
        ]),
        encoding="utf-8",
    )


class UpdateSourceReadmeTest(unittest.TestCase):
    def test_rewrites_managed_block_and_preserves_rest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_dir = Path(temp_dir) / "source"
            (source_dir / "scripts").mkdir(parents=True)
            _write_module(source_dir, "alpha", "Alpha")
            _write_module(source_dir, "beta", "Beta")

            readme = (
                "# Source\n\n"
                "보존될 소개 문단.\n\n"
                "<!-- extensions:start -->\n"
                "| stale |\n"
                "<!-- extensions:end -->\n\n"
                "## 빌드\n"
            )
            readme_path = source_dir / "README.md"
            readme_path.write_text(readme, encoding="utf-8")

            subprocess.run(
                [
                    sys.executable,
                    str(ROOT_DIR / "scripts" / "update_source_readme.py"),
                    "--source-dir",
                    str(source_dir),
                ],
                check=True,
                cwd=ROOT_DIR,
            )

            updated = readme_path.read_text(encoding="utf-8")
            self.assertIn("보존될 소개 문단.", updated)
            self.assertIn("## 빌드", updated)
            self.assertNotIn("| stale |", updated)
            self.assertIn("| Alpha | `ko/alpha` | ✅ |", updated)
            self.assertIn("| Beta | `ko/beta` | ✅ |", updated)

            # 이미 최신이면 --check 는 0을 반환한다.
            result = subprocess.run(
                [
                    sys.executable,
                    str(ROOT_DIR / "scripts" / "update_source_readme.py"),
                    "--source-dir",
                    str(source_dir),
                    "--check",
                ],
                cwd=ROOT_DIR,
            )
            self.assertEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
