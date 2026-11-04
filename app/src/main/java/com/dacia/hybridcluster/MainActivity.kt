package com.dacia.hybridcluster

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dacia.hybridcluster.model.AppLanguage
import com.dacia.hybridcluster.model.AppStrings
import com.dacia.hybridcluster.model.ConfigManager
import com.dacia.hybridcluster.model.StringsManager
import com.dacia.hybridcluster.model.UiConfig
import com.dacia.hybridcluster.obd.ObdConnectionStatus
import com.dacia.hybridcluster.obd.ObdLogger
import com.dacia.hybridcluster.obd.ObdManager

enum class AppScreen {
    MAIN,
    CALIBRATION,
    ELEMENTS,
    DIAGNOSTICS,
    TELEMETRY
}

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.dacia.hybridcluster.model.DaciaFonts.init(applicationContext)
        ConfigManager.init(applicationContext)
        ObdManager.start()

        checkPermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0B0B0B),
                    surface = Color(0xFF171717),
                    primary = Color(0xFFEC6528),
                    secondary = Color(0xFFB3CC23)
                )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf(AppScreen.MAIN) }

                    BackHandler(enabled = currentScreen != AppScreen.MAIN) {
                        currentScreen = AppScreen.MAIN
                    }

                    when (currentScreen) {
                        AppScreen.MAIN -> {
                            ClusterConfigScreen(
                                onOpenCalibration = { currentScreen = AppScreen.CALIBRATION },
                                onOpenElements = { currentScreen = AppScreen.ELEMENTS },
                                onOpenDiagnostics = { currentScreen = AppScreen.DIAGNOSTICS },
                                onOpenTelemetry = { currentScreen = AppScreen.TELEMETRY },
                                onSelectDevice = { device ->
                                    ObdManager.connectDevice(device)
                                    val currentStrings = StringsManager.get(ConfigManager.configState.value.appLanguage)
                                    Toast.makeText(this, currentStrings.toastConnecting(device.name ?: device.address), Toast.LENGTH_SHORT).show()
                                },
                                onDisconnect = {
                                    ObdManager.disconnectBluetooth()
                                }
                            )
                        }
                        AppScreen.CALIBRATION -> {
                            ClusterCalibrationScreen(
                                onBack = { currentScreen = AppScreen.MAIN }
                            )
                        }
                        AppScreen.ELEMENTS -> {
                            ClusterElementsScreen(
                                onBack = { currentScreen = AppScreen.MAIN }
                            )
                        }
                        AppScreen.DIAGNOSTICS -> {
                            ObdDiagnosticsScreen(
                                onBack = { currentScreen = AppScreen.MAIN }
                            )
                        }
                        AppScreen.TELEMETRY -> {
                            LiveTelemetryScreen(
                                onBack = { currentScreen = AppScreen.MAIN }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }
}

/**
 * EKRAN GŁÓWNY:
 * Status Bluetooth, szybkie wejście do podstron kalibracji, elementów, telemetrii na żywo i logów OBD.
 */
@SuppressLint("MissingPermission")
@Composable
fun ClusterConfigScreen(
    onOpenCalibration: () -> Unit,
    onOpenElements: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTelemetry: () -> Unit,
    onSelectDevice: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    val config by ConfigManager.configState.collectAsState()
    val strings = remember(config.appLanguage) { StringsManager.get(config.appLanguage) }
    val connectionStatus by ObdManager.connectionStatus.collectAsState()
    val connectedDeviceName by ObdManager.connectedDeviceName.collectAsState()

    var showDevicePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = strings.appTitle,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = strings.appSubtitle,
            fontSize = 14.sp,
            color = Color(0xFF8E99A4),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // KARTA BLUETOOTH & TRYB DEMO
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Status Połączenia
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(strings.connectionStatusLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8E99A4))
                        val statusText = when {
                            config.demoMode -> strings.statusDemo
                            connectionStatus == ObdConnectionStatus.CONNECTED -> strings.statusConnected(connectedDeviceName ?: config.selectedBtDeviceName ?: "vLinker")
                            connectionStatus == ObdConnectionStatus.CONNECTING -> strings.statusConnecting(connectedDeviceName ?: "vLinker")
                            connectionStatus == ObdConnectionStatus.ERROR -> strings.statusError
                            else -> strings.statusDisconnected
                        }
                        val statusColor = when {
                            config.demoMode -> Color(0xFFB388FF)
                            connectionStatus == ObdConnectionStatus.CONNECTED -> Color(0xFF00E676)
                            connectionStatus == ObdConnectionStatus.CONNECTING -> Color(0xFFFFD740)
                            connectionStatus == ObdConnectionStatus.ERROR -> Color(0xFFFF5252)
                            else -> Color(0xFF8E99A4)
                        }
                        Text(statusText, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = statusColor)
                    }

                    if (!config.demoMode && connectionStatus == ObdConnectionStatus.CONNECTED) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                        ) {
                            Text(strings.btnDisconnect, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Przełącznik Demo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.demoModeTitle, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(strings.demoModeSubtitle, fontSize = 12.sp, color = Color(0xFF8E99A4))
                    }
                    Switch(
                        checked = config.demoMode,
                        onCheckedChange = { ConfigManager.updateConfig(config.copy(demoMode = it)) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Wybór profilu adaptera: vLinker MS (STN2120) vs Standardowy ELM327
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (config.useVlinkerFastProtocol) strings.adapterProfileVlinker else strings.adapterProfileElm,
                                fontWeight = FontWeight.Bold,
                                color = if (config.useVlinkerFastProtocol) Color(0xFF00E5FF) else Color(0xFFE0E0E0),
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            text = if (config.useVlinkerFastProtocol)
                                strings.adapterProfileVlinkerDesc
                            else
                                strings.adapterProfileElmDesc,
                            fontSize = 11.sp,
                            color = Color(0xFF8E99A4)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = config.useVlinkerFastProtocol,
                        onCheckedChange = { ConfigManager.updateConfig(config.copy(useVlinkerFastProtocol = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF),
                            checkedTrackColor = Color(0xFF005B7F)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Przycisk wyboru urządzenia Bluetooth
                Button(
                    onClick = { showDevicePicker = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (config.selectedBtDeviceName != null)
                            strings.btnChangeDevice(config.selectedBtDeviceName!!)
                        else
                            strings.btnSelectDevice
                    )
                }
            }
        }

        Text(
            text = strings.sectionModules,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF8E99A4),
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        // 1. BANER: KALIBRACJA EKRANU I SUWAKI UI
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2436)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenCalibration() }
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.cardCalibrationTitle, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        strings.cardCalibrationDesc,
                        fontSize = 12.sp,
                        color = Color(0xFFB0B0B0)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onOpenCalibration,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(strings.btnOpen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 2. BANER: ELEMENTY NA CYFROWYCH ZEGARACH
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2436)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenElements() }
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.cardElementsTitle, fontWeight = FontWeight.Bold, color = Color(0xFFB3CC23), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        strings.cardElementsDesc,
                        fontSize = 12.sp,
                        color = Color(0xFFB0B0B0)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onOpenElements,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(strings.btnOpen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3. BANER: PODGLĄD TELEMETRII NA ŻYWO
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2436)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenTelemetry() }
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.cardTelemetryTitle, fontWeight = FontWeight.Bold, color = Color(0xFF00E676), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        strings.cardTelemetryDesc,
                        fontSize = 12.sp,
                        color = Color(0xFFB0B0B0)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onOpenTelemetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(strings.btnOpen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 4. BANER: DIAGNOSTYKA I LOGI OBD
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2436)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenDiagnostics() }
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.cardDiagnosticsTitle, fontWeight = FontWeight.Bold, color = Color(0xFFEC6528), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        strings.cardDiagnosticsDesc,
                        fontSize = 12.sp,
                        color = Color(0xFFB0B0B0)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onOpenDiagnostics,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(strings.btnOpen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 5. BANER: WYBÓR JĘZYKA APLIKACJI
        Text(
            text = strings.sectionLanguage,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF8E99A4),
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = strings.languageTitle,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.languageSubtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF8E99A4)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        val isSelected = config.appLanguage.equals(lang.code, ignoreCase = true)
                        val chipLabel = when (lang) {
                            AppLanguage.SYSTEM -> strings.langSystemOption
                            AppLanguage.PL -> strings.langPlOption
                            AppLanguage.EN -> strings.langEnOption
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { ConfigManager.updateConfig(config.copy(appLanguage = lang.code)) },
                            label = { Text(chipLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF),
                                selectedLabelColor = Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    // DIALOG WYBORU URZĄDZENIA BLUETOOTH
    if (showDevicePicker) {
        BluetoothDevicePickerDialog(
            onDismiss = { showDevicePicker = false },
            onDeviceSelected = { device ->
                showDevicePicker = false
                onSelectDevice(device)
            },
            strings = strings
        )
    }
}

/**
 * PODSTRONA: ELEMENTY NA CYFROWYCH ZEGARACH
 * Przełączniki widoczności modułów, układów, logowania i eksperymentalnych PID-ów.
 */
@Composable
fun ClusterElementsScreen(
    onBack: () -> Unit
) {
    val config by ConfigManager.configState.collectAsState()
    val strings = remember(config.appLanguage) { StringsManager.get(config.appLanguage) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // Górny pasek powrotu
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
            ) {
                Text(strings.btnBackToMenu, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(strings.elementsScreenTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Text(strings.elementsScreenSubtitle, fontSize = 12.sp, color = Color(0xFF8E99A4))
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // WYBÓR UKŁADU WIDOKU SZEROKIEGO
                Text(
                    text = strings.elementsActiveLayoutTitle,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF),
                    fontSize = 15.sp
                )
                Text(
                    text = strings.elementsActiveLayoutSubtitle,
                    fontSize = 11.sp,
                    color = Color(0xFF8E99A4),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !config.showTachometerInWide,
                        onClick = { ConfigManager.updateConfig(config.copy(showTachometerInWide = false)) },
                        label = { Text(strings.layoutCardsOnly, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFB3CC23),
                            selectedLabelColor = Color.Black
                        )
                    )
                    FilterChip(
                        selected = config.showTachometerInWide,
                        onClick = { ConfigManager.updateConfig(config.copy(showTachometerInWide = true)) },
                        label = { Text(strings.layoutFull, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFEC6528),
                            selectedLabelColor = Color.White
                        )
                    )
                }

                HorizontalDivider(color = Color(0xFF22283A), modifier = Modifier.padding(vertical = 8.dp))

                ConfigToggleItem(
                    title = strings.togglePowerBarTitle,
                    subtitle = strings.togglePowerBarSubtitle,
                    checked = config.showMultiSegmentPower,
                    onCheckedChange = { ConfigManager.updateConfig(config.copy(showMultiSegmentPower = it)) }
                )

                HorizontalDivider(color = Color(0xFF22283A), modifier = Modifier.padding(vertical = 8.dp))

                ConfigToggleItem(
                    title = strings.toggleTachoInWideTitle,
                    subtitle = strings.toggleTachoInWideSubtitle,
                    checked = config.showTachometerInWide,
                    onCheckedChange = { ConfigManager.updateConfig(config.copy(showTachometerInWide = it)) }
                )

                HorizontalDivider(color = Color(0xFF22283A), modifier = Modifier.padding(vertical = 8.dp))

                ConfigToggleItem(
                    title = strings.toggle12vTitle,
                    subtitle = strings.toggle12vSubtitle,
                    checked = config.show12vBattery,
                    onCheckedChange = { ConfigManager.updateConfig(config.copy(show12vBattery = it)) }
                )

                if (config.show12vBattery) {
                    ConfigToggleItem(
                        title = strings.toggle12vWattsTitle,
                        subtitle = strings.toggle12vWattsSubtitle,
                        checked = config.show12vWattsMode,
                        onCheckedChange = { ConfigManager.updateConfig(config.copy(show12vWattsMode = it)) }
                    )
                }

                HorizontalDivider(color = Color(0xFF22283A), modifier = Modifier.padding(vertical = 8.dp))

                ConfigToggleItem(
                    title = strings.toggleTorqueSplitTitle,
                    subtitle = strings.toggleTorqueSplitSubtitle,
                    checked = config.showTorqueSplit,
                    onCheckedChange = { ConfigManager.updateConfig(config.copy(showTorqueSplit = it)) }
                )

                HorizontalDivider(color = Color(0xFF22283A), modifier = Modifier.padding(vertical = 8.dp))

                ConfigToggleItem(
                    title = strings.toggleTempsTitle,
                    subtitle = strings.toggleTempsSubtitle,
                    checked = config.showTemperatures,
                    onCheckedChange = { ConfigManager.updateConfig(config.copy(showTemperatures = it)) }
                )

                HorizontalDivider(color = Color(0xFF22283A), modifier = Modifier.padding(vertical = 8.dp))

                ConfigToggleItem(
                    title = strings.toggleTripTitle,
                    subtitle = strings.toggleTripSubtitle,
                    checked = config.showTripStats,
                    onCheckedChange = { ConfigManager.updateConfig(config.copy(showTripStats = it)) }
                )

                HorizontalDivider(color = Color(0xFF22283A), modifier = Modifier.padding(vertical = 8.dp))

                ConfigToggleItem(
                    title = strings.toggleLogObdTitle,
                    subtitle = strings.toggleLogObdSubtitle,
                    checked = config.logObdData,
                    onCheckedChange = { ConfigManager.updateConfig(config.copy(logObdData = it)) }
                )
            }
        }
    }
}

