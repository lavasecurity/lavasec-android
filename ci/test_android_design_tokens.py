import hashlib
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = ROOT / "contracts" / "design-tokens" / "lava-design-tokens.json"
RESOLUTION_PATH = ROOT / "contracts" / "design-tokens" / "android-resolution.json"
DESIGN_SYSTEM = (
    ROOT / "android" / "design-system" / "src" / "main" / "kotlin" /
    "com" / "lavasec" / "app" / "designsystem"
)
TOKENS_PATH = DESIGN_SYSTEM / "LavaTokens.kt"
THEME_PATH = DESIGN_SYSTEM / "LavaTheme.kt"
ICONS_PATH = DESIGN_SYSTEM / "LavaIcons.kt"
COMPONENTS_PATH = DESIGN_SYSTEM / "LavaComponents.kt"
PINNED_IOS_SHA256 = "fdd5ea879f372b49342b86bbb53f116305d087a88046f055bf2c33df6fc015eb"
RADII_TARGETS = {
    "cardCornerRadius": "card",
    "compactCornerRadius": "compact",
    "selectionCornerRadius": "selection",
    "controlCornerRadius": "control",
    "pillCornerRadius": "pill",
    "iconBadgeCornerRadius": "iconBadge",
}


def contract_leaves(value: object, path: tuple[str, ...] = ()) -> dict[str, object]:
    if isinstance(value, dict):
        leaves: dict[str, object] = {}
        for key, child in value.items():
            if key.startswith("_"):
                continue
            leaves.update(contract_leaves(child, (*path, key)))
        return leaves
    return {".".join(path): value}


def object_body(source: str, name: str) -> str:
    match = re.search(rf"\bobject\s+{re.escape(name)}\s*\{{", source)
    if match is None:
        raise AssertionError(f"missing Kotlin object {name}")
    opening = source.find("{", match.start())
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
    raise AssertionError(f"unterminated Kotlin object {name}")


def kotlin_number(value: float) -> str:
    return str(int(value)) if value.is_integer() else str(value)


def portable_metric_drift(contract: dict[str, object], source: str) -> list[str]:
    findings: list[str] = []
    mappings = {
        "LavaSpacing": {
            "xs": contract["spacing"]["xs"],
            "sm": contract["spacing"]["sm"],
            "md": contract["spacing"]["md"],
            "lg": contract["spacing"]["lg"],
            "xl": contract["spacing"]["xl"],
            "screenHorizontal": contract["spacing"]["screenHorizontal"],
            "screenTop": contract["spacing"]["screenTop"],
            "screenBottom": contract["spacing"]["screenBottom"],
        },
        "LavaRadii": {
            "card": contract["radii"]["cardCornerRadius"],
            "compact": contract["radii"]["compactCornerRadius"],
            "selection": contract["radii"]["selectionCornerRadius"],
            "control": contract["radii"]["controlCornerRadius"],
            "pill": contract["radii"]["pillCornerRadius"],
            "iconBadge": contract["radii"]["iconBadgeCornerRadius"],
        },
        "LavaIconSizes": {
            key: value for key, value in contract["iconSizes"].items()
            if not key.startswith("_")
        },
    }
    for object_name, expected_values in mappings.items():
        body = object_body(source, object_name)
        for property_name, value in expected_values.items():
            pattern = rf"\bval\s+{re.escape(property_name)}\s*=\s*{kotlin_number(value)}\.dp\b"
            if re.search(pattern, body) is None:
                findings.append(f"{object_name}.{property_name} != {value}dp")
    return findings


def argb_literal(rgb: list[float]) -> str:
    channels = [int(component * 255 + 0.5) for component in rgb]
    return "0xFF" + "".join(f"{channel:02X}" for channel in channels)


def resolved_color_drift(
    contract: dict[str, object],
    resolution: dict[str, object],
    source: str,
) -> list[str]:
    findings: list[str] = []
    for role, variants in contract["colors"].items():
        for mode, rgb in variants.items():
            path = f"colors.{role}.{mode}"
            target = resolution["contractLeafMappings"][path]["android"]
            symbol = target.removeprefix("LavaColors.")
            expected = argb_literal(rgb)
            pattern = rf"\bval\s+{re.escape(symbol)}\s*=\s*Color\({expected}\)"
            if not target.startswith("LavaColors.") or re.search(pattern, source) is None:
                findings.append(f"{path} -> {target} != {expected}")
    return findings


def tier_drift(contract: dict[str, object], source: str) -> list[str]:
    findings: list[str] = []
    accent_roles = {"safeGreen": "SAFE", "lavaOrange": "ATTENTION", "ink": "INK"}
    for tier_name, tier in contract["tiers"].items():
        if tier_name.startswith("_"):
            continue
        expected = (
            rf"\b{tier_name.upper()}\(LavaAccentRole\.{accent_roles[tier['accent']]},\s*"
            rf"{str(tier['allowsDelightMotion']).lower()},\s*"
            rf"{str(tier['usesMonospacedMetadata']).lower()}\)"
        )
        if re.search(expected, source) is None:
            findings.append(f"tiers.{tier_name} drifted")
    return findings


