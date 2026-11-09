import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RES_ROOTS = (
    ROOT / "android" / "app" / "src" / "main" / "res",
    ROOT / "android" / "presentation" / "src" / "main" / "res",
    ROOT / "android" / "vpn" / "src" / "main" / "res",
)
LOCALES = ("de", "fr", "it", "es", "ja", "ko", "pt-rBR", "zh-rCN", "zh-rTW")
# Brand and product-token strings are intentionally identical across locales.
UNTRANSLATED_ALLOWLIST = {
    "app_name",
    "onboarding_level_core",
    "onboarding_level_balanced",
    "onboarding_level_extra",
    "legal_links_title",
    "resolver_mullvad_name",
    "settings_nerd_stats_title",
    "nerd_stats_android_platform",
    "nerd_stats_latency_ms",
    # The Guard product label is the brand token (iOS "Lava Security" subheadline).
    "guard_product_label",
    # The Lava Guard brand token (identical in every pinned iOS locale).
    "customization_guardian_section",
    # Pure format skeleton for the composed status value ("%1$s. %2$s").
    "guard_status_accessibility_value",
    # Third-party resolver service names shown in Network Activity rows.
    "network_activity_resolver_cloudflare",
    "network_activity_resolver_quad9",
    "network_activity_resolver_hagezi",
    "network_activity_resolver_google",
    # The captured-state line is a pure format string around the Lava brand token.
    "network_activity_state_line",
}
# Strings whose correct translation happens to be identical to the English
# text in specific locales (e.g. French "minute", German "Version").
COINCIDENCE_ALLOWLIST = {
    "activity_uptime_minutes": {"fr"},
    "version_format": {"de", "fr"},
    "version_build_format": {"de", "fr"},
    # The ambient notification summary labels are loanwords in German
    # ("Filter:" / "Resolver:") — the word legitimately coincides with English.
    "protection_ambient_filter_line": {"de"},
    "protection_ambient_resolver_line": {"de"},
}


def _strip_specifiers(text: str) -> str:
    return re.sub(r"%\d+\$[sdl]", "", text)


def load_file(path: Path) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    root = ET.parse(path).getroot()
    strings: dict[str, str] = {}
    plurals: dict[str, dict[str, str]] = {}
    for element in root:
        if element.tag == "string":
            strings[element.attrib["name"]] = element.text or ""
        elif element.tag == "plurals":
            plurals[element.attrib["name"]] = {
                item.attrib["quantity"]: item.text or ""
                for item in element.findall("item")
            }
    return strings, plurals


class AndroidLocalizationTest(unittest.TestCase):
    def test_every_locale_covers_every_module(self) -> None:
        for res_root in RES_ROOTS:
            base_strings, base_plurals = load_file(res_root / "values" / "strings.xml")
            self.assertTrue(base_strings or base_plurals)
            for locale in LOCALES:
                with self.subTest(module=res_root.parent.name, locale=locale):
                    path = res_root / f"values-{locale}" / "strings.xml"
                    self.assertTrue(path.is_file(), f"missing locale file: {path}")
                    strings, plurals = load_file(path)
                    self.assertEqual(
                        set(base_strings),
                        set(strings),
                        f"string-key drift for {locale} in {path}",
                    )
                    self.assertEqual(
                        set(base_plurals),
                        set(plurals),
                        f"plural-key drift for {locale} in {path}",
                    )
                    for name, quantities in plurals.items():
                        self.assertIn(
                            "other",
                            quantities,
                            f"{name} is missing quantity=other for {locale}",
                        )

    def test_no_english_leakage(self) -> None:
        for res_root in RES_ROOTS:
            base_strings, base_plurals = load_file(res_root / "values" / "strings.xml")
            for locale in LOCALES:
                strings, plurals = load_file(
                    res_root / f"values-{locale}" / "strings.xml",
                )
                for name, english in base_strings.items():
                    with self.subTest(locale=locale, key=name):
                        if name in UNTRANSLATED_ALLOWLIST:
                            continue
                        if locale in COINCIDENCE_ALLOWLIST.get(name, ()):
                            continue
                        # Single-word labels may legitimately coincide across
                        # languages (e.g. "Start", "Support", "Version" in
                        # German); multi-word English leakage is the real risk.
                        if " " not in english.strip():
                            continue
                        if not re.search(r"[A-Za-z]", _strip_specifiers(english)):
                            continue
                        translated = strings[name]
                        self.assertNotEqual(
                            translated,
                            english,
                            f"{name} is untranslated for {locale}",
                        )
                        self.assertTrue(translated.strip())
                for name, english_quantities in base_plurals.items():
                    with self.subTest(locale=locale, key=name):
                        if locale in COINCIDENCE_ALLOWLIST.get(name, ()):
                            continue
                        for quantity, english in english_quantities.items():
                            if quantity not in plurals[name]:
                                continue
                            if " " not in english.strip():
                                continue
                            if not re.search(
                                r"[A-Za-z]",
                                _strip_specifiers(english),
                            ):
                                continue
                            translated = plurals[name][quantity]
                            self.assertNotEqual(
                                translated,
                                english,
                                f"{name}[{quantity}] is untranslated for {locale}",
                            )
                            self.assertTrue(translated.strip())

    def test_cjk_locales_use_single_quantity(self) -> None:
        for res_root in RES_ROOTS:
            for locale in ("ja", "ko", "zh-rCN", "zh-rTW"):
                path = res_root / f"values-{locale}" / "strings.xml"
                _, plurals = load_file(path)
                for name, quantities in plurals.items():
                    self.assertEqual(
                        {"other"},
                        set(quantities),
                        f"{name} has extra quantities in {path}",
                    )

    def test_non_cjk_locales_declare_real_quantities(self) -> None:
        romance = {"es", "fr", "it", "pt-rBR"}
        for res_root in RES_ROOTS:
            for locale in set(LOCALES) - {"ja", "ko", "zh-rCN", "zh-rTW"}:
                path = res_root / f"values-{locale}" / "strings.xml"
                _, plurals = load_file(path)
                expected = {"one", "many", "other"} if locale in romance else {"one", "other"}
                for name, quantities in plurals.items():
                    self.assertEqual(
                        expected,
                        set(quantities),
                        f"{name} has {sorted(quantities)} quantities in {path}",
                    )


if __name__ == "__main__":
    unittest.main()
