package com.wifi.connector.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.wifi.connector.app.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var wifiManager: WifiManager
    private val scanResults = mutableStateListOf<ScanResult>()
    private var isScanning by mutableStateOf(false)

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                updateResults()
            }
            isScanning = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        enableEdgeToEdge()
        setContent {
            Theme {
                WifiConnectorScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(wifiScanReceiver)
    }

    private fun updateResults() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            scanResults.clear()
            scanResults.addAll(wifiManager.scanResults)
        }
    }

    private fun startScan() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1)
            return
        }

        Toast.makeText(this, "Memulai pencarian sinyal...", Toast.LENGTH_SHORT).show()

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
            }
        } catch (e: Exception) {
            // Abaikan
        }

        isScanning = true
        val success = wifiManager.startScan()
        if (!success) {
            isScanning = false
            Toast.makeText(this, "Gagal memindai: Hardware tidak merespons", Toast.LENGTH_LONG).show()
            updateResults()
        }
    }

    private fun connectToWifi(ssid: String, password: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val suggestion = android.net.wifi.WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .apply {
                    if (password.isNotEmpty()) {
                        setWpa2Passphrase(password)
                    }
                }
                .build()

            val suggestionsList = listOf(suggestion)
            val status = wifiManager.addNetworkSuggestions(suggestionsList)

            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Saran jaringan '$ssid' ditambahkan. Sistem akan mencoba menyambung. Tunggu beberapa saat...",
                        Toast.LENGTH_LONG
                    ).show()
                }
                // Pantau koneksi untuk memastikan internet aktif
                monitorConnection(ssid)
            } else {
                // Fallback ke NetworkSpecifier jika Suggestion ditolak sistem
                requestDirectNetwork(ssid, password)
            }
        } else {
            // HP Lama (Android < 10)
            val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (password.isNotEmpty()) {
                    preSharedKey = "\"$password\""
                } else {
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                }
            }
            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId == -1) {
                Toast.makeText(this, "Gagal menyimpan konfigurasi $ssid. Cek password.", Toast.LENGTH_SHORT).show()
            } else {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
                Toast.makeText(this, "Menyambung ke $ssid (Tersimpan)...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestDirectNetwork(ssid: String, password: String) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply {
                if (password.isNotEmpty()) {
                    setWpa2Passphrase(password)
                }
            }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // KRUSIAL: Ikat proses ke network ini agar YouTube/Google bisa pakai internetnya
                connectivityManager.bindProcessToNetwork(network)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "TERHUBUNG: $ssid siap digunakan untuk internet!", Toast.LENGTH_LONG).show()
                }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Gagal terhubung ke $ssid. Cek password atau jangkauan.", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun monitorConnection(targetSsid: String) {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                // Jika ini adalah wifi yang kita mau, ikat prosesnya
                connectivityManager.bindProcessToNetwork(network)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Internet Aktif via $targetSsid!", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun WifiConnectorScreen() {
        var showDialog by remember { mutableStateOf(false) }
        var selectedSsid by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        val isWifiEnabled = remember { mutableStateOf(wifiManager.isWifiEnabled) }

        // Refresh wifi status periodically
        LaunchedEffect(Unit) {
            while (true) {
                isWifiEnabled.value = wifiManager.isWifiEnabled
                delay(2000)
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("WifiConnector", fontWeight = FontWeight.ExtraBold) },
                    actions = {
                        IconButton(onClick = { startScan() }, enabled = !isScanning) {
                            if (isScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            ) {
                // Wifi Status Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isWifiEnabled.value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isWifiEnabled.value) Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = if (isWifiEnabled.value) "Wi-Fi Aktif" else "Wi-Fi Mati",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isScanning) "Sedang mencari sinyal..." else "Siap memindai",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Info Lokasi (Sangat Penting untuk Android 10+)
                Text(
                    text = "⚠️ Pastikan Lokasi/GPS aktif agar Wi-Fi terdeteksi.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                if (scanResults.isEmpty()) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Icon(
                                Icons.Default.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No networks found",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                "Make sure location is on and tap scan.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { startScan() }) {
                                Text("Search Networks")
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Available Networks (${scanResults.size})",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(scanResults) { result ->
                            WifiItem(result) {
                                val isOpen = result.capabilities.contains("OPEN") || 
                                             (!result.capabilities.contains("WPA") && !result.capabilities.contains("WEP"))
                                
                                selectedSsid = result.SSID
                                if (isOpen) {
                                    connectToWifi(selectedSsid, "")
                                } else {
                                    showDialog = true
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("Connect to $selectedSsid") },
                    text = {
                        Column {
                            Text("This will prompt a system connection dialog.")
                            Spacer(modifier = Modifier.height(16.dp))
                            TextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Wifi Password") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            connectToWifi(selectedSsid, password)
                            showDialog = false
                            password = ""
                        }) {
                            Text("Connect")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun WifiItem(result: ScanResult, onClick: () -> Unit) {
        val context = LocalContext.current
        val isSaved = remember(result.SSID) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                wifiManager.configuredNetworks?.any { it.SSID == "\"${result.SSID}\"" } == true
            } else false
        }

        val signalIcon = when {
            result.level > -50 -> Icons.Default.Wifi
            result.level > -70 -> Icons.Default.Wifi
            else -> Icons.Default.Wifi
        }
        val signalColor = when {
            result.level > -50 -> MaterialTheme.colorScheme.primary
            result.level > -70 -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.tertiary
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Icon(signalIcon, contentDescription = null, tint = signalColor, modifier = Modifier.size(32.dp))
                if (isSaved) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier.size(12.dp).offset(x = 4.dp, y = 4.dp)
                    ) {}
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (result.SSID.isEmpty()) "[Hidden Network]" else result.SSID,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (isSaved) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                "Tersimpan",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Text(
                    text = result.capabilities,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${result.level} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
