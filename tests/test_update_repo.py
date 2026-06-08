from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]


class UpdateRepoTest(unittest.TestCase):
    def test_preserves_managed_deploy_pages(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            work_dir = Path(temp_dir)
            source_dir = work_dir / "source"
            deploy_dir = work_dir / "deploy"
            module_dir = source_dir / "src" / "ko" / "sample"
            apk_dir = module_dir / "build" / "outputs" / "apk" / "release"
            icon_dir = module_dir / "res" / "mipmap-mdpi"

            apk_dir.mkdir(parents=True)
            icon_dir.mkdir(parents=True)
            (source_dir / "scripts").mkdir(parents=True)
            deploy_dir.mkdir()

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

            readme = "# Managed README\n"
            index_html = "<!doctype html><title>Managed</title>\n"
            (deploy_dir / "README.md").write_text(readme, encoding="utf-8")
            (deploy_dir / "index.html").write_text(index_html, encoding="utf-8")

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

            self.assertEqual(readme, (deploy_dir / "README.md").read_text(encoding="utf-8"))
            self.assertEqual(index_html, (deploy_dir / "index.html").read_text(encoding="utf-8"))
            self.assertTrue((deploy_dir / "index.json").exists())
            self.assertTrue((deploy_dir / "index.min.json").exists())
            self.assertTrue((deploy_dir / "repo.json").exists())


if __name__ == "__main__":
    unittest.main()
