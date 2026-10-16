package me.excuse.app.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted

/**
 * 悬浮倒计时圆环：圆环随时间收缩，中间显示剩余分钟。
 *
 * - 分钟级：5、4、3、2、1，剩余不足 1 分钟时仍显示 1（尺寸恒定不变形）。
 * - [remainingMinutes] 剩余分钟（1..N）。
 * - [progress] 0f..1f，已用时长占比，驱动圆环收缩；1f 表示即将到点。
 * - 黑白主色：圆环用 [Ink]，背景用 [SurfaceBg]（半透明），保持 app 工业极简风。
 */
@Composable
fun CountdownRing(
    remainingMinutes: Int,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    // 在 @Composable 作用域先取色，DrawScope 内不能调用 @Composable 的语义色。
    val ringColor = Ink
    val trackColor = Muted.copy(alpha = 0.18f)
    val numberColor = Ink
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(38.dp)) {
            val strokeWidth = 3.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)

            // 底环（未用部分）—— 淡色，始终完整。
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // 进度环（已用部分）—— 主色，随时间收缩。
            val sweep = 360f * (1f - clampedProgress)
            if (sweep > 0.5f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }

        Text(
            text = remainingMinutes.coerceAtLeast(1).toString(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = numberColor,
        )
    }
}
