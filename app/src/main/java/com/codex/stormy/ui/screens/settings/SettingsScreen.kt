package com.codex.stormy.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.stormy.BuildConfig
import com.codex.stormy.R
import com.codex.stormy.data.repository.ThemeMode
import com.codex.stormy.ui.theme.PoppinsFontFamily

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onNavigateToAiModels: () -> Unit = {},
    onNavigateToMemories: () -> Unit = {},
    onNavigateToGitSettings: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = context.getString(R.string.settings_title),
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = context.getString(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Appearance Section
            SettingsSection(title = "Appearance") {
                // Theme Setting
                ThemeSelector(
                    currentTheme = uiState.themeMode,
                    onThemeSelected = viewModel::setThemeMode
                )

                // Dynamic Colors (Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsToggle(
                        icon = Icons.Outlined.Palette,
                        title = "Dynamic Colors",
                        description = "Use system color palette",
                        checked = uiState.dynamicColors,
                        onCheckedChange = viewModel::setDynamicColors
                    )
                }
            }

            // Editor Section
            SettingsSection(title = "Editor") {
                // Font Size
                FontSizeSetting(
                    currentSize = uiState.fontSize,
                    onSizeChange = viewModel::setFontSize
                )

                // Line Numbers
                SettingsToggle(
                    icon = Icons.Outlined.Code,
                    title = "Line Numbers",
                    description = "Show line numbers in editor",
                    checked = uiState.lineNumbers,
                    onCheckedChange = viewModel::setLineNumbers
                )

                // Word Wrap
                SettingsToggle(
                    icon = Icons.AutoMirrored.Outlined.WrapText,
                    title = "Word Wrap",
                    description = "Wrap long lines",
                    checked = uiState.wordWrap,
                    onCheckedChange = viewModel::setWordWrap
                )

                // Auto Save
                SettingsToggle(
                    icon = Icons.Outlined.Save,
                    title = "Auto Save",
                    description = "Save files automatically",
                    checked = uiState.autoSave,
                    onCheckedChange = viewModel::setAutoSave
                )
            }

            // AI Section
            SettingsSection(title = "AI") {
                // AI Models Navigation
                val currentModelName = uiState.availableModels.find { it.id == uiState.aiModel }?.name
                    ?: uiState.aiModel.substringAfterLast("/")
                SettingsNavItem(
                    icon = Icons.Outlined.ModelTraining,
                    title = "AI Models",
                    subtitle = currentModelName,
                    onClick = onNavigateToAiModels
                )

                // Memories Navigation
                SettingsNavItem(
                    icon = Icons.Outlined.Memory,
                    title = "Memories",
                    subtitle = "AI agent learnings & context",
                    onClick = onNavigateToMemories
                )
            }

            // Git Section
            SettingsSection(title = "Git") {
                GitSettingsNavItem(
                    title = "Git Settings",
                    subtitle = "Identity, credentials & preferences",
                    onClick = onNavigateToGitSettings
                )
            }

            // About Section
            SettingsSection(title = "About") {
                SettingsInfoItem(
                    icon = Icons.Outlined.Info,
                    title = "Version",
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
            }
            
            // Beta Section
            SettingsSection(title = "Beta") {
                 SettingsToggle(
                    icon = Icons.Outlined.Code, // Reusing Code icon or maybe Bug icon if available
                    title = "Debug Logs",
                    description = "Save raw AI interactions to .codex/logs.txt in project",
                    checked = uiState.debugLogsEnabled,
                    onCheckedChange = viewModel::setDebugLogsEnabled
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Section container with title
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * Theme selector with visual preview
 */
@Composable
private fun ThemeSelector(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Brightness4,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = "Theme",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = PoppinsFontFamily,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Theme toggle chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeChip(
                icon = Icons.Outlined.PhoneAndroid,
                isSelected = currentTheme == ThemeMode.SYSTEM,
                onClick = { onThemeSelected(ThemeMode.SYSTEM) }
            )
            ThemeChip(
                icon = Icons.Outlined.LightMode,
                isSelected = currentTheme == ThemeMode.LIGHT,
                onClick = { onThemeSelected(ThemeMode.LIGHT) }
            )
            ThemeChip(
                icon = Icons.Outlined.DarkMode,
                isSelected = currentTheme == ThemeMode.DARK,
                onClick = { onThemeSelected(ThemeMode.DARK) }
            )
        }
    }
}

/**
 * Theme selection chip
 */
@Composable
private fun ThemeChip(
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * Font size slider setting
 */
@Composable
private fun FontSizeSetting(
    currentSize: Float,
    onSizeChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FormatSize,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Font Size",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = PoppinsFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "${currentSize.toInt()}sp",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = currentSize,
            onValueChange = onSizeChange,
            valueRange = 10f..24f,
            steps = 13,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "10",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = "24",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Toggle setting item
 */
@Composable
private fun SettingsToggle(
    icon: ImageVector,
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = PoppinsFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = PoppinsFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/**
 * Navigation item to another screen
 */
@Composable
private fun SettingsNavItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = PoppinsFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = PoppinsFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Info item (read-only)
 */
@Composable
private fun SettingsInfoItem(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = PoppinsFontFamily,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = PoppinsFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Git settings navigation item with GitHub icon
 */
@Composable
private fun GitSettingsNavItem(
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_github),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = PoppinsFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = PoppinsFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}