/**
 * PODSTRONA: PODGLĄD TELEMETRII NA ŻYWO
 * Wszystkie parametry odczytywane na żywo z magistrali CAN / symulacji demo.
 */
@Composable
fun LiveTelemetryScreen(
    onBack: () -> Unit
) {
    val config by ConfigManager.configState.collectAsState()
    val strings = remember(config.appLanguage) { StringsManager.get(config.appLanguage) }
    val telemetry by ObdManager.telemetryState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // Górny pasek powrotu
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
            ) {
                Text(strings.btnBackToMenu, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(strings.telemetryTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Text(strings.telemetrySubtitle, fontSize = 12.sp, color = Color(0xFF8E99A4))
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10131D)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val boostStatus = if (telemetry.fuelInjectionBoostActive) strings.boostActive else strings.boostInactive
                val ssRaw = telemetry.startStopInhibitReason
                val ssStatus = if (ssRaw.isNotBlank()) "  |  ${ssRaw.lines().filter { it.isNotBlank() }.joinToString(" + ") { strings.translateReason(it.trim()) }}" else ""
                val modeStr = strings.translateMode(telemetry.hybridMode)
                Text("${strings.gearLabel}: ${telemetry.currentGear} (EV: ${telemetry.eGear})  |  ${strings.modeLabel}: $modeStr  |  $boostStatus$ssStatus", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("${strings.speedLabel}: ${telemetry.speedKmH.toInt()} km/h  |  ${strings.iceRpmLabel}: ${telemetry.iceRpm} RPM  |  ${strings.eMotorLabel}: ${Math.abs(telemetry.rearMotorRpm)} RPM", color = Color(0xFF8E99A4), fontSize = 13.sp)
                Text("${strings.powerOutputLabel}: +${telemetry.totalOutputPowerKw.toInt()} kW (ICE: ${telemetry.icePowerKw.toInt()} | ${strings.rearLabel}: ${telemetry.rearMotorPowerKw.toInt()} | HSG: ${telemetry.hsgPowerKw.toInt()})", color = Color(0xFFFF6D00), fontSize = 13.sp)
                Text("${strings.regenLabel}: -${telemetry.totalRegenPowerKw.toInt()} kW  |  ${strings.torqueSplitLabel}: ${telemetry.frontTorquePercent}% / ${telemetry.rearTorquePercent}%", color = Color(0xFF00E5FF), fontSize = 13.sp)
                val oilText = if (telemetry.oilTempC > -100) "${telemetry.oilTempC.toInt()}°C" else "--"
                val coolantText = if (telemetry.coolantTempC > -100) "${telemetry.coolantTempC.toInt()}°C" else "--"
                val atfText = if (telemetry.gearboxTempC > -100) "${telemetry.gearboxTempC.toInt()}°C" else "--"
                Text("${strings.coolantLabel}: $coolantText  |  ${strings.engineOilLabel}: $oilText  |  ${strings.gearboxAtfLabel}: $atfText", color = Color(0xFFB3CC23), fontSize = 13.sp)
                val soc48Str = if (telemetry.hvBatterySocPercent in 5.0..100.0) "${telemetry.hvBatterySocPercent.toInt()}% SoC" else "${telemetry.hsgLoadPercent.toInt()}% MHEV"
                val v48Val = if (telemetry.hvBatteryVoltage > 20.0) telemetry.hvBatteryVoltage else telemetry.hsgVoltage48v
                val v48Str = "${String.format(java.util.Locale.US, "%.1f", v48Val)}V ($soc48Str)"
                val soc12Str = if (telemetry.bat12vSocPercent > 0) " (${telemetry.bat12vSocPercent}% SoC)" else ""
                Text("48V: $v48Str  |  12V: ${String.format(java.util.Locale.US, "%.1f", telemetry.bat12vVoltage)}V$soc12Str • DCDC: +${telemetry.bat12vPowerWatts.toInt()}W (${telemetry.bat12vCurrent.toInt()}A)", color = Color(0xFF29B6F6), fontSize = 13.sp)
                Text("${strings.tripEvLabel}: ${telemetry.tripEvDistancePercent.toInt()}%  |  ${strings.tripRegenLabel}: ${String.format(java.util.Locale.US, "%.2f", telemetry.tripRegenKWh)} kWh  |  ${strings.tripSavedLabel}: +${String.format(java.util.Locale.US, "%.2f", telemetry.tripFuelSavedLiters)} L", color = Color(0xFFFFD740), fontSize = 13.sp)

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { ObdManager.resetTrip() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22283A)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.btnResetTrip, fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

/**
 * PODSTRONA: DIAGNOSTYKA I LOGI OBD
 * Podgląd surowego bufora zapytań i odpowiedzi CAN oraz zarządzanie logiem.
 */
@Composable
fun ObdDiagnosticsScreen(
    onBack: () -> Unit
) {
    val config by ConfigManager.configState.collectAsState()
    val strings = remember(config.appLanguage) { StringsManager.get(config.appLanguage) }
    val telemetry by ObdManager.telemetryState.collectAsState()

    var logLines by remember { mutableStateOf(emptyList<String>()) }
    var logSizeKb by remember { mutableStateOf(0L) }

    LaunchedEffect(config.logObdData, telemetry) {
        if (config.logObdData) {
            logLines = ObdLogger.getLastLines(25)
            logSizeKb = ObdLogger.getFileSizeKb()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // Górny pasek powrotu
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
            ) {
                Text(strings.btnBackToMenu, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(strings.diagTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Text(strings.diagSubtitle, fontSize = 12.sp, color = Color(0xFF8E99A4))
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (config.logObdData) strings.diagLogActive(logSizeKb) else strings.diagLogDisabled,
                    fontWeight = FontWeight.Bold,
                    color = if (config.logObdData) Color(0xFFB3CC23) else Color(0xFF8E99A4),
                    fontSize = 14.sp
                )
                Text(
                    text = strings.diagFileLabel(ObdLogger.getLogPath()),
                    fontSize = 11.sp,
                    color = Color(0xFF8E99A4),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            logLines = ObdLogger.getLastLines(25)
                            logSizeKb = ObdLogger.getFileSizeKb()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22283A)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(strings.btnRefreshLog, fontSize = 12.sp, color = Color.White)
                    }

                    OutlinedButton(
                        onClick = {
                            ObdLogger.clearLog()
                            logLines = emptyList()
                            logSizeKb = 0L
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                    ) {
                        Text(strings.btnClearLog, fontSize = 12.sp)
                    }
                }

                if (logLines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF07090E), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            logLines.forEach { line ->
                                Text(
                                    text = line,
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = if (line.contains("OK")) Color(0xFFB3CC23) else if (line.contains("BRAK") || line.contains("Błąd")) Color(0xFFFF5252) else Color(0xFFB0B0B0)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * PODSTRONA KALIBRACJI I SUWAKÓW UI:
 * Osobna, niezależna kalibracja dla Zegarów (Cluster) oraz dla Ekranu Środkowego (Infotainment: pełny i podzielony).
 */
@Composable
fun ClusterCalibrationScreen(
    onBack: () -> Unit
) {
    val config by ConfigManager.configState.collectAsState()
    val strings = remember(config.appLanguage) { StringsManager.get(config.appLanguage) }
    var targetDisplay by remember { mutableStateOf(0) } // 0 = Zegary (Cluster), 1 = Ekran środkowy (Infotainment)
    var selectedTab by remember { mutableStateOf(if (config.showTachometerInWide) 0 else 1) }
    var selectedInfoTab by remember { mutableStateOf(0) } // 0 = Pełny ekran, 1 = Podzielony ekran (YT Music)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // Górny pasek powrotu
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
            ) {
                Text(strings.btnBackToMenu, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(strings.calibTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Text(strings.calibSubtitle, fontSize = 12.sp, color = Color(0xFF8E99A4))
            }
        }

        // KARTA WYBORU DOCELOWEGO EKRANU (Cluster vs Infotainment)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(strings.calibTargetDisplayHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = targetDisplay == 0,
                        onClick = { targetDisplay = 0 },
                        label = { Text(strings.displayCluster, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00E5FF),
                            selectedLabelColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = targetDisplay == 1,
                        onClick = { targetDisplay = 1 },
                        label = { Text(strings.displayInfotainment, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFEC6528),
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (targetDisplay == 0) {
            // ==========================================
            // === KALIBRACJA DLA ZEGARÓW (CLUSTER)   ===
            // ==========================================

            // KARTA WYBORU EDYTOWANEGO UKŁADU
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(strings.calibLayoutHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0
                                ConfigManager.updateConfig(config.copy(showTachometerInWide = true))
                            },
                            label = { Text(strings.layoutFull, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFEC6528),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                ConfigManager.updateConfig(config.copy(showTachometerInWide = false))
                            },
                            label = { Text(strings.layoutCardsOnly, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFB3CC23),
                                selectedLabelColor = Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    val activeModeLabel = if (config.showTachometerInWide) strings.layoutFull else strings.layoutCardsOnly
                    Text(
                        text = strings.activeModeOnCluster(activeModeLabel),
                        fontSize = 11.sp,
                        color = Color(0xFF00E676)
                    )
                }
            }

            // WIZUALNY PODGLĄD PROPORCJI EKRANU (MINI SCHEMAT)
            Text(
                text = strings.previewLiveHeader,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8E99A4),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(85.dp)
                    .background(Color(0xFF050505), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF262626), RoundedCornerShape(12.dp))
                    .padding(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTab == 0) {
                        val leftOffset = config.fullLeftOffsetPercent.coerceIn(5f, 40f)
                        val tachoW = config.fullTachoWidthPercent.coerceIn(10f, 40f)
                        val rightMarg = config.fullRightMarginPercent.coerceIn(5f, 40f)
                        val cardsW = (100f - leftOffset - tachoW - rightMarg).coerceAtLeast(15f)

                        Box(
                            modifier = Modifier
                                .weight(leftOffset)
                                .fillMaxHeight()
                                .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF333333), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(strings.previewSpeedo(leftOffset.toInt()), color = Color(0xFF707070), fontSize = 9.sp, textAlign = TextAlign.Center)
                        }

                        Spacer(modifier = Modifier.width(3.dp))

                        Box(
                            modifier = Modifier
                                .weight(tachoW)
                                .fillMaxHeight()
                                .background(Color(0xFF2D160A), RoundedCornerShape(6.dp))
                                .border(1.5.dp, Color(0xFFEC6528), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(strings.previewTacho(tachoW.toInt()), color = Color(0xFFFF9E80), fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }

                        Spacer(modifier = Modifier.width(3.dp))

                        Box(
                            modifier = Modifier
                                .weight(cardsW)
                                .fillMaxHeight()
                                .background(Color(0xFF162408), RoundedCornerShape(6.dp))
                                .border(1.5.dp, Color(0xFFB3CC23), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(strings.previewCards, color = Color(0xFFE8F5E9), fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }

                        Spacer(modifier = Modifier.width(3.dp))

                        Box(
                            modifier = Modifier
                                .weight(rightMarg)
                                .fillMaxHeight()
                                .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF333333), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(strings.previewMargin(rightMarg.toInt()), color = Color(0xFF707070), fontSize = 9.sp, textAlign = TextAlign.Center)
                        }
                    } else {
                        val leftSpace = config.cardsOnlyLeftOffsetPercent.coerceIn(5f, 60f)
                        val rightMarg = config.cardsOnlyRightMarginPercent.coerceIn(5f, 40f)
                        val cardsW = (100f - leftSpace - rightMarg).coerceAtLeast(15f)

                        Box(
                            modifier = Modifier
                                .weight(leftSpace)
                                .fillMaxHeight()
                                .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF333333), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(strings.previewFreeSpaceSpeedo(leftSpace.toInt()), color = Color(0xFF707070), fontSize = 10.sp, textAlign = TextAlign.Center)
                        }

                        Spacer(modifier = Modifier.width(3.dp))

                        Box(
                            modifier = Modifier
                                .weight(cardsW)
                                .fillMaxHeight()
                                .background(Color(0xFF162408), RoundedCornerShape(6.dp))
                                .border(1.5.dp, Color(0xFFB3CC23), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(strings.previewCardsRight, color = Color(0xFFE8F5E9), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }

                        Spacer(modifier = Modifier.width(3.dp))

                        Box(
                            modifier = Modifier
                                .weight(rightMarg)
                                .fillMaxHeight()
                                .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF333333), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(strings.previewMargin(rightMarg.toInt()), color = Color(0xFF707070), fontSize = 9.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SUWAKI DLA WYBRANEGO TRYBU ZEGARÓW
            if (selectedTab == 0) {
                // === TRYB PEŁNY ===
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.fullModeSlidersHeader, fontWeight = FontWeight.Bold, color = Color(0xFFEC6528), fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(strings.tachoWidthSlider(config.fullTachoWidthPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.tachoWidthSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.fullTachoWidthPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(fullTachoWidthPercent = it)) },
                            valueRange = 10f..35f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.tachoLeftOffsetSlider(config.fullLeftOffsetPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.tachoLeftOffsetSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.fullLeftOffsetPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(fullLeftOffsetPercent = it)) },
                            valueRange = 10f..40f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.rightMarginSlider(config.fullRightMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.rightMarginSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.fullRightMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(fullRightMarginPercent = it)) },
                            valueRange = 5f..35f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.bottomMarginSlider(config.fullBottomMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.bottomMarginSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.fullBottomMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(fullBottomMarginPercent = it)) },
                            valueRange = 10f..35f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.topMarginSlider(config.fullTopMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.topMarginSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.fullTopMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(fullTopMarginPercent = it)) },
                            valueRange = 0f..20f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                ConfigManager.updateConfig(
                                    config.copy(
                                        fullLeftOffsetPercent = 24f,
                                        fullTachoWidthPercent = 18f,
                                        fullRightMarginPercent = 18f,
                                        fullBottomMarginPercent = 22f,
                                        fullTopMarginPercent = 1f
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEC6528))
                        ) {
                            Text(strings.btnRestoreDefaultsFull)
                        }
                    }
                }
            } else {
                // === TRYB TYLKO KAFELKI ===
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.cardsOnlySlidersHeader, fontWeight = FontWeight.Bold, color = Color(0xFFB3CC23), fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(strings.cardsOnlyLeftSpaceSlider(config.cardsOnlyLeftOffsetPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.cardsOnlyLeftSpaceSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.cardsOnlyLeftOffsetPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(cardsOnlyLeftOffsetPercent = it)) },
                            valueRange = 10f..50f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.rightMarginSlider(config.cardsOnlyRightMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.cardsOnlyRightMarginSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.cardsOnlyRightMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(cardsOnlyRightMarginPercent = it)) },
                            valueRange = 5f..35f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.bottomMarginSlider(config.cardsOnlyBottomMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.bottomMarginSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.cardsOnlyBottomMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(cardsOnlyBottomMarginPercent = it)) },
                            valueRange = 10f..35f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.topMarginSlider(config.cardsOnlyTopMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.topMarginSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.cardsOnlyTopMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(cardsOnlyTopMarginPercent = it)) },
                            valueRange = 0f..20f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                ConfigManager.updateConfig(
                                    config.copy(
                                        cardsOnlyLeftOffsetPercent = 25f,
                                        cardsOnlyRightMarginPercent = 18f,
                                        cardsOnlyBottomMarginPercent = 22f,
                                        cardsOnlyTopMarginPercent = 1f
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB3CC23))
                        ) {
                            Text(strings.btnRestoreDefaultsCardsOnly)
                        }
                    }
                }
            }

            // KARTA OGÓLNA: SKALA CZCIONEK I KOLORY
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.fontSizeAndColorsHeader, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(strings.uiScaleSlider((config.uiScale * 100).toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                    Slider(
                        value = config.uiScale,
                        onValueChange = { ConfigManager.updateConfig(config.copy(uiScale = it)) },
                        valueRange = 0.85f..1.55f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = Color(0xFF22283A), modifier = Modifier.padding(vertical = 10.dp))

                    Text(strings.clusterBgColorTitle, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                    Text(strings.clusterBgColorDesc, fontSize = 11.sp, color = Color(0xFF8E99A4), modifier = Modifier.padding(bottom = 6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val bgPresets = listOf(
                            "#000000" to strings.presetPureBlack,
                            "#0B0B0B" to strings.presetGraphite,
                            "#121212" to strings.presetAnthracite,
                            "#080C14" to strings.presetNavy
                        )
                        bgPresets.forEach { (hex, label) ->
                            val isSelected = config.clusterBgColorHex.equals(hex, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = { ConfigManager.updateConfig(config.copy(clusterBgColorHex = hex)) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFB3CC23),
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(strings.cardColorTitle, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                    Text(strings.cardColorDesc, fontSize = 11.sp, color = Color(0xFF8E99A4), modifier = Modifier.padding(bottom = 6.dp))

                    val curCardHex = config.clusterCardColorHex.removePrefix("#")
                    val curColorInt = curCardHex.toLongOrNull(16)?.toInt() ?: 0x171717
                    val curR = (curColorInt shr 16) and 0xFF
                    val curG = (curColorInt shr 8) and 0xFF
                    val curB = curColorInt and 0xFF

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .background(Color(0xFF0F121C), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF2B3448), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(curR, curG, curB), RoundedCornerShape(8.dp))
                                .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Kolor: #${String.format(java.util.Locale.US, "%02X%02X%02X", curR, curG, curB)}",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "R:$curR  G:$curG  B:$curB",
                                fontSize = 12.sp,
                                color = Color(0xFF8E99A4)
                            )
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(strings.redR, fontSize = 12.sp, color = Color(0xFFFF5252), fontWeight = FontWeight.SemiBold)
                        Text("$curR", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = curR.toFloat(),
                        onValueChange = { newR ->
                            val hex = String.format(java.util.Locale.US, "#%02X%02X%02X", newR.toInt().coerceIn(0, 255), curG, curB)
                            ConfigManager.updateConfig(config.copy(clusterCardColorHex = hex))
                        },
                        valueRange = 0f..255f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(strings.greenG, fontSize = 12.sp, color = Color(0xFF00E676), fontWeight = FontWeight.SemiBold)
                        Text("$curG", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = curG.toFloat(),
                        onValueChange = { newG ->
                            val hex = String.format(java.util.Locale.US, "#%02X%02X%02X", curR, newG.toInt().coerceIn(0, 255), curB)
                            ConfigManager.updateConfig(config.copy(clusterCardColorHex = hex))
                        },
                        valueRange = 0f..255f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(strings.blueB, fontSize = 12.sp, color = Color(0xFF29B6F6), fontWeight = FontWeight.SemiBold)
                        Text("$curB", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = curB.toFloat(),
                        onValueChange = { newB ->
                            val hex = String.format(java.util.Locale.US, "#%02X%02X%02X", curR, curG, newB.toInt().coerceIn(0, 255))
                            ConfigManager.updateConfig(config.copy(clusterCardColorHex = hex))
                        },
                        valueRange = 0f..255f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(strings.quickPresets, fontSize = 11.sp, color = Color(0xFF8E99A4))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val cardPresets = listOf(
                            "#171717" to strings.presetGraphiteOem,
                            "#000000" to strings.presetBlack,
                            "#0E1420" to strings.presetNavyBlue,
                            "#141A10" to strings.presetOlive,
                            "#222222" to strings.presetLight
                        )
                        cardPresets.forEach { (hex, label) ->
                            val isSelected = config.clusterCardColorHex.equals(hex, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = { ConfigManager.updateConfig(config.copy(clusterCardColorHex = hex)) },
                                label = { Text(label, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFEC6528),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        } else {
            // ===============================================
            // === KALIBRACJA DLA INFOTAINMENT (ŚRODEK)   ===
            // ===============================================

            // KARTA WYBORU TRYBU INFOTAINMENT: Pełny vs Podzielony
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(strings.infoDisplayModeHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = config.infoViewMode == "TACHO" || (config.infoViewMode == "AUTO" && selectedInfoTab == 0),
                            onClick = {
                                selectedInfoTab = 0
                                ConfigManager.updateConfig(config.copy(infoViewMode = "TACHO"))
                            },
                            label = { Text(strings.infoWithTacho, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFEC6528),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = config.infoViewMode == "CARDS" || (config.infoViewMode != "TACHO" && config.infoViewMode != "AUTO" && selectedInfoTab == 1),
                            onClick = {
                                selectedInfoTab = 1
                                ConfigManager.updateConfig(config.copy(infoViewMode = "CARDS"))
                            },
                            label = { Text(strings.infoCardsOnly, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFB3CC23),
                                selectedLabelColor = Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = config.infoViewMode == "AUTO",
                            onClick = {
                                ConfigManager.updateConfig(config.copy(infoViewMode = "AUTO"))
                            },
                            label = { Text(strings.infoAutoCoolwalk, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF),
                                selectedLabelColor = Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val activeModeDesc = when (config.infoViewMode) {
                        "TACHO" -> strings.infoDescTacho
                        "CARDS" -> strings.infoDescCards
                        else -> strings.infoDescAuto
                    }
                    Text(
                        text = activeModeDesc,
                        fontSize = 11.sp,
                        color = Color(0xFF8E99A4)
                    )
                }
            }

            // WIZUALNY PODGLĄD PROPORCJI DLA INFOTAINMENT (MINI SCHEMAT)
            Text(
                text = strings.infoPreviewHeader,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8E99A4),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(Color(0xFF050505), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF262626), RoundedCornerShape(12.dp))
                    .padding(6.dp)
            ) {
                if (selectedInfoTab == 0) {
                    // TRYB PEŁNY (100% szerokości ekranu Infotainment)
                    val leftOffset = config.infoFullLeftOffsetPercent.coerceIn(0f, 30f)
                    val tachoW = config.infoFullTachoWidthPercent.coerceIn(10f, 40f)
                    val rightMarg = config.infoFullRightMarginPercent.coerceIn(0f, 30f)
                    val cardsW = (100f - leftOffset - tachoW - rightMarg).coerceAtLeast(15f)

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (leftOffset > 0.5f) {
                            Box(
                                modifier = Modifier
                                    .weight(leftOffset)
                                    .fillMaxHeight()
                                    .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Marg.\n(${leftOffset.toInt()}%)", color = Color(0xFF707070), fontSize = 8.sp, textAlign = TextAlign.Center)
                            }
                            Spacer(modifier = Modifier.width(3.dp))
                        }

                        Box(
                            modifier = Modifier
                                .weight(tachoW)
                                .fillMaxHeight()
                                .background(Color(0xFF2D160A), RoundedCornerShape(6.dp))
                                .border(1.5.dp, Color(0xFFEC6528), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Obrotomierz\n+ Bieg (${tachoW.toInt()}%)", color = Color(0xFFFF9E80), fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }

                        Spacer(modifier = Modifier.width(3.dp))

                        Box(
                            modifier = Modifier
                                .weight(cardsW)
                                .fillMaxHeight()
                                .background(Color(0xFF162408), RoundedCornerShape(6.dp))
                                .border(1.5.dp, Color(0xFFB3CC23), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(strings.calibPreviewCardsInfoFull, color = Color(0xFFE8F5E9), fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }

                        if (rightMarg > 0.5f) {
                            Spacer(modifier = Modifier.width(3.dp))
                            Box(
                                modifier = Modifier
                                    .weight(rightMarg)
                                    .fillMaxHeight()
                                    .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Marg.\n(${rightMarg.toInt()}%)", color = Color(0xFF707070), fontSize = 8.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    // TRYB PODZIELONY COOLWALK (np. ~58% nasza aplikacja + ~42% YT Music)
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // LEWA STRONA: NASZA APLIKACJA
                        val splitLeftMarg = config.infoSplitLeftOffsetPercent.coerceIn(0f, 30f)
                        val splitRightMarg = config.infoSplitRightMarginPercent.coerceIn(0f, 30f)
                        val splitCardsW = (100f - splitLeftMarg - splitRightMarg).coerceAtLeast(20f)

                        Box(
                            modifier = Modifier
                                .weight(58f)
                                .fillMaxHeight()
                                .background(Color(0xFF0F151E), RoundedCornerShape(8.dp))
                                .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(8.dp))
                                .padding(4.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    "KOKPIT TELEMETRII (PODZIELONY COOLWALK)",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E5FF),
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (splitLeftMarg > 0.5f) {
                                        Box(
                                            modifier = Modifier
                                                .weight(splitLeftMarg)
                                                .fillMaxHeight()
                                                .background(Color(0xFF080B10), RoundedCornerShape(4.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("${splitLeftMarg.toInt()}%", color = Color(0xFF666666), fontSize = 7.sp)
                                        }
                                        Spacer(modifier = Modifier.width(2.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(splitCardsW)
                                            .fillMaxHeight()
                                            .background(Color(0xFF162408), RoundedCornerShape(6.dp))
                                            .border(1.dp, Color(0xFFB3CC23), RoundedCornerShape(6.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(strings.calibPreviewCardsInfoSplit, color = Color(0xFFE8F5E9), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                    }

                                    if (splitRightMarg > 0.5f) {
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .weight(splitRightMarg)
                                                .fillMaxHeight()
                                                .background(Color(0xFF080B10), RoundedCornerShape(4.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("${splitRightMarg.toInt()}%", color = Color(0xFF666666), fontSize = 7.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // PRAWA STRONA: DRUGA APLIKACJA ANDROID AUTO (YT Music / Mapy)
                        Box(
                            modifier = Modifier
                                .weight(42f)
                                .fillMaxHeight()
                                .background(Color(0xFF18151E), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF3B2F4C), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🎵 YT Music / Mapy", color = Color(0xFFD1B3FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Druga aplikacja Android Auto", color = Color(0xFF8E80A0), fontSize = 8.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedInfoTab == 0) {
                // === SUWAKI DLA PEŁNEGO EKRANU INFOTAINMENT ===
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.infoSlidersFullHeader, fontWeight = FontWeight.Bold, color = Color(0xFFEC6528), fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(strings.infoLeftMargSlider(config.infoFullLeftOffsetPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.infoLeftMargSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.infoFullLeftOffsetPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(infoFullLeftOffsetPercent = it)) },
                            valueRange = 0f..30f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.infoTachoWidthSlider(config.infoFullTachoWidthPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.infoTachoWidthSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.infoFullTachoWidthPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(infoFullTachoWidthPercent = it)) },
                            valueRange = 10f..40f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.infoRightMargSlider(config.infoFullRightMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.infoRightMargSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.infoFullRightMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(infoFullRightMarginPercent = it)) },
                            valueRange = 0f..30f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.infoBottomMargSlider(config.infoFullBottomMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.infoBottomMargSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.infoFullBottomMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(infoFullBottomMarginPercent = it)) },
                            valueRange = 0f..30f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.infoTopMargSlider(config.infoFullTopMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.infoTopMargSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.infoFullTopMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(infoFullTopMarginPercent = it)) },
                            valueRange = 0f..25f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                ConfigManager.updateConfig(
                                    config.copy(
                                        infoFullLeftOffsetPercent = 6f,
                                        infoFullTachoWidthPercent = 22f,
                                        infoFullRightMarginPercent = 6f,
                                        infoFullBottomMarginPercent = 6f,
                                        infoFullTopMarginPercent = 4f
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEC6528))
                        ) {
                            Text(strings.btnRestoreDefaultsInfoFull)
                        }
                    }
                }
            } else {
                // === SUWAKI DLA PODZIELONEGO EKRANU INFOTAINMENT ===
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.infoSlidersSplitHeader, fontWeight = FontWeight.Bold, color = Color(0xFFB3CC23), fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(strings.infoLeftMargSlider(config.infoSplitLeftOffsetPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.infoSplitLeftDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.infoSplitLeftOffsetPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(infoSplitLeftOffsetPercent = it)) },
                            valueRange = 0f..30f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.infoRightMargSlider(config.infoSplitRightMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.infoSplitRightDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.infoSplitRightMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(infoSplitRightMarginPercent = it)) },
                            valueRange = 0f..30f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.infoBottomMargSlider(config.infoSplitBottomMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.infoBottomMargSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.infoSplitBottomMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(infoSplitBottomMarginPercent = it)) },
                            valueRange = 0f..30f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(strings.infoTopMargSlider(config.infoSplitTopMarginPercent.toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(strings.infoTopMargSliderDesc, fontSize = 11.sp, color = Color(0xFF8E99A4))
                        Slider(
                            value = config.infoSplitTopMarginPercent,
                            onValueChange = { ConfigManager.updateConfig(config.copy(infoSplitTopMarginPercent = it)) },
                            valueRange = 0f..25f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                ConfigManager.updateConfig(
                                    config.copy(
                                        infoSplitLeftOffsetPercent = 5f,
                                        infoSplitRightMarginPercent = 5f,
                                        infoSplitBottomMarginPercent = 6f,
                                        infoSplitTopMarginPercent = 4f
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB3CC23))
                        ) {
                            Text(strings.btnRestoreDefaultsInfoSplit)
                        }
                    }
                }
            }

            // KARTA OGÓLNA: SKALA DLA INFOTAINMENT
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.infoScaleHeader, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(strings.infoScaleSlider((config.infoUiScale * 100).toInt()), fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                    Slider(
                        value = config.infoUiScale,
                        onValueChange = { ConfigManager.updateConfig(config.copy(infoUiScale = it)) },
                        valueRange = 0.75f..1.70f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { ConfigManager.updateConfig(config.copy(infoUiScale = 1.10f)) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                    ) {
                        Text(strings.btnRestoreDefaultsInfoScale)
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothDevicePickerDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    strings: AppStrings = StringsManager.get(ConfigManager.configState.value.appLanguage)
) {
    val btAdapter = BluetoothAdapter.getDefaultAdapter()
    val pairedDevices = remember {
        btAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(strings.btDialogTitle, fontWeight = FontWeight.Bold, color = Color.White)
        },
        text = {
            if (pairedDevices.isEmpty()) {
                Text(
                    strings.btDialogNoDevices,
                    color = Color(0xFF8E99A4),
                    fontSize = 14.sp
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    items(pairedDevices) { device ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2436)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onDeviceSelected(device) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = device.name ?: strings.btUnknownDevice,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = device.address,
                                    color = Color(0xFF00E5FF),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.btDialogCancel, color = Color(0xFF00B0FF))
            }
        },
        containerColor = Color(0xFF141824)
    )
}

@Composable
fun ConfigToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, color = Color(0xFF8E99A4))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