def expected_android_target(path: str) -> str:
    parts = path.split(".")
    family = parts[0]
    if family == "colors":
        role, mode = parts[1:]
        symbol = {"dangerRed": "Danger"}.get(role, role[0].upper() + role[1:])
        return f"LavaColors.{symbol}{'Dark' if mode == 'dark' else ''}"
    if family == "systemColors":
        role, platform = parts[1:]
        if platform == "ios":
            return "Pinned iOS source semantic"
        return {
            "primaryText": "MaterialTheme.colorScheme.onSurface",
            "secondaryText": "MaterialTheme.colorScheme.onSurfaceVariant",
            "tertiaryText": "MaterialTheme.colorScheme.onSurfaceVariant",
        }[role]
    if family == "spacing":
        return f"LavaSpacing.{parts[1]}"
    if family == "radii":
        return f"LavaRadii.{RADII_TARGETS[parts[1]]}"
    if family == "iconSizes":
        return f"LavaIconSizes.{parts[1]}"
    if family == "typography":
        return "Pinned iOS source typography" if parts[-1] == "ios" else "LavaTypography.metricNumeral"
    if family == "tiers":
        return f"LavaTier.{parts[1].upper()}.{parts[2]}"
    raise AssertionError(f"unhandled canonical path: {path}")


def resolution_target_drift(resolution: dict[str, object]) -> list[str]:
    return [
        f"{path} -> {mapping['android']}"
        for path, mapping in resolution["contractLeafMappings"].items()
        if mapping["android"] != expected_android_target(path)
    ]


class AndroidDesignTokensTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contract_bytes = CONTRACT_PATH.read_bytes()
        self.contract = json.loads(self.contract_bytes)
        self.tokens = TOKENS_PATH.read_text(encoding="utf-8")

    def test_contract_is_the_exact_approved_ios_artifact(self) -> None:
        self.assertEqual(PINNED_IOS_SHA256, hashlib.sha256(self.contract_bytes).hexdigest())

    def test_android_resolution_is_separate_and_covers_every_canonical_leaf(self) -> None:
        resolution = json.loads(RESOLUTION_PATH.read_text(encoding="utf-8"))
        self.assertEqual(PINNED_IOS_SHA256, resolution["canonicalSha256"])
        expected = contract_leaves(self.contract)
        actual = resolution["contractLeafMappings"]
        self.assertEqual(set(expected), set(actual))
        for path, source_value in expected.items():
            with self.subTest(path=path):
                self.assertEqual(source_value, actual[path]["sourceValue"])
                self.assertIn(actual[path]["status"], {"native", "resolved", "source-only"})
                self.assertTrue(actual[path]["android"])

    def test_resolution_documents_android_only_choices_and_deviations(self) -> None:
        resolution = json.loads(RESOLUTION_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            {"dynamicColor", "errorRole", "motion", "semanticIcons", "shapeFamily", "surfaceFamily", "typographyFallback"},
            set(resolution["androidOnly"]),
        )
        self.assertEqual(
            {"lavaOrangeText", "lavaOrangeSelectedFill", "navigationBadgeContainer"},
            set(resolution["iosSourceAdditions"]),
        )
        self.assertTrue(all(item["reason"] for item in resolution["approvedDeviations"]))

    def test_portable_metrics_match_kotlin(self) -> None:
        self.assertEqual([], portable_metric_drift(self.contract, self.tokens))

    def test_canonical_colors_and_tiers_resolve_to_real_kotlin_declarations(self) -> None:
        resolution = json.loads(RESOLUTION_PATH.read_text(encoding="utf-8"))
        self.assertEqual([], resolved_color_drift(self.contract, resolution, self.tokens))
        self.assertEqual([], tier_drift(self.contract, self.tokens))
        for path, mapping in resolution["contractLeafMappings"].items():
            target = mapping["android"]
            if target.startswith("LavaColors."):
                self.assertRegex(self.tokens, rf"\bval\s+{re.escape(target.split('.')[-1])}\b", path)
            elif target.startswith("LavaTier."):
                tier = target.split(".")[1]
                self.assertRegex(self.tokens, rf"\b{re.escape(tier)}\(", path)

    def test_every_resolution_target_is_an_approved_native_or_kotlin_symbol(self) -> None:
        resolution = json.loads(RESOLUTION_PATH.read_text(encoding="utf-8"))
        self.assertEqual([], resolution_target_drift(resolution))
        mutated = json.loads(json.dumps(resolution))
        mutated["contractLeafMappings"]["spacing.xs"]["android"] = "LavaSpacing.doesNotExist"
        self.assertEqual(
            ["spacing.xs -> LavaSpacing.doesNotExist"],
            resolution_target_drift(mutated),
        )

    def test_ios_source_addition_for_navigation_badge_matches_kotlin(self) -> None:
        resolution = json.loads(RESOLUTION_PATH.read_text(encoding="utf-8"))
        addition = resolution["iosSourceAdditions"]["navigationBadgeContainer"]
        self.assertEqual(38, addition["sourceValue"])
        self.assertEqual("LavaNavigationCardMetrics.badgeSize", addition["android"])
        self.assertRegex(self.tokens, r"\bval\s+badgeSize\s*=\s*38\.dp\b")

    def test_fixed_typography_resolution_matches_actual_kotlin(self) -> None:
        body = object_body(self.tokens, "LavaTypography")
        for expected in (
            r'Font\(DeviceFontFamilyName\("sans-serif-rounded"\), FontWeight\.Bold\)',
            r'Font\(DeviceFontFamilyName\("sans-serif"\), FontWeight\.Bold\)',
            r"fontSize\s*=\s*42\.sp",
            r"fontWeight\s*=\s*FontWeight\.Bold",
            r'fontFeatureSettings\s*=\s*"tnum"',
        ):
            self.assertRegex(body, expected)

    def test_each_metric_class_reports_path_specific_drift(self) -> None:
        mutations = {
            "LavaSpacing.screenHorizontal != 18.0dp": ("val screenHorizontal = 18.dp", "val screenHorizontal = 17.dp"),
            "LavaRadii.card != 20.0dp": ("val card = 20.dp", "val card = 19.dp"),
            "LavaIconSizes.hero != 44.0dp": ("val hero = 44.dp", "val hero = 43.dp"),
        }
        for finding, (before, after) in mutations.items():
            with self.subTest(finding=finding):
                self.assertIn(finding, portable_metric_drift(self.contract, self.tokens.replace(before, after)))

    def test_color_and_tier_mutations_report_path_specific_drift(self) -> None:
        resolution = json.loads(RESOLUTION_PATH.read_text(encoding="utf-8"))
        color_drift = resolved_color_drift(
            self.contract,
            resolution,
            self.tokens.replace("val SafeGreen = Color(0xFF297857)", "val SafeGreen = Color(0xFF297858)"),
        )
        self.assertIn("colors.safeGreen.light -> LavaColors.SafeGreen != 0xFF297857", color_drift)
        self.assertIn(
            "tiers.calm drifted",
            tier_drift(self.contract, self.tokens.replace("CALM(LavaAccentRole.SAFE, false, false)", "CALM(LavaAccentRole.SAFE, true, false)")),
        )

    def test_theme_defaults_to_static_native_color_and_exposes_semantic_roles(self) -> None:
        theme = THEME_PATH.read_text(encoding="utf-8")
        self.assertIn("useDynamicColor: Boolean = false", theme)
        self.assertIn("LocalLavaSemanticColors", theme)
        self.assertIn("attentionSelectedFill", theme)
        self.assertIn("panelBackground", theme)
        self.assertIn("panelStroke", theme)

    def test_tertiary_text_uses_a_contrast_safe_native_material_role(self) -> None:
        resolution = json.loads(RESOLUTION_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            "MaterialTheme.colorScheme.onSurfaceVariant",
            resolution["contractLeafMappings"]["systemColors.tertiaryText.android"]["android"],
        )

    def test_design_system_owns_all_android_semantic_icon_roles(self) -> None:
        icons = ICONS_PATH.read_text(encoding="utf-8")
        for role in (
            "Guard", "Filters", "Activity", "Settings", "Tune", "DomainHistory",
            "NetworkActivity", "Blocked", "Allowed", "Forward",
        ):
            self.assertRegex(icons, rf"\bval\s+{role}\s*:\s*ImageVector")
        self.assertNotEqual(
            re.search(r'name = "(LavaSettings)"', icons).group(1),
            re.search(r'name = "(LavaTune)"', icons).group(1),
        )

    def test_panel_and_navigation_components_use_semantic_tokens(self) -> None:
        components = COMPONENTS_PATH.read_text(encoding="utf-8")
        self.assertIn("val semanticColors = LocalLavaSemanticColors.current", components)
        self.assertIn("containerColor = semanticColors.panelBackground", components)
        self.assertIn("BorderStroke(1.dp, semanticColors.panelStroke)", components)
        self.assertIn("LavaNavigationCardMetrics.badgeSize", components)
        self.assertIn(".background(badgeColor, RoundedCornerShape(LavaRadii.iconBadge))", components)
        self.assertIn("onClick = onClick", components)
        self.assertIn("enabled = enabled", components)
        self.assertIn("disabledContentColor = colors.onSurface.copy(alpha = 0.38f)", components)
        self.assertIn('testTag("lava_navigation_leading_icon")', components)


if __name__ == "__main__":
    unittest.main()
