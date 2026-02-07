package org.fcitx.fcitx5.android.plugin.clipboard_sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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

    // Cache to avoid circular updates (Pull -> Local -> Push -> Loop)
    private var lastLocalContent: String? = null
    private var lastRemoteContent: String? = null

    private val transformer = object : IClipboardEntryTransformer.Stub() {
        override fun getPriority(): Int = 100

        override fun transform(clipboardText: String): String {
            // This is called when user copies text locally
            if (clipboardText == lastRemoteContent) {
                // If this change matches what we just pulled, ignore it (don't push back)
                // Log.d(TAG, "[Push] Ignored circular update")
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
        val url = prefs.getString("server_address", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (url.isBlank()) return
        
        scope.launch {
            try {
                SyncClient.putClipboard(url, user, pass, text)
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
                    val safeInterval = interval.coerceIn(1, 60)
                    
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

        try {
            val remoteText = SyncClient.getClipboard(url, user, pass)
            
            if (remoteText.isNotEmpty() && remoteText != lastLocalContent && remoteText != lastRemoteContent) {
                Log.d(TAG, "[Pull] Remote content changed, updating local")
                lastRemoteContent = remoteText
                lastLocalContent = remoteText // Update local cache to prevent echo
                
                // Update System Clipboard
                // Since we are a service, we can try to update clipboard
                // But typically Fcitx plugin updates clipboard via return value of transform()
                // However, transform() is passive.
                // To actively update clipboard, we might need to use Android ClipboardManager
                // Note: Background services have restrictions on accessing clipboard on Android 10+
                // But since this is an IME plugin, or if the app is in foreground (Settings), it might work.
                // Fcitx5 main app usually handles clipboard, but here we want to push TO fcitx or system.
                // Since we don't have an API in IFcitxRemoteService to "setClipboard", we use system API.
                
                withContext(Dispatchers.Main) {
                    updateSystemClipboard(remoteText)
                }
            }
        } catch (e: Exception) {
            // Logged in SyncClient
        }
    }

    private fun updateSystemClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SyncClipboard", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "[Pull] System clipboard updated")
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to update system clipboard", e)
        }
    }
}
