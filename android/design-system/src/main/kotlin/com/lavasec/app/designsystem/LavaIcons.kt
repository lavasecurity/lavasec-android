package com.lavasec.app.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object LavaIcons {
    /** Material arrow-back geometry, auto-mirrored for RTL navigation. */
    val Back: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaBack",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 11f)
                horizontalLineTo(7.83f)
                lineTo(13.42f, 5.41f)
                lineTo(12f, 4f)
                lineTo(4f, 12f)
                lineTo(12f, 20f)
                lineTo(13.41f, 18.59f)
                lineTo(7.83f, 13f)
                horizontalLineTo(20f)
                close()
            }
        }.build()
    }

    /** Material chevron-forward geometry, auto-mirrored for RTL navigation. */
    val Forward: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaForward",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(9.29f, 6.71f)
                lineTo(13.58f, 11f)
                lineTo(9.29f, 15.29f)
                lineTo(10.71f, 16.71f)
                lineTo(16.42f, 11f)
                lineTo(10.71f, 5.29f)
                close()
            }
        }.build()
    }

    /** Material more-vert geometry for row action menus. */
    val MoreVert: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaMoreVert",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 8f)
                curveTo(13.1f, 8f, 14f, 7.1f, 14f, 6f)
                curveTo(14f, 4.9f, 13.1f, 4f, 12f, 4f)
                curveTo(10.9f, 4f, 10f, 4.9f, 10f, 6f)
                curveTo(10f, 7.1f, 10.9f, 8f, 12f, 8f)
                close()
                moveTo(12f, 10f)
                curveTo(10.9f, 10f, 10f, 10.9f, 10f, 12f)
                curveTo(10f, 13.1f, 10.9f, 14f, 12f, 14f)
                curveTo(13.1f, 14f, 14f, 13.1f, 14f, 12f)
                curveTo(14f, 10.9f, 13.1f, 10f, 12f, 10f)
                close()
                moveTo(12f, 16f)
                curveTo(10.9f, 16f, 10f, 16.9f, 10f, 18f)
                curveTo(10f, 19.1f, 10.9f, 20f, 12f, 20f)
                curveTo(13.1f, 20f, 14f, 19.1f, 14f, 18f)
                curveTo(14f, 16.9f, 13.1f, 16f, 12f, 16f)
                close()
            }
        }.build()
    }

    /** Material check geometry for decorative completion affordances. */
    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaCheck",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 16.17f)
                lineTo(4.83f, 12f)
                lineTo(3.41f, 13.41f)
                lineTo(9f, 19f)
                lineTo(21f, 7f)
                lineTo(19.59f, 5.59f)
                close()
            }
        }.build()
    }

    /** Material block geometry, mapping iOS hand.raised.fill to the native Android idiom. */
    val Blocked: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaBlock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(5f, 12f)
                curveTo(5f, 10.35f, 5.54f, 8.82f, 6.46f, 7.59f)
                lineTo(16.41f, 17.54f)
                curveTo(15.18f, 18.46f, 13.65f, 19f, 12f, 19f)
                curveTo(8.13f, 19f, 5f, 15.87f, 5f, 12f)
                close()
                moveTo(7.59f, 6.46f)
                curveTo(8.82f, 5.54f, 10.35f, 5f, 12f, 5f)
                curveTo(15.87f, 5f, 19f, 8.13f, 19f, 12f)
                curveTo(19f, 13.65f, 18.46f, 15.18f, 17.54f, 16.41f)
                close()
            }
        }.build()
    }

    /** Compatibility name for existing call sites; semantic consumers should use [Blocked]. */
    val Block: ImageVector get() = Blocked

    /** Material filled/outlined status circles used as decorative state accents. */
    val StatusOn: ImageVector by lazy { statusCircle(name = "LavaStatusOn", filled = true) }
    val StatusOff: ImageVector by lazy { statusCircle(name = "LavaStatusOff", filled = false) }

    private fun statusCircle(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = if (filled) PathFillType.NonZero else PathFillType.EvenOdd,
            ) {
                if (filled) {
                    moveTo(12f, 2f)
                    curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                    curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                    curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                    curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                    close()
                } else {
                    moveTo(12f, 2f)
                    curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                    curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                    curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                    curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                    close()
                    moveTo(12f, 4f)
                    curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
                    curveTo(20f, 16.42f, 16.42f, 20f, 12f, 20f)
                    curveTo(7.58f, 20f, 4f, 16.42f, 4f, 12f)
                    curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
                    close()
                }
            }
        }.build()

    val Guard: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaGuard",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(4f, 5.5f)
                verticalLineTo(11f)
                curveTo(4f, 16.2f, 7.4f, 20.6f, 12f, 22f)
                curveTo(16.6f, 20.6f, 20f, 16.2f, 20f, 11f)
                verticalLineTo(5.5f)
                close()
                moveTo(12f, 5f)
                lineTo(17f, 7.2f)
                verticalLineTo(11f)
                curveTo(17f, 14.5f, 15f, 17.5f, 12f, 18.8f)
                curveTo(9f, 17.5f, 7f, 14.5f, 7f, 11f)
                verticalLineTo(7.2f)
                close()
            }
        }.build()
    }

    /** Material tune/sliders role used by onboarding controls, not application settings. */
    val Tune: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaTune",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 6f)
                horizontalLineTo(11f)
                verticalLineTo(4f)
                horizontalLineTo(14f)
                verticalLineTo(6f)
                horizontalLineTo(20f)
                verticalLineTo(8f)
                horizontalLineTo(14f)
                verticalLineTo(10f)
                horizontalLineTo(11f)
                verticalLineTo(8f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 11f)
                horizontalLineTo(7f)
                verticalLineTo(9f)
                horizontalLineTo(10f)
                verticalLineTo(11f)
                horizontalLineTo(20f)
                verticalLineTo(13f)
                horizontalLineTo(10f)
                verticalLineTo(15f)
                horizontalLineTo(7f)
                verticalLineTo(13f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 16f)
                horizontalLineTo(13f)
                verticalLineTo(14f)
                horizontalLineTo(16f)
                verticalLineTo(16f)
                horizontalLineTo(20f)
                verticalLineTo(18f)
                horizontalLineTo(16f)
                verticalLineTo(20f)
                horizontalLineTo(13f)
                verticalLineTo(18f)
                horizontalLineTo(4f)
                close()
            }
        }.build()
    }

    /** Material gear role used only for Settings destinations. */
    val Settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaSettings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(19.43f, 12.98f)
                curveTo(19.47f, 12.66f, 19.5f, 12.34f, 19.5f, 12f)
                curveTo(19.5f, 11.66f, 19.47f, 11.34f, 19.42f, 11.02f)
                lineTo(21.54f, 9.37f)
                lineTo(19.54f, 5.91f)
                lineTo(17.05f, 6.91f)
                curveTo(16.54f, 6.5f, 15.98f, 6.17f, 15.35f, 5.93f)
                lineTo(15f, 3.27f)
                horizontalLineTo(11f)
                lineTo(10.65f, 5.93f)
                curveTo(10.02f, 6.17f, 9.45f, 6.5f, 8.95f, 6.91f)
                lineTo(6.46f, 5.91f)
                lineTo(4.46f, 9.37f)
                lineTo(6.57f, 11.02f)
                curveTo(6.53f, 11.34f, 6.5f, 11.67f, 6.5f, 12f)
                curveTo(6.5f, 12.33f, 6.53f, 12.66f, 6.58f, 12.98f)
                lineTo(4.46f, 14.63f)
                lineTo(6.46f, 18.09f)
                lineTo(8.95f, 17.09f)
                curveTo(9.46f, 17.5f, 10.02f, 17.83f, 10.65f, 18.07f)
                lineTo(11f, 20.73f)
                horizontalLineTo(15f)
                lineTo(15.35f, 18.07f)
                curveTo(15.98f, 17.83f, 16.55f, 17.5f, 17.05f, 17.09f)
                lineTo(19.54f, 18.09f)
                lineTo(21.54f, 14.63f)
                close()
                moveTo(13f, 15.5f)
                curveTo(11.07f, 15.5f, 9.5f, 13.93f, 9.5f, 12f)
                curveTo(9.5f, 10.07f, 11.07f, 8.5f, 13f, 8.5f)
                curveTo(14.93f, 8.5f, 16.5f, 10.07f, 16.5f, 12f)
                curveTo(16.5f, 13.93f, 14.93f, 15.5f, 13f, 15.5f)
                close()
            }
        }.build()
    }

    val Filters: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaFilters",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 6f)
                horizontalLineTo(14f)
                curveTo(14.45f, 4.84f, 15.58f, 4f, 17f, 4f)
                curveTo(18.42f, 4f, 19.55f, 4.84f, 20f, 6f)
                horizontalLineTo(21f)
                verticalLineTo(8f)
                horizontalLineTo(20f)
                curveTo(19.55f, 9.16f, 18.42f, 10f, 17f, 10f)
                curveTo(15.58f, 10f, 14.45f, 9.16f, 14f, 8f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 11f)
                horizontalLineTo(7f)
                curveTo(7.45f, 9.84f, 8.58f, 9f, 10f, 9f)
                curveTo(11.42f, 9f, 12.55f, 9.84f, 13f, 11f)
                horizontalLineTo(21f)
                verticalLineTo(13f)
                horizontalLineTo(13f)
                curveTo(12.55f, 14.16f, 11.42f, 15f, 10f, 15f)
                curveTo(8.58f, 15f, 7.45f, 14.16f, 7f, 13f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 16f)
                horizontalLineTo(14f)
                curveTo(14.45f, 14.84f, 15.58f, 14f, 17f, 14f)
                curveTo(18.42f, 14f, 19.55f, 14.84f, 20f, 16f)
                horizontalLineTo(21f)
                verticalLineTo(18f)
                horizontalLineTo(20f)
                curveTo(19.55f, 19.16f, 18.42f, 20f, 17f, 20f)
                curveTo(15.58f, 20f, 14.45f, 19.16f, 14f, 18f)
                horizontalLineTo(4f)
                close()
            }
        }.build()
    }

    val Activity: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaActivity",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 20f)
                verticalLineTo(11f)
                horizontalLineTo(8f)
                verticalLineTo(20f)
                close()
                moveTo(10f, 20f)
                verticalLineTo(4f)
                horizontalLineTo(14f)
                verticalLineTo(20f)
                close()
                moveTo(16f, 20f)
                verticalLineTo(8f)
                horizontalLineTo(20f)
                verticalLineTo(20f)
                close()
            }
        }.build()
    }

    val DomainHistory: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaDomainHistory",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(12f, 4f)
                curveTo(7.58f, 4f, 4f, 7.58f, 4f, 12f)
                horizontalLineTo(1f)
                lineTo(5f, 16f)
                lineTo(9f, 12f)
                horizontalLineTo(6f)
                curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
                curveTo(15.31f, 6f, 18f, 8.69f, 18f, 12f)
                curveTo(18f, 15.31f, 15.31f, 18f, 12f, 18f)
                curveTo(10.29f, 18f, 8.75f, 17.28f, 7.66f, 16.12f)
                lineTo(6.24f, 17.54f)
                curveTo(7.7f, 19.06f, 9.74f, 20f, 12f, 20f)
                curveTo(16.42f, 20f, 20f, 16.42f, 20f, 12f)
                curveTo(20f, 7.58f, 16.42f, 4f, 12f, 4f)
                close()
                moveTo(11f, 8f)
                horizontalLineTo(13f)
                verticalLineTo(12.5f)
                lineTo(16f, 14.25f)
                lineTo(15f, 15.98f)
                lineTo(11f, 13.67f)
                close()
            }
        }.build()
    }

    val NetworkActivity: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaNetworkActivity",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 12f)
                horizontalLineTo(6f)
                lineTo(8f, 7f)
                lineTo(11f, 17f)
                lineTo(14f, 9f)
                lineTo(16f, 12f)
                horizontalLineTo(21f)
                verticalLineTo(14f)
                horizontalLineTo(15f)
                lineTo(14.5f, 13.25f)
                lineTo(10.8f, 21f)
                lineTo(7.7f, 11f)
                lineTo(7.3f, 12f)
                horizontalLineTo(3f)
                close()
            }
        }.build()
    }

    /** Material pause-circle role for locally allowed requests observed while protection paused. */
    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaPause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(8.75f, 7.5f)
                horizontalLineTo(11f)
                verticalLineTo(16.5f)
                horizontalLineTo(8.75f)
                close()
                moveTo(13f, 7.5f)
                horizontalLineTo(15.25f)
                verticalLineTo(16.5f)
                horizontalLineTo(13f)
                close()
            }
        }.build()
    }

    val Allowed: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaAllowed",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(8f, 11f)
                horizontalLineTo(13.17f)
                lineTo(11f, 8.83f)
                lineTo(12.41f, 7.41f)
                lineTo(17f, 12f)
                lineTo(12.41f, 16.59f)
                lineTo(11f, 15.17f)
                lineTo(13.17f, 13f)
                horizontalLineTo(8f)
                close()
            }
        }.build()
    }
    /**
     * The slice-03 filter-editing glyph set. Each maps one iOS SF Symbol used by the filter
     * surfaces to its Material geometry (FilterLibraryView/FilterMyListView/FilterDomainSheets):
     * a phone (the overview diagram's first hop), a plain forward arrow (diagram hops), a
     * plus/minus pair (add rows and the review diff semantics), an edit pencil, a delete
     * (minus.circle.fill equivalent), an undo (arrow.uturn.backward / counterclockwise), a
     * play glyph for the in-effect marker, and a close/chevron pair for sheets and pickers.
     */
    private fun buildFilterIcon(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    val Phone: ImageVector by lazy {
        buildFilterIcon("LavaPhone") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 1.01f)
                lineTo(7f, 1f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(18f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(10f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(19f, 3f)
                curveToRelative(0f, -1.1f, -0.9f, -1.99f, -2f, -1.99f)
                close()
                moveTo(17f, 19f)
                lineTo(7f, 19f)
                lineTo(7f, 5f)
                horizontalLineToRelative(10f)
                close()
            }
        }
    }

    /** Material arrow-forward geometry — the diagram hops (auto-mirrored for RTL). */
    val ArrowRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "LavaArrowRight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(16.17f, 11f)
                lineTo(4f, 11f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(12.17f)
                lineToRelative(-5.58f, 5.59f)
                lineTo(12f, 20f)
                lineToRelative(8f, -8f)
                close()
            }
        }.build()
    }

    /** Material close geometry for sheet/dialog dismissal. */
    val XMark: ImageVector by lazy {
        buildFilterIcon("LavaXMark") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18.3f, 5.71f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
                lineTo(12f, 10.59f)
                lineTo(7.11f, 5.7f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0f, 1.41f)
                lineTo(10.59f, 12f)
                lineTo(5.7f, 16.89f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0f, 1.41f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0f)
                lineTo(12f, 13.41f)
                lineToRelative(4.89f, 4.89f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
                lineTo(13.41f, 12f)
                lineToRelative(4.89f, -4.89f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
                close()
            }
        }
    }

    /** Material add geometry — add rows and the review diff's plus semantics. */
    val Plus: ImageVector by lazy {
        buildFilterIcon("LavaPlus") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 13f)
                horizontalLineToRelative(-6f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-6f)
                lineTo(5f, 13f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(6f)
                lineTo(11f, 5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(6f)
                close()
            }
        }
    }

    /** Material remove geometry — the review diff's minus semantics. */
    val Minus: ImageVector by lazy {
        buildFilterIcon("LavaMinus") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 13f)
                lineTo(5f, 13f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(14f)
                close()
            }
        }
    }

    /** Material edit-pencil geometry for rename affordances. */
    val Pencil: ImageVector by lazy {
        buildFilterIcon("LavaPencil") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 17.25f)
                lineTo(3f, 21f)
                horizontalLineToRelative(3.75f)
                lineTo(17.81f, 9.94f)
                lineToRelative(-3.75f, -3.75f)
                close()
                moveTo(20.71f, 7.04f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
                lineToRelative(-2.34f, -2.34f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
                lineToRelative(-1.83f, 1.83f)
                lineToRelative(3.75f, 3.75f)
                close()
            }
        }
    }

    /** Material delete geometry — staged library deletion rows. */
    val Trash: ImageVector by lazy {
        buildFilterIcon("LavaTrash") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 19f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(8f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(18f, 7f)
                lineTo(6f, 7f)
                close()
                moveTo(19f, 4f)
                horizontalLineToRelative(-3.5f)
                lineToRelative(-1f, -1f)
                horizontalLineToRelative(-5f)
                lineToRelative(-1f, 1f)
                lineTo(5f, 4f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(14f)
                close()
            }
        }
    }

    /** Material counterclockwise geometry — undo staging and restore defaults. */
    val Undo: ImageVector by lazy {
        buildFilterIcon("LavaUndo") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12.5f, 8f)
                curveToRelative(-2.65f, 0f, -5.05f, 0.99f, -6.9f, 2.6f)
                lineTo(2f, 7f)
                verticalLineToRelative(9f)
                horizontalLineToRelative(9f)
                lineToRelative(-3.62f, -3.62f)
                curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
                curveToRelative(3.54f, 0f, 6.55f, 2.31f, 7.6f, 5.5f)
                lineToRelative(2.37f, -0.78f)
                curveTo(21.08f, 11.03f, 17.15f, 8f, 12.5f, 8f)
                close()
            }
        }
    }

    /** Material play-circle geometry — the "Now filtering" in-effect marker. */
    val PlayCircle: ImageVector by lazy {
        buildFilterIcon("LavaPlayCircle") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
                reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
                reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                close()
                moveTo(10f, 16.5f)
                verticalLineToRelative(-9f)
                lineToRelative(6f, 4.5f)
                close()
            }
        }
    }

    /** Material chevron-down geometry for the connection-state picker chip. */
    val ChevronDown: ImageVector by lazy {
        buildFilterIcon("LavaChevronDown") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7.41f, 8.59f)
                lineTo(12f, 13.17f)
                lineToRelative(4.59f, -4.58f)
                lineTo(18f, 10f)
                lineToRelative(-6f, 6f)
                lineToRelative(-6f, -6f)
                close()
            }
        }
    }
}

