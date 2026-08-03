package app.zhijuan.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Paper = Color(0xFFF6F0E5)
private val PaperSurface = Color(0xFFFFF9EF)
private val Ink = Color(0xFF211D19)
private val MutedInk = Color(0xFF615A52)
private val Orange = Color(0xFFB45525)
private val PaperOutline = Color(0xFFD8CDBF)

private val Night = Color(0xFF171411)
private val NightSurface = Color(0xFF24201C)
private val NightInk = Color(0xFFF5EEE4)
private val NightMutedInk = Color(0xFFCFC5B9)
private val NightOrange = Color(0xFFE28A55)
private val NightOutline = Color(0xFF51483F)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Orange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1D5C4),
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0E7DB),
    onSurfaceVariant = MutedInk,
    outline = PaperOutline,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = NightInk,
    onPrimary = Night,
    secondary = NightOrange,
    onSecondary = Night,
    secondaryContainer = Color(0xFF5C3524),
    onSecondaryContainer = NightInk,
    background = Night,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF302A25),
    onSurfaceVariant = NightMutedInk,
    outline = NightOutline,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val ZhijuanTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 46.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
)

@Composable
fun ZhijuanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ZhijuanTypography,
        content = content,
    )
}
