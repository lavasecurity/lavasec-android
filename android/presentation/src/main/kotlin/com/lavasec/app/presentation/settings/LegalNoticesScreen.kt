package com.lavasec.app.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaNavigationCard
import com.lavasec.app.designsystem.LavaNavigationCardMetrics
import com.lavasec.app.designsystem.LavaSettingsIcons
import com.lavasec.app.designsystem.LavaMetrics
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSection
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.presentation.R

/** One third-party attribution notice, rendered inside a legal section. */
data class LegalNoticeUi(
    val displayName: String,
    val noticeText: String,
    val plannedUse: String,
    val sourceUrl: String?,
    val licenseName: String?,
    val licenseUrl: String?,
)

/** A titled group of notices on the Legal Notices screen. */
data class LegalNoticeSectionUi(
    val title: String,
    val notices: List<LegalNoticeUi>,
)

@Composable
fun LegalNoticesScreen(
    sections: List<LegalNoticeSectionUi>,
    privacyUrl: String,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("legal_notices_screen")
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current),
        verticalArrangement = Arrangement.spacedBy(LavaMetrics.sectionSpacing),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_legal_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        LavaSection(title = stringResource(R.string.legal_protection_limits_title)) {
            LavaPanel {
                Text(
                    text = stringResource(R.string.legal_protection_limits_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LavaSection(
            title = stringResource(R.string.legal_third_party_title),
            contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
        ) {
            LavaPanel {
                Text(
                    text = stringResource(R.string.legal_affiliation_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LavaSection(title = stringResource(R.string.legal_links_title)) {
            LavaNavigationCard(
                title = stringResource(R.string.legal_protection_terms_title),
                summary = stringResource(R.string.legal_open_browser),
                leadingIcon = LavaSettingsIcons.OpenExternal,
                onClick = { onOpenUrl("https://lavasecurity.app/terms/") },
                modifier = Modifier.testTag("legal_protection_terms_link"),
            )
            LavaNavigationCard(
                title = stringResource(R.string.legal_privacy_title),
                summary = stringResource(R.string.legal_open_browser),
                leadingIcon = LavaSettingsIcons.OpenExternal,
                onClick = { onOpenUrl(privacyUrl) },
                modifier = Modifier.testTag("legal_privacy_link"),
            )
        }
        sections.forEach { section ->
            LavaSection(
                title = section.title,
                contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
            ) {
                section.notices.forEach { notice ->
                    LavaPanel {
                        Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                            Text(
                                text = notice.displayName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = notice.noticeText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = notice.plannedUse,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            notice.sourceUrl?.let { source ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable { onOpenUrl(source) }
                                        .testTag("legal_source_link"),
                                ) {
                                    Text(
                                        text = stringResource(R.string.legal_metadata_source, source),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            notice.licenseName?.let { license ->
                                Text(
                                    text = notice.licenseUrl?.let { licenseUrl ->
                                        stringResource(
                                            R.string.legal_metadata_license_url,
                                            license,
                                            licenseUrl,
                                        )
                                    } ?: stringResource(R.string.legal_metadata_license, license),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = notice.licenseUrl?.let { licenseUrl ->
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable { onOpenUrl(licenseUrl) }
                                            .testTag("legal_license_link")
                                    } ?: Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
        LavaSection(
            title = stringResource(R.string.legal_other_marks_title),
            contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
        ) {
            LavaPanel {
                Text(
                    text = stringResource(R.string.legal_other_marks_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
