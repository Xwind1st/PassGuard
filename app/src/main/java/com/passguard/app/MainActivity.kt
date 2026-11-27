package com.passguard.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Palette : pin profond pour le texte, menthe / ambre / corail pour le niveau du réservoir.
private val Ink = Color(0xFF14302B)
private val Paper = Color(0xFFF3F5F0)
private val Track = Color(0xFFDCE4DD)
private val Line = Color(0xFFC9D4CC)
private val Muted = Color(0xFF566B65)
private val Danger = Color(0xFFB3301F)
private val FillOk = Color(0xFF8ED3B9)
private val FillWarn = Color(0xFFF0C95A)
private val FillLow = Color(0xFFEE9B8B)

private val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

private enum class Screen { SETUP, DASHBOARD, SETTINGS }

class MainActivity : ComponentActivity() {

    private val visible = mutableStateOf(false)
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent)
        )
        setContent {
            PassGuardTheme {
                App(visible = visible.value, resumeKey = resumes.intValue)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible.value = true
    }

    override fun onStop() {
        visible.value = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue = resumes.intValue + 1
    }
}

@Composable
private fun PassGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink, onPrimary = Color.White,
            background = Paper, onBackground = Ink,
            surface = Paper, onSurface = Ink
        ),
        content = content
    )
}

@Composable
private fun currentLang(): String {
    val tag = LocalConfiguration.current.locales.get(0).language
    return if (tag == "en") "en" else "fr"
}

@Composable
private fun App(visible: Boolean, resumeKey: Int) {
    val ctx = LocalContext.current
    val usageOk = remember(resumeKey) { UsageReader.hasUsageAccess(ctx) }
    var pass by remember { mutableStateOf(PassStore.load(ctx)) }
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var showComplete by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(usageOk) {
        if (usageOk && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(usageOk, pass) {
        val p = pass
        if (usageOk && p != null && !p.exhausted && System.currentTimeMillis() < p.endMs) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            } catch (e: Exception) {
                // Android a refusé le lancement : le suivi reprendra via le bouton de relance manuelle.
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
    ) {
        when {
            !usageOk -> AccessScreen()
            screen == Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.DASHBOARD })
            pass == null || screen == Screen.SETUP -> SetupScreen(
                canCancel = pass != null,
                onCancel = { screen = Screen.DASHBOARD },
                onSave = { newPass ->
                    Tracker.reset(ctx)
                    PassStore.save(ctx, newPass)
                    pass = newPass
                    screen = Screen.DASHBOARD
                }
            )
            else -> {
                val current = pass!!
                Dashboard(
                    pass = current,
                    visible = visible,
                    onNewPass = { screen = Screen.SETUP },
                    onSettings = { screen = Screen.SETTINGS },
                    onComplete = { showComplete = true },
                    onDeleteRequest = { showDeleteConfirm = true }
                )
                if (showComplete) {
                    CompleteDialog(
                        onDismiss = { showComplete = false },
                        onConfirm = { addBytes, addDuration ->
                            val used = currentUsed(ctx, current)
                            val updated = completePass(current, addBytes, addDuration, used)
                            PassStore.save(ctx, updated)
                            pass = updated
                            showComplete = false
                        }
                    )
                }
                if (showDeleteConfirm) {
                    DeleteConfirmDialog(
                        onDismiss = { showDeleteConfirm = false },
                        onConfirm = { keepHistory ->
                            val used = currentUsed(ctx, current)
                            ctx.stopService(Intent(ctx, MonitorService::class.java))
                            PassStore.clear(ctx, keepHistory, used)
                            pass = null
                            showDeleteConfirm = false
                            screen = Screen.SETUP
                        }
                    )
                }
            }
        }
    }
}

// ---------- Écran 1 : autorisation ----------

@Composable
private fun AccessScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(stringResource(R.string.access_title), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(stringResource(R.string.access_body), color = Muted)
        Text(stringResource(R.string.access_steps), color = Ink)
        PrimaryButton(stringResource(R.string.access_button)) { openUsageSettings(ctx) }
    }
}

// ---------- Écran 2 : fixer le forfait ----------

