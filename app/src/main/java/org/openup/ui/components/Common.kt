package org.openup.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openup.data.AppParts
import java.io.File
import java.text.DateFormat
import java.util.Date

object IconCache {
    private val cache = LruCache<String, ImageBitmap>(300)

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun load(context: Context, packageName: String, fallbackPng: File?): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        val bmp = try {
            context.packageManager.getApplicationIcon(packageName).toBitmap(144, 144).asImageBitmap()
        } catch (_: Exception) {
            fallbackPng?.takeIf { it.isFile }?.let { f -> BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
        }
        if (bmp != null) cache.put(packageName, bmp)
        return bmp
    }

    fun invalidate(packageName: String) {
        cache.remove(packageName)
    }
}

@Composable
fun AppIcon(packageName: String, fallbackPng: File? = null, size: Dp = 40.dp) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf(IconCache.get(packageName)) }
    LaunchedEffect(packageName, fallbackPng) {
        if (bitmap == null) bitmap = withContext(Dispatchers.IO) { IconCache.load(context, packageName, fallbackPng) }
    }
    val b = bitmap
    if (b != null) {
        Image(bitmap = b, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun formatBytes(context: Context, bytes: Long): String =
    if (bytes < 0) "—" else Formatter.formatShortFileSize(context, bytes)

fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

fun formatRelative(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(label)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Lets the user pick which parts of an app to back up or restore. */
@Composable
fun PartsDialog(
    title: String,
    initial: AppParts,
    restore: Boolean,
    available: AppParts? = null,
    confirm: String,
    onConfirm: (AppParts) -> Unit,
    onDismiss: () -> Unit,
) {
    var parts by remember { mutableStateOf(initial) }
    val avail = available ?: AppParts()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                CheckRow("APK", if (restore) "Install the app (skipped if the same or a newer version is installed)" else "App package including split APKs", parts.apk, avail.apk) { parts = parts.copy(apk = it) }
                CheckRow("External data", "Android/data folder", parts.extData, avail.extData) { parts = parts.copy(extData = it) }
                CheckRow("Expansion files", "Android/obb folder", parts.obb, avail.obb) { parts = parts.copy(obb = it) }
                CheckRow("Media", "Android/media folder", parts.media, avail.media) { parts = parts.copy(media = it) }
                CheckRow(
                    "Permissions & app settings",
                    "Runtime permissions, usage access, accessibility service, battery optimisation and other special access",
                    parts.permissions,
                    avail.permissions,
                ) { parts = parts.copy(permissions = it) }
                Spacer(Modifier.padding(4.dp))
                Text(
                    "Private app data (/data/data), notification settings and the SSAID cannot be accessed without root.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            val effective = AppParts(
                apk = parts.apk && avail.apk,
                extData = parts.extData && avail.extData,
                obb = parts.obb && avail.obb,
                media = parts.media && avail.media,
                permissions = parts.permissions && avail.permissions,
            )
            TextButton(
                enabled = effective.any || (restore && effective.permissions),
                onClick = {
                    onDismiss()
                    onConfirm(effective)
                },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
