package com.example.muzerion

import android.Manifest
import android.app.*
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SharingStatus(
    val starting: Boolean = false,
    val running: Boolean = false,
    val key: String? = null,
    val error: String? = null
)

class MuzerionSharingService : Service() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }
    private val languageChanged: ()->Unit = {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,tr(R.string.msg_06e58c86e6),NotificationManager.IMPORTANCE_LOW))
        if(mutableStatus.value.running || mutableStatus.value.starting) manager.notify(NOTIFICATION_ID,notification())
    }
    companion object {
        private const val CHANNEL = "muzerion_pc_sharing"
        private const val NOTIFICATION_ID = 2002
        private const val STOP = "com.example.muzerion.STOP_SHARING"
        private val mutableStatus = MutableStateFlow(SharingStatus())
        val status = mutableStatus.asStateFlow()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var server: MuzerionWebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        AppLanguage.listeners.add(languageChanged)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr(R.string.msg_06e58c86e6), NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (mutableStatus.value.starting || mutableStatus.value.running) return START_NOT_STICKY
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            mutableStatus.value = SharingStatus(error = tr(R.string.msg_4294c6f1ff))
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ) else startForeground(NOTIFICATION_ID, notification)
            acquireLocks()
        } catch (failure: Exception) {
            mutableStatus.value = SharingStatus(error = tr(R.string.msg_f9c28f5922, failure.message))
            stopSelf()
            return START_NOT_STICKY
        }
        mutableStatus.value = SharingStatus(starting = true)
        scope.launch {
            try {
                val active = withContext(Dispatchers.IO) {
                    MuzerionWebServer(applicationContext).also {
                        server = it
                        it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                    }
                }
                ensureActive()
                mutableStatus.value = SharingStatus(running = true, key = active.accessKey)
            } catch (cancelled: CancellationException) {
                server?.stop()
                throw cancelled
            } catch (failure: Exception) {
                mutableStatus.value = SharingStatus(error = failure.message ?: tr(R.string.msg_3899758874))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MuzerionSharingService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(tr(R.string.msg_15fa1bcda6))
            .setContentText(tr(R.string.msg_922bec0c3c))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, tr(R.string.msg_d4f447c130), stop).build()).build()
    }
    @Suppress("DEPRECATION", "WakelockTimeout")
    private fun acquireLocks() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muzerion:PcSharing").apply {
            setReferenceCounted(false); acquire()
        }
        if (Build.VERSION.SDK_INT < 34) {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Muzerion:WifiSharing").apply {
                setReferenceCounted(false); acquire()
            }
        }
    }
    override fun onDestroy() {
        AppLanguage.listeners.remove(languageChanged)
        scope.cancel()
        server?.stop(); server = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock = null; wakeLock = null
        mutableStatus.value = SharingStatus(error = mutableStatus.value.error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
fun PcSharingPanel() {
    val context = LocalContext.current
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val status by MuzerionSharingService.status.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pendingStart by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var powerSaving by remember { mutableStateOf(power.isPowerSaveMode) }
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().firstOrNull()
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
                powerSaving = power.isPowerSaveMode
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    fun startService() {
        try {
            error = null
            ContextCompat.startForegroundService(context, Intent(context, MuzerionSharingService::class.java))
        } catch (failure: Exception) { error = failure.message ?: tr(R.string.msg_f13b597ddd) }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startService() }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startService()
    }
    val network = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) requestNotifications() else error = tr(R.string.msg_9608e80b6c)
    }
    fun requestNetwork() {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context,
                "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            network.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else requestNotifications()
    }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        if (pendingStart) {
            pendingStart = false
            if (unrestricted) requestNetwork()
            else error = tr(R.string.msg_6e4954413c)
        }
    }
    fun requestBattery() {
        try {
            battery.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")))
        } catch (_: Exception) {
            battery.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
    Surface(color = MuzerionStyle.color(0xFF24202F), shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.msg_15fa1bcda6), Modifier.weight(1f), fontSize = 14.sp)
                Text(if (status.starting) tr(R.string.msg_a08934854c) else if (status.running) tr(R.string.msg_e6156da328) else tr(R.string.msg_6eb7540d7c),
                    color = if (status.running) MuzerionStyle.color(0xFFB9A0FF) else MuzerionStyle.color(0xFF96939F), fontSize = 12.sp)
                Spacer(Modifier.width(16.dp))
                Text(if (expanded) "⌃" else "⌄", color = MuzerionStyle.color(0xFFB9A0FF), fontSize = 22.sp)
            }
            if (expanded) {
                Text(if (unrestricted) tr(R.string.msg_8553494683) else tr(R.string.msg_2071268d2d),
                    color = if (unrestricted) MuzerionStyle.color(0xFFB9A0FF) else MuzerionStyle.color(0xFFFFB4AB), fontSize = 12.sp)
                TextButton(enabled = !status.starting, onClick = {
                    if (status.running) context.stopService(Intent(context, MuzerionSharingService::class.java))
                    else {
                        error = null
                        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
                        if (unrestricted) requestNetwork() else { pendingStart = true; requestBattery() }
                    }
                }) { Text(if (status.running) tr(R.string.msg_d4f447c130) else if (unrestricted) tr(R.string.msg_124298ec71) else tr(R.string.msg_f95e0e9f6f)) }
                PcQrPairingButton(accessKey = status.key)
                if (!unrestricted) TextButton(onClick = { pendingStart = false; requestBattery() }) { Text(tr(R.string.msg_3bf39e9711)) }
                TextButton(onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))) }
                    catch (_: Exception) { error = tr(R.string.msg_38450d04b4) }
                }) { Text(tr(R.string.msg_85df8f7942)) }
                if (powerSaving) Text(tr(R.string.msg_ca07a5d907), color = MuzerionStyle.color(0xFFFFB4AB), fontSize = 12.sp)

                (error ?: status.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        }
    }
}