from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]


class UpdateRepoTest(unittest.TestCase):
    def _prepare_source(self, source_dir: Path) -> None:
        module_dir = source_dir / "src" / "ko" / "sample"
        apk_dir = module_dir / "build" / "outputs" / "apk" / "release"
        icon_dir = module_dir / "res" / "mipmap-mdpi"

        apk_dir.mkdir(parents=True)
        icon_dir.mkdir(parents=True)
        (source_dir / "scripts").mkdir(parents=True)

        (module_dir / "build.gradle").write_text(
            "\n".join([
                "ext {",
                "    extName = 'Sample'",
                "    extClass = '.Sample'",
                "    extVersionCode = 1",
                "    isNsfw = false",
                "}",
                "",
                'apply plugin: "kei.plugins.extension.legacy"',
                "",
            ]),
            encoding="utf-8",
        )
        (source_dir / "scripts" / "extensions.json").write_text(
            json.dumps({
                "extensions": {
                    "sample": {
                        "sources": [
                            {
                                "name": "Sample",
                                "lang": "ko",
                                "baseUrl": "https://example.com",
                                "id": "1234",
                            },
                        ],
                    },
                },
            }),
            encoding="utf-8",
        )
        (apk_dir / "tachiyomi-ko.sample-v1.0.1-release.apk").write_bytes(b"apk")
        (icon_dir / "ic_launcher.png").write_bytes(b"png")

    def _run_update(self, source_dir: Path, deploy_dir: Path) -> None:
        subprocess.run(
            [
                sys.executable,
                str(ROOT_DIR / "scripts" / "update_repo.py"),
                "--source-dir",
                str(source_dir),
                "--deploy-dir",
                str(deploy_dir),
                "--extensions",
                "sample",
            ],
            check=True,
            cwd=ROOT_DIR,
        )

    def test_preserves_unmanaged_deploy_pages(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            work_dir = Path(temp_dir)
            source_dir = work_dir / "source"
            deploy_dir = work_dir / "deploy"
            deploy_dir.mkdir()
            self._prepare_source(source_dir)

            # README without the managed markers must stay untouched.
            readme = "# Managed README\n"
            index_html = "<!doctype html><title>Managed</title>\n"
            (deploy_dir / "README.md").write_text(readme, encoding="utf-8")
            (deploy_dir / "index.html").write_text(index_html, encoding="utf-8")

            self._run_update(source_dir, deploy_dir)

            self.assertEqual(readme, (deploy_dir / "README.md").read_text(encoding="utf-8"))
            self.assertEqual(index_html, (deploy_dir / "index.html").read_text(encoding="utf-8"))
            self.assertTrue((deploy_dir / "index.json").exists())
            self.assertTrue((deploy_dir / "index.min.json").exists())
            self.assertTrue((deploy_dir / "repo.json").exists())

    def test_updates_managed_readme_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            work_dir = Path(temp_dir)
            source_dir = work_dir / "source"
            deploy_dir = work_dir / "deploy"
            deploy_dir.mkdir()
            self._prepare_source(source_dir)

            readme = (
                "# Deploy README\n\n"
                "설명 문단은 보존되어야 한다.\n\n"
                "<!-- extensions:start -->\n"
                "| old | table |\n"
                "<!-- extensions:end -->\n\n"
                "## 서명\n"
            )
            (deploy_dir / "README.md").write_text(readme, encoding="utf-8")

            self._run_update(source_dir, deploy_dir)

            updated = (deploy_dir / "README.md").read_text(encoding="utf-8")
            self.assertIn("# Deploy README", updated)
            self.assertIn("설명 문단은 보존되어야 한다.", updated)
            self.assertIn("## 서명", updated)
            self.assertNotIn("| old | table |", updated)
            self.assertIn("| Sample | `1.0.1` |", updated)
            self.assertIn("[example.com](https://example.com)", updated)
            self.assertIn("`…ko.sample`", updated)

    def test_infers_fallback_base_url_for_dynamic_source(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            work_dir = Path(temp_dir)
            source_dir = work_dir / "source"
            deploy_dir = work_dir / "deploy"
            deploy_dir.mkdir()
            self._prepare_source(source_dir)

            (source_dir / "scripts" / "extensions.json").write_text(
                json.dumps({"extensions": {}}),
                encoding="utf-8",
            )
            source_file = source_dir / "src" / "ko" / "sample" / "src" / "Sample.kt"
            source_file.parent.mkdir(parents=True)
            source_file.write_text(
                "\n".join([
                    "class Sample {",
                    '    override val name = "Sample"',
                    '    override val lang = "ko"',
                    '    private val fallbackBaseUrl = "https://fallback.example.com"',
                    "    override val baseUrl: String",
                    "        get() = resolveBaseUrl()",
                    "}",
                ]),
                encoding="utf-8",
            )

            self._run_update(source_dir, deploy_dir)

            index = json.loads((deploy_dir / "index.json").read_text(encoding="utf-8"))
            source = index["extensions"][0]["sources"][0]
            self.assertEqual("https://fallback.example.com", source["homeUrl"])


if __name__ == "__main__":
    unittest.main()
