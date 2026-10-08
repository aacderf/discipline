package com.hl.discipline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Design tokens · Light", widthDp = 360, heightDp = 760, showBackground = true)
@Composable
private fun LightDesignTokensPreview() = DesignTokensPreview(dark = false)

@Preview(name = "Design tokens · Dark", widthDp = 360, heightDp = 760, showBackground = true)
@Composable
private fun DarkDesignTokensPreview() = DesignTokensPreview(dark = true)

@Composable
private fun DesignTokensPreview(dark: Boolean) {
    DisciplineTheme(darkTheme = dark) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(AppSpacing.page),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                Text("今天，专注一件事", style = MaterialTheme.typography.headlineMedium)
                Text("标题、正文、辅助信息和不同操作状态", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlassTaskPanel {
                    Text("下一步", style = MaterialTheme.typography.titleMedium)
                    Text("在图书馆专注 25 分钟", style = MaterialTheme.typography.bodyLarge)
                    GlassPrimaryButton("开始专注", Modifier.fillMaxWidth(), iconRes = R.drawable.ic_play) {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    GlassSecondaryButton("请假", modifier = Modifier.weight(1f), onClick = {})
                    GlassSecondaryButton("删除", modifier = Modifier.weight(1f),
                        tone = GlassSecondaryTone.Destructive, onClick = {})
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    GlassIconButton("设置", onClick = {}) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = null,
                            modifier = Modifier.size(AppIconSize.action))
                    }
                    GlassPillButton("已选中", selected = true, onClick = {})
                    GlassPillButton("未选中", selected = false, onClick = {})
                }
                GlassPrimaryButton("不可用", Modifier.fillMaxWidth(), enabled = false, onClick = {})
                GlassLoadingButton(Modifier.fillMaxWidth())
                GlassSuccessButton(Modifier.fillMaxWidth())
            }
        }
    }
}