@Composable
private fun SetupScreen(canCancel: Boolean, onCancel: () -> Unit, onSave: (Pass) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var inGo by remember { mutableStateOf(true) }
    var duration by remember { mutableStateOf("") }
    var inDays by remember { mutableStateOf(true) }

    val amountValue = amount.replace(',', '.').toDoubleOrNull()
    val durationValue = duration.replace(',', '.').toDoubleOrNull()
    val valid = amountValue != null && amountValue > 0.0 && durationValue != null && durationValue > 0.0

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(stringResource(R.string.setup_title), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(stringResource(R.string.setup_subtitle), color = Muted)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == ',' || c == '.' } },
                    label = { Text(stringResource(R.string.setup_amount_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Pill(stringResource(R.string.unit_mo), !inGo) { inGo = false }
                Pill(stringResource(R.string.unit_go), inGo) { inGo = true }
            }
            Text(stringResource(R.string.setup_unit_note), color = Muted, fontSize = 13.sp)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = duration,
                onValueChange = { duration = it.filter { c -> c.isDigit() || c == ',' || c == '.' } },
                label = { Text(stringResource(R.string.setup_duration_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            Pill(stringResource(R.string.unit_hours), !inDays) { inDays = false }
            Pill(stringResource(R.string.unit_days), inDays) { inDays = true }
        }

        PrimaryButton(stringResource(R.string.setup_confirm), enabled = valid) {
            val a = amountValue
            val d = durationValue
            if (a != null && d != null && a > 0.0 && d > 0.0) {
                val now = System.currentTimeMillis()
                val bytes = (a * (if (inGo) GB else MB)).toLong()
                val span = (d * (if (inDays) DAY_MS else HOUR_MS)).toLong()
                onSave(Pass(totalBytes = bytes, startMs = now, endMs = now + span))
            }
        }
        if (canCancel) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.setup_cancel), color = Ink) }
        }
    }
}

// ---------- Écran 3 : le réservoir ----------

