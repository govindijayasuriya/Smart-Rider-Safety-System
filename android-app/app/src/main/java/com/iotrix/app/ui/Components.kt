package com.iotrix.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

enum class BtnKind { Primary, Outline, Danger }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: BtnKind = BtnKind.Primary,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    val shape = RoundedCornerShape(14.dp)
    val m = modifier.height(height)
    when (kind) {
        BtnKind.Outline -> OutlinedButton(
            onClick = onClick, modifier = m, shape = shape, enabled = enabled,
            border = BorderStroke(1.5.dp, C.Teal),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Teal, containerColor = Color.White),
        ) { content() }
        BtnKind.Danger -> Button(
            onClick = onClick, modifier = m, shape = shape, enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = C.Red, contentColor = Color.White),
        ) { content() }
        BtnKind.Primary -> Button(
            onClick = onClick, modifier = m, shape = shape, enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = C.Teal, contentColor = Color.White),
        ) { content() }
    }
}

@Composable
fun Avatar(name: String, size: Dp = 52.dp) {
    val palette = listOf(
        Color(0xFFF4A261), Color(0xFF2A9D8F), Color(0xFFE76F51),
        Color(0xFF6C8EBF), Color(0xFFB07CC6), Color(0xFFE9C46A),
    )
    val color = palette[(name.firstOrNull()?.code ?: 0) % palette.size]
    val initials = name.trim().split(" ").filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }
    Box(
        Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
    }
}
