"""Source staging checks, no Android toolchain or network needed."""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/stage_core.py"
spec = importlib.util.spec_from_file_location("stage_core", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class StageCoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.source = self.base / "upstream"
        self.source.mkdir()
        for rel in module.REQUIRED_FILES:
            path = self.source / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("// synthetic source for file: " + rel, encoding="utf-8")
        app = self.source / "app/src/main/AndroidManifest.xml"
        app.parent.mkdir(parents=True)
        app.write_text("<manifest />", encoding="utf-8")
        subprocess.run(["git", "init", "-q"], cwd=self.source, check=True)
        subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.test",
                        "commit", "-q", "--allow-empty", "-m", "fixture"], cwd=self.source, check=True)
        self.sha = module.source_sha(self.source)

    def tearDown(self):
        self.tmp.cleanup()

    def test_core_only_and_unmodified_license(self):
        dest = self.base / "lite"
        report = module.stage(self.source, dest, expected_sha=self.sha)
        self.assertEqual(report["modules"], ["core"])
        self.assertTrue((dest / "core/build.gradle.kts").exists())
        self.assertTrue((dest / "LICENSE").exists())
        self.assertFalse((dest / "app").exists())
        self.assertIn('include(":core")', (dest / "settings.gradle.kts").read_text())
        self.assertNotIn('include(":app")', (dest / "settings.gradle.kts").read_text())
        adapter = dest / "core/src/main/kotlin/app/hush/music/lite/HushLiteSongSearch.kt"
        self.assertTrue(adapter.exists())
        adapter_content = adapter.read_text(encoding="utf-8")
        self.assertIn("YouTube.SearchFilter.FILTER_SONG", adapter_content)
        self.assertIn("useAccountContext = YouTube.currentPlaybackAuthState().hasPlaybackLoginContext", adapter_content)
        self.assertIn("videoId = song.id", adapter_content)
        self.assertIn("song.endpoint ?: WatchEndpoint(videoId = song.id)", adapter_content)
        stream = dest / "core/src/main/kotlin/app/hush/music/lite/HushLiteStreamResolver.kt"
        self.assertTrue(stream.exists())
        self.assertIn("NewPipeUtils.getStreamUrl(", stream.read_text(encoding="utf-8"))
        self.assertIn("audioUrl(videoId: String)", stream.read_text(encoding="utf-8"))
        self.assertFalse(report["apk_generated"])
        self.assertFalse(report["compile_verified"])

    def test_wrong_revision_rejected(self):
        with self.assertRaisesRegex(ValueError, "Wrong Hush revision"):
            module.stage(self.source, self.base / "lite", expected_sha="0" * 40)
        self.assertFalse((self.base / "lite").exists())

    def test_overwrite_refused(self):
        dest = self.base / "lite"
        dest.mkdir()
        (dest / "important.txt").write_text("DO NOT DELETE")
        with self.assertRaises(FileExistsError):
            module.stage(self.source, dest, expected_sha=self.sha)
        self.assertEqual((dest / "important.txt").read_text(), "DO NOT DELETE")

    def test_missing_source_file_rejected(self):
        (self.source / "core/build.gradle.kts").unlink()
        with self.assertRaises(FileNotFoundError):
            module.stage(self.source, self.base / "lite", expected_sha=self.sha)


if __name__ == "__main__":
    unittest.main()