@Composable
private fun Dashboard(
    pass: Pass,
    visible: Boolean,
    onNewPass: () -> Unit,
    onSettings: () -> Unit,
    onComplete: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val unattributedLabel = stringResource(R.string.dash_unattributed)
    var snap by remember(pass) { mutableStateOf<Snapshot?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var stale by remember { mutableStateOf(false) }

    LaunchedEffect(pass, visible, lang) {
        if (!visible) return@LaunchedEffect
        val meter = SpeedMeter()
        var apps: List<AppUsage> = emptyList()
        var tick = 0
        while (true) {
            val t = System.currentTimeMillis()
            val end = minOf(t, pass.endMs)
            val refreshApps = tick % 4 == 0
            val result = withContext(Dispatchers.Default) {
                val used = currentUsed(ctx, pass)
                val speed = meter.sample()
                val list = if (refreshApps) UsageReader.perApp(ctx, pass.startMs, end, lang) else apps
                Snapshot(used, speed, list)
            }
            apps = result.apps
            snap = result
            now = t
            stale = Heartbeat.isStale(ctx)
            tick++
            delay(1_000)
        }
    }

    val s = snap
    val exact = s?.used ?: 0L
    val appsWithGap = if (s != null) withUnattributed(exact, s.apps, unattributedLabel) else emptyList()
    val left = (pass.totalBytes - exact).coerceAtLeast(0L)
    val fraction = if (pass.totalBytes > 0L) {
        (left.toDouble() / pass.totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else 0f
    val level by animateFloatAsState(targetValue = fraction, label = "reservoir")
    val timeExpired = now >= pass.endMs
    val percentUsed = if (pass.totalBytes > 0L) (exact * 100L / pass.totalBytes).toInt().coerceIn(0, 100) else 0
    val fillColor = when {
        fraction > 0.25f -> FillOk
        fraction > 0.10f -> FillWarn
        else -> FillLow
    }
    val (number, unit) = bytesParts(left, lang)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("PassGuard", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                "\u2699",
                fontSize = 20.sp,
                color = Ink,
                modifier = Modifier
                    .clickable(onClick = onSettings)
                    .padding(6.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (stale) stringResource(R.string.dash_tracking_paused) else stringResource(R.string.dash_tracking_active),
                color = if (stale) FillWarn else Muted,
                fontSize = 13.sp
            )
            if (stale) {
                TextButton(onClick = {
                    try {
                        ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
                    } catch (e: Exception) { }
                }) { Text(stringResource(R.string.dash_relaunch), color = Ink, fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Le réservoir : le niveau baisse au fil de la consommation.
        Box(
            Modifier
                .fillMaxWidth()
                .height(230.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Track)
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight(level)
                    .background(fillColor)
            )
            Column(Modifier.padding(22.dp)) {
                Text(
                    when {
                        left <= 0L -> stringResource(R.string.reservoir_exhausted)
                        timeExpired -> stringResource(R.string.reservoir_expired)
                        else -> stringResource(R.string.reservoir_left)
                    },
                    color = Ink,
                    fontSize = 16.sp
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(number, fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 92.sp, color = Ink)
                    Text(
                        unit,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink,
                        modifier = Modifier.padding(start = 8.dp, bottom = 16.dp)
                    )
                }
                Text(
                    stringResource(R.string.dash_of_total, formatBytes(pass.totalBytes, lang), percentUsed),
                    color = Ink,
                    fontSize = 15.sp
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dash_now_label), color = Muted, fontSize = 13.sp)
                Text(
                    if (timeExpired) "—" else formatSpeed(s?.speed ?: 0L, lang),
                    color = Ink,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dash_valid_label), color = Muted, fontSize = 13.sp)
                Text(
                    formatRemainingTime(pass.endMs - now, lang),
                    color = Ink,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onNewPass, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dash_new_plan), color = Ink)
            }
            TextButton(onClick = onComplete, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dash_complete), color = Ink)
            }
        }
        HorizontalDivider(color = Line)

        Text(
            stringResource(R.string.dash_who_title),
            color = Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            stringResource(R.string.dash_who_subtitle),
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        when {
            s == null -> Text(stringResource(R.string.dash_calculating), color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            appsWithGap.isEmpty() -> Text(
                stringResource(R.string.dash_empty),
                color = Muted,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            else -> {
                val biggest = appsWithGap.maxOf { it.bytes }
                appsWithGap.forEach { AppRow(it, biggest, lang) }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Line)
        Text(
            stringResource(R.string.dash_disclaimer),
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 14.dp)
        )
        TextButton(onClick = { openBatterySettings(ctx) }) {
            Text(stringResource(R.string.dash_battery_link), color = Ink)
        }
        TextButton(onClick = onDeleteRequest) { Text(stringResource(R.string.dash_delete), color = Danger) }
    }
}

@Composable
private fun AppRow(app: AppUsage, biggest: Long, lang: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                app.name,
                color = Ink,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            )
            Text(formatBytes(app.bytes, lang), color = Ink, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Track)
        ) {
            val share = if (biggest > 0L) (app.bytes.toFloat() / biggest.toFloat()).coerceIn(0.02f, 1f) else 0.02f
            Box(
                Modifier
                    .fillMaxWidth(share)
                    .fillMaxHeight()
                    .background(Ink)
            )
        }
        if (app.bgBytes > 0L) {
            Text(
                stringResource(R.string.dash_bg_part, formatBytes(app.bgBytes, lang)),
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ---------- F2 bis : compléter le forfait ----------

@Composable
private fun CompleteDialog(onDismiss: () -> Unit, onConfirm: (addBytes: Long, addDurationMs: Long) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var inGo by remember { mutableStateOf(true) }
    var duration by remember { mutableStateOf("") }
    var inDays by remember { mutableStateOf(true) }

    val amountValue = amount.replace(',', '.').toDoubleOrNull()
    val durationValue = duration.replace(',', '.').toDoubleOrNull()
    val valid = amountValue != null && amountValue > 0.0

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Paper)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.complete_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(stringResource(R.string.complete_subtitle), color = Muted, fontSize = 13.sp)

            Text(stringResource(R.string.complete_add_label), color = Ink, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == ',' || c == '.' } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Pill(stringResource(R.string.unit_mo), !inGo) { inGo = false }
                Pill(stringResource(R.string.unit_go), inGo) { inGo = true }
            }

            Text(stringResource(R.string.complete_extend_label), color = Ink, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it.filter { c -> c.isDigit() || c == ',' || c == '.' } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Pill(stringResource(R.string.unit_hours), !inDays) { inDays = false }
                Pill(stringResource(R.string.unit_days), inDays) { inDays = true }
            }

            PrimaryButton(stringResource(R.string.complete_confirm), enabled = valid) {
                val a = amountValue
                if (a != null && a > 0.0) {
                    val addBytes = (a * (if (inGo) GB else MB)).toLong()
                    val d = durationValue
                    val addDuration = if (d != null && d > 0.0) (d * (if (inDays) DAY_MS else HOUR_MS)).toLong() else 0L
                    onConfirm(addBytes, addDuration)
                }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.complete_cancel), color = Ink) }
        }
    }
}

