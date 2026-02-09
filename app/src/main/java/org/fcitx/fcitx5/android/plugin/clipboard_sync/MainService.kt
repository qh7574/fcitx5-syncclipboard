package org.fcitx.fcitx5.android.plugin.clipboard_sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.common.FcitxPluginService
import org.fcitx.fcitx5.android.common.ipc.FcitxRemoteConnection
import org.fcitx.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fcitx.fcitx5.android.common.ipc.bindFcitxRemoteService
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient

class MainService : FcitxPluginService() {

    companion object {
        private const val TAG = "FcitxClipboardSync"
        private const val DEFAULT_INTERVAL = 3 // seconds
    }

    private lateinit var connection: FcitxRemoteConnection
    private lateinit var prefs: SharedPreferences
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastETag: String? = null
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off, stopping sync")
                    stopPeriodicSync()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen on, restarting sync")
                    startPeriodicSync()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    Log.d(TAG, "Power save mode changed, restarting sync")
                    startPeriodicSync()
                }
            }
        }
    }

    // Cache to avoid circular updates (Pull -> Local -> Push -> Loop)
    private var lastLocalContent: String? = null
    private var lastRemoteContent: String? = null
    private var lastRemoteHash: String = ""

    private val transformer = object : IClipboardEntryTransformer.Stub() {
        override fun getPriority(): Int = 100

        override fun transform(clipboardText: String): String {
            // This is called when user copies text locally
            if (clipboardText == lastRemoteContent) {
                // If this change matches what we just pulled, ignore it (don't push back)
                return clipboardText
            }

            if (clipboardText != lastLocalContent) {
                lastLocalContent = clipboardText
                Log.d(TAG, "[Push] Detected local change, triggering upload")
                uploadToCloud(clipboardText)
            }
            return clipboardText
        }

        override fun getDescription(): String = "SyncClipboard"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MainService onCreate")
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun start() {
        Log.d(TAG, "MainService start")
        connection = bindFcitxRemoteService(BuildConfig.MAIN_APPLICATION_ID,
            onDisconnect = {
                Log.d(TAG, "Disconnected from Fcitx")
                stopPeriodicSync()
            },
            onConnected = { service ->
                Log.d(TAG, "Connected to Fcitx")
                try {
                    service.registerClipboardEntryTransformer(transformer)
                    startPeriodicSync()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register transformer", e)
                }
            }
        )
        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun stop() {
        Log.d(TAG, "MainService stop")
        runCatching { unregisterReceiver(screenReceiver) }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        stopPeriodicSync()
        runCatching {
            connection.remoteService?.unregisterClipboardEntryTransformer(transformer)
        }
        unbindService(connection)
        scope.cancel()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "quick_sync" || key == "sync_interval") {
            Log.d(TAG, "Preference changed: $key, restarting sync")
            startPeriodicSync()
        }
    }

    private fun uploadToCloud(text: String) {
        if (!prefs.getBoolean("quick_sync", true)) {
            Log.d(TAG, "[Push] Sync disabled, skipping upload")
            return
        }
        val url = prefs.getString("server_address", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (url.isBlank()) return
        
        scope.launch {
            try {
                SyncClient.putClipboard(this@MainService, url, user, pass, text)
            } catch (e: Exception) {
                Log.e(TAG, "[Push] Failed to upload clipboard", e)
            }
        }
    }

    private fun startPeriodicSync() {
        stopPeriodicSync()
        
        val quickSync = prefs.getBoolean("quick_sync", true)
        if (!quickSync) {
            Log.d(TAG, "[Pull] Quick sync disabled, stopping background polling")
            return
        }
        
        Log.d(TAG, "[Pull] Starting periodic sync")
        syncJob = scope.launch {
            while (isActive) {
                try {
                    val interval = prefs.getString("sync_interval", "3")?.toLongOrNull() ?: 3L
                    var safeInterval = interval.coerceIn(1, 60)
                    
                    if (powerManager.isPowerSaveMode) {
                        safeInterval = 60L
                    }
                    
                    checkRemoteClipboard()
                    
                    delay(safeInterval * 1000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[Pull] Loop error", e)
                    delay(5000) // Retry delay on error
                }
            }
        }
    }

    private fun stopPeriodicSync() {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun checkRemoteClipboard() {
        val url = prefs.getString("server_address", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (url.isBlank()) return

        val downloadPath = prefs.getString("download_path", null)
        val downloadUri = if (downloadPath != null) Uri.parse(downloadPath) else null

        try {
            // 1. Fetch metadata (JSON) only
            val (partialData, newETag) = SyncClient.fetchClipboardJson(url, user, pass, lastETag)
            
            if (newETag != null) {
                lastETag = newETag
            }
            
            if (partialData == null) {
                // 304 Not Modified
                return
            }

            // 2. Check if hash matches last known remote hash to avoid repeated downloads
            if (partialData.hash.isNotEmpty() && partialData.hash == lastRemoteHash) {
                return
            }

            // 3. Download full details (files) if changed
            val data = SyncClient.downloadDetails(this, url, user, pass, partialData, downloadUri)

            // 4. Update hash cache
            if (data.hash.isNotEmpty()) {
                lastRemoteHash = data.hash
            }

            val remoteText = data.text
            Log.d(TAG, "[Pull] Processed data: type=${data.type}, text=$remoteText")
            
            if (remoteText.isNotEmpty() && remoteText != lastLocalContent && remoteText != lastRemoteContent) {
                Log.d(TAG, "[Pull] Remote content changed, updating local")
                lastRemoteContent = remoteText
                lastLocalContent = remoteText // Update local cache to prevent echo
                
                withContext(Dispatchers.Main) {
                    if (data.type == "Text") {
                        updateSystemClipboard(remoteText)
                    } else {
                        updateSystemClipboardWithUri(Uri.parse(remoteText))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Error checking remote clipboard", e)
        }
    }

    private fun updateSystemClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SyncClipboard", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "[Pull] System clipboard updated (Text)")
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to update system clipboard", e)
        }
    }

    private fun updateSystemClipboardWithUri(uri: Uri) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(contentResolver, "SyncClipboard", uri)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "[Pull] System clipboard updated with URI: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to update system clipboard with URI", e)
        }
    }
}
