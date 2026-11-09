from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PRODUCT = ROOT / "android" / "product"
CANONICAL_PRODUCT_BUILD = """plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lavasec.app.product"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":foundation"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
"""


class AndroidProductBoundaryTest(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        path = ROOT / relative_path
        self.assertTrue(path.is_file(), f"missing {path}")
        return path.read_text(encoding="utf-8")

    def test_product_module_has_exact_build_contract(self) -> None:
        settings = self.read("android/settings.gradle.kts")
        build = self.read("android/product/build.gradle.kts")

        self.assertEqual(1, settings.count('include(":product")'))
        self.assertEqual(CANONICAL_PRODUCT_BUILD, build)

    def test_product_production_sources_are_platform_neutral(self) -> None:
        result = self.run_verifier(ROOT)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_foundation_verifier_rejects_forbidden_product_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            build = fixture / "android" / "product" / "build.gradle.kts"
            source = build.read_text(encoding="utf-8")
            build.write_text(
                source.replace(
                    'api(project(":foundation"))',
                    'api(project(":foundation"))\n    implementation(project(":vpn"))',
                ),
                encoding="utf-8",
            )

            result = self.run_verifier(fixture)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("product build contract", result.stderr.lower())

    def test_public_coroutine_abi_requires_api_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            build = fixture / "android" / "product" / "build.gradle.kts"
            source = build.read_text(encoding="utf-8")
            build.write_text(
                source.replace(
                    "api(libs.kotlinx.coroutines.core)",
                    "implementation(libs.kotlinx.coroutines.core)",
                ),
                encoding="utf-8",
            )
            self.write_product_source(
                fixture,
                "main",
                "kotlin",
                "package com.lavasec.app.product\n"
                "import kotlinx.coroutines.flow.StateFlow\n"
                "public interface ConsumerVisibleState {\n"
                "    public val state: StateFlow<String>\n"
                "}\n",
            )

            result = self.run_verifier(fixture)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("product build contract", result.stderr.lower())

    def test_foundation_verifier_rejects_forbidden_product_import(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            source = (
                fixture
                / "android"
                / "product"
                / "src"
                / "main"
                / "kotlin"
                / "com"
                / "lavasec"
                / "app"
                / "product"
                / "Forbidden.kt"
            )
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(
                "package com.lavasec.app.product\n\n"
                "import android.content.Context\n\n"
                "internal typealias Forbidden = Context\n",
                encoding="utf-8",
            )

            result = self.run_verifier(fixture)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("product boundary", result.stderr.lower())

    def test_foundation_verifier_rejects_alternate_dependency_syntax(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            build = fixture / "android" / "product" / "build.gradle.kts"
            build.write_text(
                'project.dependencies.add("implementation", project(":vpn"))\n'
                + build.read_text(encoding="utf-8"),
                encoding="utf-8",
            )

            result = self.run_verifier(fixture)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("product build contract", result.stderr.lower())

    def test_foundation_verifier_rejects_external_product_gradle_configuration(self) -> None:
        mutations = {
            "root-project-dependency-injection": (
                "android/build.gradle.kts",
                '\nsubprojects {\n'
                '    if (path == ":product") {\n'
                '        dependencies.add("implementation", project(":vpn"))\n'
                "    }\n"
                "}\n",
            ),
            "settings-project-directory-remap": (
                "android/settings.gradle.kts",
                '\nproject(":product").projectDir = file("product-shadow")\n',
            ),
        }
        for name, (relative, injection) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                path = fixture / relative
                path.write_text(path.read_text(encoding="utf-8") + injection, encoding="utf-8")

                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("product gradle boundary", result.stderr.lower())

    def test_foundation_verifier_pins_product_catalog_dependencies(self) -> None:
        mutations = {
            "core-coordinate": (
                'module = "org.jetbrains.kotlinx:kotlinx-coroutines-core"',
                'module = "org.jetbrains.kotlinx:kotlinx-coroutines-android"',
            ),
            "core-version-reference": (
                'kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", '
                'version.ref = "coroutines" }',
                'kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", '
                'version.ref = "coroutinesTest" }',
            ),
            "test-coordinate": (
                'module = "org.jetbrains.kotlinx:kotlinx-coroutines-test"',
                'module = "org.jetbrains.kotlinx:kotlinx-coroutines-android"',
            ),
            "junit-coordinate": (
                'module = "junit:junit"',
                'module = "org.junit.jupiter:junit-jupiter"',
            ),
            "core-version": ('coroutines = "1.10.2"', 'coroutines = "1.10.1"'),
            "test-version": ('coroutinesTest = "1.10.2"', 'coroutinesTest = "1.10.1"'),
            "junit-version": ('junit = "4.13.2"', 'junit = "4.13.1"'),
        }
        for name, (old, new) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                catalog = fixture / "android" / "gradle" / "libs.versions.toml"
                source = catalog.read_text(encoding="utf-8")
                self.assertIn(old, source)
                catalog.write_text(source.replace(old, new, 1), encoding="utf-8")

                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("product catalog", result.stderr.lower())

    def test_foundation_verifier_scans_all_non_test_product_source_sets(self) -> None:
        cases = {
            "fully-qualified-android-main": (
                "main",
                "Forbidden.kt",
                "package com.lavasec.app.product\n"
                "internal val forbidden = android.content.Context::class\n",
            ),
            "filesystem-release": (
                "release",
                "Forbidden.kt",
                "package com.lavasec.app.product\n"
                "internal val forbidden = java.io.File(\".\")\n",
            ),
            "kotlin-filesystem-debug": (
                "debug",
                "Forbidden.kt",
                "package com.lavasec.app.product\n"
                "internal val forbidden = kotlin.io.path.Path(\".\")\n",
            ),
            "network-flavor": (
                "internal",
                "Forbidden.java",
                "package com.lavasec.app.product;\n"
                "final class Forbidden { java.net.Socket value; }\n",
            ),
            "production-flavor-beginning-with-test": (
                "testing",
                "Forbidden.kt",
                "package com.lavasec.app.product\n"
                "internal val forbidden = android.os.Build.VERSION.SDK_INT\n",
            ),
        }
        for name, (source_set, filename, declaration) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                source = (
                    fixture
                    / "android"
                    / "product"
                    / "src"
                    / source_set
                    / ("java" if filename.endswith(".java") else "kotlin")
                    / "com"
                    / "lavasec"
                    / "app"
                    / "product"
                    / filename
                )
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text(declaration, encoding="utf-8")

                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("product boundary", result.stderr.lower())

    def test_foundation_verifier_rejects_obscured_product_capability_references(self) -> None:
        cases = {
            "java-whitespace-qualified-filesystem": (
                "java",
                "package com.lavasec.app.product;\n"
                "final class Probe { java . io . File value; }\n",
            ),
            "java-comment-separated-filesystem": (
                "java",
                "package com.lavasec.app.product;\n"
                "final class Probe { java/* trivia */.io.File value; }\n",
            ),
            "java-unicode-qualified-android": (
                "java",
                "package com.lavasec.app.product;\n"
                "final class Probe { and\\u0072oid.content.Context value; }\n",
            ),
            "kotlin-template-android": (
                "kotlin",
                "package com.lavasec.app.product\n"
                'internal val probe = "${android.os.Build.VERSION.SDK_INT}"\n',
            ),
            "kotlin-raw-template-network": (
                "kotlin",
                "package com.lavasec.app.product\n"
                'internal val probe = """${javax.net.SocketFactory.getDefault()}"""\n',
            ),
            "kotlin-local-shadow-does-not-hide-another-scope": (
                "kotlin",
                "package com.lavasec.app.product\n"
                "private data class Thing(val content: Int)\n"
                "private fun harmless() {\n"
                "    val android = Thing(1)\n"
                "    println(android.content)\n"
                "}\n"
                "internal val probe = android.os.Build.VERSION.SDK_INT\n",
            ),
            "kotlin-declaration-does-not-shadow-its-initializer": (
                "kotlin",
                "package com.lavasec.app.product\n"
                "private val android = android.os.Build.VERSION.SDK_INT\n",
            ),
            "javax-network": (
                "kotlin",
                "package com.lavasec.app.product\n"
                "import javax.net.SocketFactory\n"
                'internal fun connect() = SocketFactory.getDefault().createSocket("example.com", 443)\n',
            ),
            "javax-database": (
                "kotlin",
                "package com.lavasec.app.product\n"
                "import javax.sql.DataSource\n"
                "internal interface Store { val source: DataSource }\n",
            ),
            "network-channel": (
                "kotlin",
                "package com.lavasec.app.product\n"
                "import java.nio.channels.SocketChannel\n"
                "internal fun channel() = SocketChannel.open()\n",
            ),
            "filesystem-channel": (
                "kotlin",
                "package com.lavasec.app.product\n"
                "import java.nio.channels.FileChannel\n"
                "internal interface FilePort { val channel: FileChannel }\n",
            ),
        }
        for name, (language, source) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                self.write_product_source(fixture, "main", language, source)

                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("product boundary", result.stderr.lower())

    def test_foundation_verifier_rejects_nonapproved_product_payloads(self) -> None:
        payloads = {
            "android-resource": (
                "res/layout/probe.xml",
                '<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android" />\n',
            ),
            "asset": ("assets/probe.bin", "probe"),
            "native-library": ("jniLibs/arm64-v8a/libprobe.so", "probe"),
            "aidl": (
                "aidl/com/lavasec/app/product/IProbe.aidl",
                "package com.lavasec.app.product; interface IProbe { void ping(); }\n",
            ),
            "jvm-resource": ("resources/probe.txt", "probe"),
            "unexpected-source-type": ("kotlin/Probe.txt", "probe"),
        }
        for name, (relative, content) in payloads.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                payload = fixture / "android" / "product" / "src" / "main" / relative
                payload.parent.mkdir(parents=True, exist_ok=True)
                payload.write_text(content, encoding="utf-8")

                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("product boundary payload", result.stderr.lower())

    def test_foundation_verifier_rejects_unapproved_foundation_api_access(self) -> None:
        cases = {
            "import": (
                "package com.lavasec.app.product\n"
                "import com.lavasec.app.foundation.filter.PreparedFilterArtifact\n"
                "internal interface ArtifactPort { val artifact: PreparedFilterArtifact }\n"
            ),
            "fully-qualified": (
                "package com.lavasec.app.product\n"
                "internal interface ArtifactPort {\n"
                "    val artifact: com.lavasec.app.foundation.filter.PreparedFilterArtifact\n"
                "}\n"
            ),
        }
        for name, source in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                self.write_product_source(fixture, "main", "kotlin", source)

                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("product foundation api", result.stderr.lower())

    def test_foundation_verifier_rejects_product_manifest_components(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            manifest = fixture / "android" / "product" / "src" / "release" / "AndroidManifest.xml"
            manifest.parent.mkdir(parents=True, exist_ok=True)
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                "    <application>\n"
                '        <service android:name=".ForbiddenService" />\n'
                "    </application>\n"
                "</manifest>\n",
                encoding="utf-8",
            )

            result = self.run_verifier(fixture)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("product boundary manifest", result.stderr.lower())

    def test_foundation_verifier_ignores_package_names_in_comments_literals_and_tests(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            main_source = (
                fixture
                / "android"
                / "product"
                / "src"
                / "main"
                / "kotlin"
                / "com"
                / "lavasec"
                / "app"
                / "product"
                / "AllowedText.kt"
            )
            main_source.parent.mkdir(parents=True, exist_ok=True)
            main_source.write_text(
                "package com.lavasec.app.product\n\n"
                "// android.content.Context is documentation, not a dependency.\n"
                'internal const val ALLOWED = "java.io.File is not used here"\n',
                encoding="utf-8",
            )
            test_source = (
                fixture
                / "android"
                / "product"
                / "src"
                / "test"
                / "kotlin"
                / "ForbiddenInProductionOnly.kt"
            )
            test_source.parent.mkdir(parents=True, exist_ok=True)
            test_source.write_text(
                "internal val androidTestFixture = android.content.Context::class\n",
                encoding="utf-8",
            )

            for source_set in ("testDebug", "androidTestRelease", "testFixtures"):
                self.write_product_source(
                    fixture,
                    source_set,
                    "kotlin",
                    "internal val testOnly = android.content.Context::class\n",
                )

            result = self.run_verifier(fixture)

        self.assertEqual(0, result.returncode, result.stderr)

    def test_foundation_verifier_ignores_shadowed_names_and_ineligible_java_unicode(self) -> None:
        cases = {
            "shadowed-kotlin-root": (
                "kotlin",
                "package com.lavasec.app.product\n"
                "private data class Thing(val content: Int)\n"
                "private val android = Thing(1)\n"
                "internal val probe = android.content\n",
            ),
            "escaped-kotlin-template": (
                "kotlin",
                "package com.lavasec.app.product\n"
                'internal val probe = "\\${android.os.Build.VERSION.SDK_INT}"\n',
            ),
            "ineligible-java-unicode-newline": (
                "java",
                "package com.lavasec.app.product;\n"
                "final class Probe {\n"
                "    // harmless \\\\u000a android.content.Context remains comment text\n"
                "}\n",
            ),
        }
        for name, (language, source) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                self.write_product_source(fixture, "main", language, source)

                result = self.run_verifier(fixture)

                self.assertEqual(0, result.returncode, result.stderr)

    def write_product_source(
        self,
        root: Path,
        source_set: str,
        language: str,
        source: str,
    ) -> Path:
        extension = "java" if language == "java" else "kt"
        path = (
            root
            / "android"
            / "product"
            / "src"
            / source_set
            / language
            / "com"
            / "lavasec"
            / "app"
            / "product"
            / f"Probe.{extension}"
        )
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
        return path

    def verifier_fixture(self, directory: str) -> Path:
        fixture = Path(directory)
        shutil.copytree(
            ROOT / "android",
            fixture / "android",
            ignore=shutil.ignore_patterns(".gradle", ".cxx", "build", "node_modules"),
        )
        shutil.copytree(ROOT / "contracts", fixture / "contracts")
        return fixture

    def run_verifier(self, root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                str(ROOT / "scripts" / "verify-android-foundation"),
                "--root",
                str(root),
            ],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )


if __name__ == "__main__":
    unittest.main()