/** Material-style vectors for Settings support cards (iOS SF Symbol roles). */
object LavaSettingsIcons {
    private fun build(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    /** iOS "info.circle" — Nerd Stats. */
    val Info: ImageVector by lazy {
        build("LavaInfo") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveTo(6.48f, 22f, 12f, 22f)
                reflectiveCurveTo(22f, 17.52f, 22f, 12f)
                reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                close()
                moveTo(13f, 17f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-6f)
                horizontalLineToRelative(2f)
                close()
                moveTo(13f, 9f)
                horizontalLineToRelative(-2f)
                lineTo(11f, 7f)
                horizontalLineToRelative(2f)
                close()
            }
        }
    }

    /** iOS "waveform.path.ecg.rectangle" — Settings → Advanced → Network Activity. */
    val NetworkActivity: ImageVector by lazy {
        build("LavaNetworkActivity") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 6f)
                curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                verticalLineToRelative(12f)
                curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
                horizontalLineTo(5f)
                curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                close()
                moveTo(6f, 12f)
                horizontalLineToRelative(2.5f)
                lineToRelative(1.5f, -4f)
                lineToRelative(2.5f, 8f)
                lineToRelative(1.5f, -4f)
                horizontalLineTo(18f)
            }
        }
    }

    /** iOS "questionmark.circle" — Help. */
    val Help: ImageVector by lazy {
        build("LavaHelp") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveTo(6.48f, 22f, 12f, 22f)
                reflectiveCurveTo(22f, 17.52f, 22f, 12f)
                reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                close()
                moveTo(13f, 19f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                close()
                moveTo(15.07f, 11.25f)
                lineToRelative(-0.9f, 0.92f)
                curveTo(13.45f, 12.9f, 13f, 13.5f, 13f, 15f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-0.5f)
                curveToRelative(0f, -1.1f, 0.45f, -2.1f, 1.17f, -2.83f)
                lineToRelative(1.24f, -1.26f)
                curveToRelative(0.37f, -0.36f, 0.59f, -0.86f, 0.59f, -1.41f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                reflectiveCurveToRelative(-2f, 0.9f, -2f, 2f)
                lineTo(8f, 9f)
                curveToRelative(0f, -2.21f, 1.79f, -4f, 4f, -4f)
                reflectiveCurveToRelative(4f, 1.79f, 4f, 4f)
                curveToRelative(0f, 0.88f, -0.36f, 1.68f, -0.93f, 2.25f)
                close()
            }
        }
    }

    /** iOS "ladybug" — Feedback. */
    val Bug: ImageVector by lazy {
        build("LavaBug") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 8f)
                horizontalLineToRelative(-2.81f)
                curveToRelative(-0.45f, -0.78f, -1.07f, -1.45f, -1.82f, -1.96f)
                lineTo(17f, 4.41f)
                lineTo(15.59f, 3f)
                lineToRelative(-2.17f, 2.17f)
                curveTo(12.96f, 5.06f, 12.49f, 5f, 12f, 5f)
                curveToRelative(-0.49f, 0f, -0.96f, 0.06f, -1.41f, 0.17f)
                lineTo(8.41f, 3f)
                lineTo(7f, 4.41f)
                lineToRelative(1.62f, 1.63f)
                curveTo(7.88f, 6.55f, 7.26f, 7.22f, 6.81f, 8f)
                lineTo(4f, 8f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2.09f)
                curveToRelative(-0.05f, 0.33f, -0.09f, 0.66f, -0.09f, 1f)
                verticalLineToRelative(1f)
                lineTo(4f, 12f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(1f)
                curveToRelative(0f, 0.34f, 0.04f, 0.67f, 0.09f, 1f)
                lineTo(4f, 16f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2.81f)
                curveToRelative(1.04f, 1.79f, 2.97f, 3f, 5.19f, 3f)
                reflectiveCurveToRelative(4.15f, -1.21f, 5.19f, -3f)
                lineTo(20f, 18f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(-2.09f)
                curveToRelative(0.05f, -0.33f, 0.09f, -0.66f, 0.09f, -1f)
                verticalLineToRelative(-1f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-1f)
                curveToRelative(0f, -0.34f, -0.04f, -0.67f, -0.09f, -1f)
                lineTo(20f, 10f)
                close()
                moveTo(14f, 16f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(4f)
                close()
                moveTo(14f, 12f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(4f)
                close()
            }
        }
    }

    /** iOS "doc.text" — Legal Notices. */
    val Doc: ImageVector by lazy {
        build("LavaDoc") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(14f, 2f)
                lineTo(6f, 2f)
                curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(4f, 20f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
                lineTo(18f, 22f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(20f, 8f)
                close()
                moveTo(16f, 18f)
                lineTo(8f, 18f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(8f)
                close()
                moveTo(16f, 14f)
                lineTo(8f, 14f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(8f)
                close()
                moveTo(13f, 9f)
                lineTo(13f, 3.5f)
                lineTo(18.5f, 9f)
                close()
            }
        }
    }

    /** iOS "lock.fill" — Security. */
    val Lock: ImageVector by lazy {
        build("LavaLock") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 8f)
                horizontalLineToRelative(-1f)
                lineTo(17f, 6f)
                curveToRelative(0f, -2.76f, -2.24f, -5f, -5f, -5f)
                reflectiveCurveToRelative(-5f, 2.24f, -5f, 5f)
                verticalLineToRelative(2f)
                lineTo(6f, 8f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(10f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(12f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(20f, 10f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(12f, 17f)
                curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                reflectiveCurveToRelative(2f, 0.9f, 2f, 2f)
                reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
                close()
                moveTo(15.1f, 8f)
                lineTo(8.9f, 8f)
                lineTo(8.9f, 6f)
                curveToRelative(0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                curveToRelative(1.71f, 0f, 3.1f, 1.39f, 3.1f, 3.1f)
                close()
            }
        }
    }

    /** iOS "network" — DNS Resolver. */
    val Globe: ImageVector by lazy {
        build("LavaGlobe") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveTo(6.48f, 22f, 12f, 22f)
                reflectiveCurveTo(22f, 17.52f, 22f, 12f)
                reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                close()
                moveTo(11f, 19.93f)
                curveToRelative(-3.95f, -0.49f, -7f, -3.85f, -7f, -7.93f)
                curveToRelative(0f, -0.62f, 0.08f, -1.21f, 0.21f, -1.79f)
                lineTo(9f, 15f)
                verticalLineToRelative(1f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                close()
                moveTo(17.9f, 17.39f)
                curveToRelative(-0.26f, -0.81f, -1f, -1.39f, -1.9f, -1.39f)
                horizontalLineToRelative(-1f)
                verticalLineToRelative(-3f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                lineTo(8f, 12f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                lineTo(11f, 7f)
                horizontalLineToRelative(2f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineToRelative(-0.41f)
                curveToRelative(2.93f, 1.19f, 5f, 4.06f, 5f, 7.41f)
                curveToRelative(0f, 2.08f, -0.8f, 3.97f, -2.1f, 5.39f)
                close()
            }
        }
    }

    /** iOS "eyeglasses" — Privacy & Data. */
    val Eye: ImageVector by lazy {
        build("LavaEye") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4.5f)
                curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
                curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
                reflectiveCurveToRelative(9.27f, -3.11f, 11f, -7.5f)
                curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
                close()
                moveTo(12f, 17f)
                curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
                reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
                reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
                reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
                close()
                moveTo(12f, 9f)
                curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
                reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
                close()
            }
        }
    }

    /** iOS "square.and.arrow.up" — Share. */
    val Share: ImageVector by lazy {
        build("LavaShare") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 16.08f)
                curveToRelative(-0.76f, 0f, -1.44f, 0.3f, -1.96f, 0.77f)
                lineTo(8.91f, 12.7f)
                curveToRelative(0.05f, -0.23f, 0.09f, -0.46f, 0.09f, -0.7f)
                reflectiveCurveToRelative(-0.04f, -0.47f, -0.09f, -0.7f)
                lineToRelative(7.05f, -4.11f)
                curveToRelative(0.54f, 0.5f, 1.25f, 0.81f, 2.04f, 0.81f)
                curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
                reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
                reflectiveCurveToRelative(-3f, 1.34f, -3f, 3f)
                curveToRelative(0f, 0.24f, 0.04f, 0.47f, 0.09f, 0.7f)
                lineTo(8.04f, 9.81f)
                curveTo(7.5f, 9.31f, 6.79f, 9f, 6f, 9f)
                curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                curveToRelative(0.79f, 0f, 1.5f, -0.31f, 2.04f, -0.81f)
                lineToRelative(7.12f, 4.16f)
                curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
                curveToRelative(0f, 1.61f, 1.31f, 2.92f, 2.92f, 2.92f)
                curveToRelative(1.61f, 0f, 2.92f, -1.31f, 2.92f, -2.92f)
                reflectiveCurveToRelative(-1.31f, -2.92f, -2.92f, -2.92f)
                close()
            }
        }
    }

    /** iOS "arrow.right.circle.fill"-style open-in-browser — external links. */
    val OpenExternal: ImageVector by lazy {
        build("LavaOpenExternal") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 19f)
                lineTo(5f, 19f)
                lineTo(5f, 5f)
                horizontalLineToRelative(7f)
                lineTo(12f, 3f)
                lineTo(5f, 3f)
                curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineToRelative(-7f)
                horizontalLineToRelative(-2f)
                close()
                moveTo(14f, 3f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(3.59f)
                lineToRelative(-9.83f, 9.83f)
                lineToRelative(1.41f, 1.41f)
                lineTo(19f, 6.41f)
                lineTo(19f, 10f)
                horizontalLineToRelative(2f)
                lineTo(21f, 3f)
                close()
            }
        }
    }

    /** iOS "person.crop.circle" — Account & Backup. */
    val Account: ImageVector by lazy {
        build("LavaAccount") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
                reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
                reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                close()
                moveTo(12f, 5f)
                curveToRelative(1.93f, 0f, 3.5f, 1.57f, 3.5f, 3.5f)
                reflectiveCurveTo(13.93f, 12f, 12f, 12f)
                reflectiveCurveToRelative(-3.5f, -1.57f, -3.5f, -3.5f)
                reflectiveCurveTo(10.07f, 5f, 12f, 5f)
                close()
                moveTo(12f, 19.2f)
                curveToRelative(-2.03f, 0f, -3.82f, -0.86f, -5.03f, -2.21f)
                curveTo(8.08f, 15.28f, 9.96f, 14.5f, 12f, 14.5f)
                reflectiveCurveToRelative(3.92f, 0.78f, 5.03f, 2.49f)
                curveTo(15.82f, 18.34f, 14.03f, 19.2f, 12f, 19.2f)
                close()
            }
        }
    }

    /** iOS "sparkles" — Upgrade. */
    val Upgrade: ImageVector by lazy {
        build("LavaUpgrade") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.01f, 3f)
                horizontalLineToRelative(-0.52f)
                lineToRelative(-0.26f, 0.45f)
                lineToRelative(-3.5f, 6.04f)
                lineToRelative(0.54f, 0.42f)
                lineToRelative(6.03f, -3.5f)
                lineToRelative(0.45f, -0.26f)
                lineTo(21.23f, 5.63f)
                lineTo(21.68f, 5f)
                close()
                moveTo(17.52f, 14.5f)
                lineToRelative(-3.5f, 6.05f)
                lineToRelative(-0.26f, 0.45f)
                horizontalLineToRelative(-0.52f)
                lineToRelative(-0.45f, -0.63f)
                lineToRelative(-2.36f, -4.06f)
                lineToRelative(-4.07f, -2.35f)
                lineToRelative(-0.63f, -0.45f)
                verticalLineToRelative(-0.52f)
                lineToRelative(0.45f, -0.26f)
                lineToRelative(6.04f, -3.5f)
                lineToRelative(4.06f, 2.36f)
                lineToRelative(0.63f, 0.45f)
                verticalLineToRelative(0.52f)
                close()
                moveTo(9.94f, 10.71f)
                lineToRelative(-4.06f, -2.35f)
                lineTo(5.25f, 7.9f)
                lineTo(5.26f, 7.39f)
                lineToRelative(0.45f, -0.26f)
                lineToRelative(6.05f, -3.5f)
                lineToRelative(0.26f, -0.45f)
                horizontalLineToRelative(0.52f)
                lineToRelative(0.45f, 0.63f)
                lineToRelative(2.35f, 4.07f)
                lineToRelative(-4.07f, 2.35f)
                lineToRelative(-0.63f, 0.45f)
                close()
            }
        }
    }

    /** iOS "slider.horizontal.3" — Customization. */
    val Customization: ImageVector by lazy {
        build("LavaCustomization") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 4f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
                reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f)
                reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                close()
                moveTo(18f, 7f)
                horizontalLineToRelative(-9f)
                lineTo(9f, 5f)
                horizontalLineToRelative(9f)
                close()
                moveTo(3f, 7f)
                lineTo(3f, 5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                close()
                moveTo(18f, 16f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
                reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f)
                reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                close()
                moveTo(14f, 19f)
                lineTo(6f, 19f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(8f)
                close()
                moveTo(6f, 19f)
                lineTo(6f, 17f)
                lineTo(4f, 17f)
                verticalLineToRelative(2f)
                close()
                moveTo(18f, 10f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
                reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f)
                reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                close()
                moveTo(14f, 13f)
                lineTo(3f, 13f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(11f)
                close()
                moveTo(3f, 13f)
                lineTo(3f, 11f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                close()
            }
        }
    }

    /** iOS "hand.raised" — Privacy & data. */
    val PrivacyData: ImageVector by lazy {
        build("LavaPrivacyData") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(14.5f, 6.5f)
                lineTo(13f, 5f)
                verticalLineToRelative(5.5f)
                lineTo(12f, 10.5f)
                verticalLineToRelative(-6f)
                horizontalLineToRelative(-1f)
                lineTo(11f, 11f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(-1f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-1f)
                horizontalLineToRelative(-1f)
                lineTo(5f, 15f)
                curveToRelative(0f, 3.58f, 2.92f, 6.5f, 6.5f, 6.5f)
                reflectiveCurveTo(18f, 18.58f, 18f, 15f)
                lineTo(18f, 7f)
                close()
                moveTo(17f, 9f)
                horizontalLineToRelative(1f)
                lineTo(18f, 15f)
                curveToRelative(0f, 2.76f, -2.24f, 5f, -5f, 5f)
                curveToRelative(-0.34f, 0f, -0.68f, -0.03f, -1f, -0.1f)
                lineTo(11f, 9f)
                close()
            }
        }
    }
}