// ---------- F11 : supprimer le forfait ----------

@Composable
private fun DeleteConfirmDialog(onDismiss: () -> Unit, onConfirm: (keepHistory: Boolean) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Paper)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.delete_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(stringResource(R.string.delete_keep_desc), color = Muted, fontSize = 13.sp)
            PrimaryButton(stringResource(R.string.delete_confirm_keep)) { onConfirm(true) }
            TextButton(onClick = { onConfirm(false) }) {
                Text(stringResource(R.string.delete_confirm_discard), color = Danger)
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_cancel), color = Ink) }
        }
    }
}

// ---------- F12 bis : espace personnel ----------

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val lang = currentLang()
    var tick by remember { mutableStateOf(0) }

    val usageOk = remember(tick) { UsageReader.hasUsageAccess(ctx) }
    val notifOk = remember(tick) { UsageReader.areNotificationsEnabled(ctx) }
    val batteryOk = remember(tick) { UsageReader.isBatteryUnrestricted(ctx) }
    val summary = remember { LastSummary.read(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("\u2039 " + stringResource(R.string.settings_back), color = Ink) }
        Text(stringResource(R.string.settings_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)

        Text(stringResource(R.string.settings_language), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.settings_lang_fr), lang == "fr") { applyLanguage(activity, "fr") }
            Pill(stringResource(R.string.settings_lang_en), lang == "en") { applyLanguage(activity, "en") }
        }

        HorizontalDivider(color = Line)
        Text(stringResource(R.string.settings_permissions), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        PermissionRow(stringResource(R.string.settings_usage_access), usageOk) { openUsageSettings(ctx); tick++ }
        PermissionRow(stringResource(R.string.settings_notifications), notifOk) { openNotificationSettings(ctx); tick++ }
        PermissionRow(stringResource(R.string.settings_battery), batteryOk) { openBatterySettings(ctx); tick++ }

        HorizontalDivider(color = Line)
        Text(stringResource(R.string.settings_privacy), color = Muted, fontSize = 13.sp)

        if (summary != null && summary.size == 4) {
            HorizontalDivider(color = Line)
            Text(
                stringResource(R.string.settings_last_summary_title),
                color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
            )
            Text(
                stringResource(
                    R.string.settings_last_summary,
                    formatBytes(summary[0], lang), formatBytes(summary[1], lang),
                    formatShortDate(summary[2], lang), formatShortDate(summary[3], lang)
                ),
                color = Muted, fontSize = 13.sp
            )
        }

        HorizontalDivider(color = Line)
        Text(stringResource(R.string.settings_limits_title), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(stringResource(R.string.dash_disclaimer), color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ink, fontWeight = FontWeight.Medium)
            Text(
                if (ok) stringResource(R.string.settings_enabled) else stringResource(R.string.settings_setup_needed),
                color = if (ok) FillOk else FillLow,
                fontSize = 13.sp
            )
        }
        if (!ok) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.settings_open), color = Ink) }
        }
    }
}

// ---------- Petits composants ----------

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Ink else Track)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun openUsageSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e2: Exception) {
            // Réglage introuvable sur ce téléphone.
        }
    }
}

private fun openBatterySettings(ctx: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
        ctx.startActivity(intent)
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e2: Exception) {
            // Réglage introuvable sur ce téléphone.
        }
    }
}

private fun openNotificationSettings(ctx: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        ctx.startActivity(intent)
    } catch (e: Exception) {
        // Réglage introuvable sur ce téléphone.
    }
}

/** Change la langue de l'application (F12 bis) et recrée l'activité pour l'appliquer tout de suite. */
private fun applyLanguage(activity: Activity?, lang: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
    activity?.recreate()
}
