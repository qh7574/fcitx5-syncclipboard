package org.fcitx.fcitx5.android.plugin.clipboard_sync.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClipboardData(
    @SerialName("Type")
    val type: String = "Text",
    
    @SerialName("Clipboard")
    val content: String,
    
    @SerialName("File")
    val file: String = ""
)
