package com.mjaskols.tvhome

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper

data class InputEntry(
    val id: String,
    val label: String,
    /** Label of the physical port when this input is an HDMI-CEC device behind it (e.g. Apple TV on "HDMI 3/ARC"). */
    val parentLabel: String?,
    val state: Int,
) {
    val isCecDevice get() = parentLabel != null
    val isConnected get() = state != TvInputManager.INPUT_STATE_DISCONNECTED
}

/** Thin wrapper over [TvInputManager] limited to external (passthrough) inputs — the tuner is not used. */
class TvInputs(private val context: Context) {
    private val manager = context.getSystemService(TvInputManager::class.java)

    fun list(): List<InputEntry> {
        val infos = manager?.tvInputList.orEmpty()
            .filter { it.isPassthroughInput && !it.isHidden(context) }
        val byId = infos.associateBy { it.id }
        // An HDMI-CEC device (e.g. Apple TV) is registered as a child of its physical port.
        // Show the device instead of the bare port it sits behind.
        val portsWithDevices = infos.mapNotNull { it.parentId }.toSet()
        return infos
            .filter { it.id !in portsWithDevices }
            .map { info ->
                InputEntry(
                    id = info.id,
                    label = label(info),
                    parentLabel = info.parentId?.let { byId[it] }?.let(::label),
                    state = manager.getInputState(info.id),
                )
            }
            .sortedWith(
                compareByDescending<InputEntry> { it.isCecDevice }
                    .thenByDescending { it.isConnected }
                    .thenBy { it.label },
            )
    }

    fun open(id: String) {
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun register(callback: TvInputManager.TvInputCallback) =
        manager?.registerCallback(callback, Handler(Looper.getMainLooper()))

    fun unregister(callback: TvInputManager.TvInputCallback) = manager?.unregisterCallback(callback)

    private fun label(info: TvInputInfo): String =
        (info.loadCustomLabel(context) ?: info.loadLabel(context)).toString()
}
