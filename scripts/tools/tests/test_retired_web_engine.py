"""Offline source-boundary checks for the retired Gecko compatibility engine."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class RetiredWebEngineTest(unittest.TestCase):
    def text(self, name):
        return (ROOT / name).read_text()

    def test_manifest_has_no_mozilla_components_or_factory(self):
        manifest = ET.fromstring(self.text("app/src/main/AndroidManifest.xml"))
        for element in manifest.iter():
            for attribute in ("name", "appComponentFactory"):
                value = element.get(ANDROID + attribute, "")
                self.assertFalse(value.startswith("org.mozilla."), value)
                self.assertNotIn("GeckoComponentFactory", value)

    def test_build_has_no_gecko_module_repository_or_download_config(self):
        settings = self.text("settings.gradle.kts")
        self.assertNotIn('include(":web-runtime")', settings)
        self.assertNotIn("maven.mozilla.org", settings)
        self.assertNotIn("org.mozilla", self.text("gradle/libs.versions.toml"))
        build = self.text("app/build.gradle.kts")
        for retired in ("WEB_RUNTIME_", "webRuntime.url", "web-runtime/runtime.properties"):
            self.assertNotIn(retired, build)

    def test_retired_loader_host_and_bridge_sources_are_removed(self):
        for name in (
            "web-runtime/build.gradle.kts",
            "app/src/main/java/io/stamethyst/web/GeckoComponentFactory.java",
            "app/src/main/java/io/stamethyst/web/GeckoDependencyLoader.java",
            "app/src/main/java/io/stamethyst/web/GeckoDependencyArchive.java",
            "app/src/main/java/io/stamethyst/web/SlingAssetServer.java",
            "app/src/main/java/io/stamethyst/web/SlingLauncherChannel.java",
            "app/src/main/java/io/stamethyst/SlingBreakWebView.kt",
            "app/src/main/assets/sling-gecko-launcher-bridge.js",
        ):
            with self.subTest(path=name):
                self.assertFalse((ROOT / name).exists())

    def test_old_mode_is_not_read_or_reported_by_launcher(self):
        source = ROOT / "app/src/main/java/io/stamethyst"
        for path in source.rglob("*.kt"):
            text = path.read_text()
            with self.subTest(path=path.relative_to(ROOT)):
                for retired in ("SlingBreakEngineMode", "slingBreakEngineMode", "sling_break_engine_mode"):
                    self.assertNotIn(retired, text)
        for path in (ROOT / "app/src/main/res").glob("values*/strings.xml"):
            for element in ET.fromstring(path.read_text()):
                self.assertFalse(element.get("name", "").startswith((
                    "settings_sling_break_mode_", "settings_sling_break_web_",
                )))

    def test_godot_keeps_legacy_save_migration_without_mozilla_sdk(self):
        base = "app/src/main/java/io/stamethyst/godot/"
        activity = self.text(base + "GodotMinigameActivity.java")
        self.assertIn("new LegacySlingBreakMigration(", activity)
        migration = self.text(base + "LegacySlingBreakMigration.java")
        self.assertIn("LegacyGeckoSlingBreakSave.read(files)", migration)
        reader = self.text(base + "LegacyGeckoSlingBreakSave.java")
        self.assertIn("SQLiteDatabase.OPEN_READONLY", reader)
        self.assertNotIn("import org.mozilla.", reader)

    def test_packaged_output_guard_covers_manifest_dex_and_native_libraries(self):
        build = self.text("app/build.gradle.kts")
        for required in (
            "verify${capitalized}EngineBoundaries",
            "SingleArtifact.MERGED_MANIFEST",
            'contains("org.mozilla.")',
            'startsWith("Lorg/mozilla/")',
            'endsWith("/libxul.so")',
        ):
            self.assertIn(required, build)


if __name__ == "__main__":
    unittest.main()
