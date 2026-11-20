package com.mjaskols.tvhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var background: LiveBackgroundView
    private lateinit var chrome: View
    private lateinit var splash: View
    private lateinit var inputsRow: LinearLayout
    private lateinit var settingsRow: LinearLayout
    private lateinit var autoStartTile: Tile

    private val tvInputs by lazy { TvInputs(this) }
    private val prefs by lazy { Prefs(this) }
    private var inputs: List<InputEntry> = emptyList()

    /** Set on the first launch after a boot; consumed once the primary input is registered. */
    private var pendingAutoStart = false
    private var splashShownAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val autoStartRunnable = Runnable {
        hideSplash()
        primaryInput()?.let(::openInput)
    }

    /** Picture-frame mode: after a while without input only the photo stays on screen. */
    private val idleRunnable = Runnable { chrome.animate().alpha(0f).setDuration(1200).start() }
    private var swallowKeyUp = false

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = refreshInputs()
        override fun onInputRemoved(inputId: String) = refreshInputs()
        override fun onInputUpdated(inputId: String) = refreshInputs()
        override fun onInputStateChanged(inputId: String, state: Int) = refreshInputs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        background = findViewById(R.id.background)
        chrome = findViewById(R.id.chrome)
        splash = findViewById(R.id.splash)
        inputsRow = findViewById(R.id.inputs_row)
        settingsRow = findViewById(R.id.settings_row)

        buildSettingsRow()
        checkAutoStart()
        refreshInputs()
        tvInputs.register(inputCallback)
    }

    override fun onResume() {
        super.onResume()
        background.start()
        refreshInputs()
        scheduleIdle()
    }

    override fun onPause() {
        background.stop()
        handler.removeCallbacks(idleRunnable)
        chrome.animate().cancel()
        chrome.alpha = 1f
        super.onPause()
    }

    override fun onDestroy() {
        tvInputs.unregister(inputCallback)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Any key during the boot splash means "stay here" — cancel the auto-switch.
            if (splash.visibility == View.VISIBLE) {
                handler.removeCallbacks(autoStartRunnable)
                pendingAutoStart = false
                hideSplash()
                swallowKeyUp = true
                return true
            }
            // First key in picture-frame mode only wakes the UI.
            if (chrome.alpha < 1f) {
                chrome.animate().alpha(1f).setDuration(200).start()
                scheduleIdle()
                swallowKeyUp = true
                return true
            }
            scheduleIdle()
        } else if (event.action == KeyEvent.ACTION_UP && swallowKeyUp) {
            swallowKeyUp = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, IDLE_MS)
    }

    private fun showSplash() {
        splashShownAt = SystemClock.uptimeMillis()
        splash.visibility = View.VISIBLE
        chrome.visibility = View.INVISIBLE
    }

    private fun hideSplash() {
        splash.visibility = View.GONE
        chrome.visibility = View.VISIBLE
        chrome.alpha = 1f
        inputsRow.getChildAt(0)?.requestFocus()
    }

    // HOME screen: Back has nowhere to go.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    private fun refreshInputs() {
        inputs = tvInputs.list()
        val hadFocus = inputsRow.findFocus() != null || currentFocus == null
        inputsRow.removeAllViews()
        if (inputs.isEmpty()) {
            inputsRow.addView(Tile(this, R.dimen.input_tile_width, R.dimen.input_tile_height)
                .bind(getString(R.string.no_inputs), dimmed = true))
        }
        for (input in inputs) {
            val tile = Tile(this, R.dimen.input_tile_width, R.dimen.input_tile_height)
                .bind(input.label, subtitleFor(input), dimmed = !input.isConnected)
            tile.setOnClickListener { openInput(input) }
            inputsRow.addView(tile)
        }
        if (hadFocus) inputsRow.getChildAt(0)?.requestFocus()
        updateAutoStartTile()

        if (pendingAutoStart && primaryInput() != null) {
            pendingAutoStart = false
            val shown = SystemClock.uptimeMillis() - splashShownAt
            handler.postDelayed(autoStartRunnable, (SPLASH_MS - shown).coerceAtLeast(0))
        }
    }

    // Sony reports HDMI ports as CONNECTED even when empty, so only show states that carry information.
    private fun subtitleFor(input: InputEntry): String? = when (input.state) {
        TvInputManager.INPUT_STATE_DISCONNECTED -> getString(R.string.state_disconnected)
        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> getString(R.string.state_standby)
        else -> input.parentLabel?.let { getString(R.string.via_cec, it) }
    }

    private fun openInput(input: InputEntry) {
        try {
            tvInputs.open(input.id)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.cannot_open, input.label))
        }
    }

    private fun primaryInput(): InputEntry? =
        inputs.firstOrNull { it.id == prefs.primaryInputId } ?: inputs.firstOrNull { it.isCecDevice }

    private fun checkAutoStart() {
        if (!prefs.autoStart) return
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot == prefs.lastAutoStartBoot) return
        prefs.lastAutoStartBoot = boot
        pendingAutoStart = true
        showSplash()
    }

    private fun buildSettingsRow() {
        fun add(titleRes: Int, intent: Intent) {
            val tile = Tile(this, R.dimen.setting_tile_width, R.dimen.setting_tile_height).bind(getString(titleRes))
            tile.setOnClickListener { launch(intent, getString(titleRes)) }
            settingsRow.addView(tile)
        }
        // Sony's quick-adjust overlays (same panels as the remote's Action Menu).
        add(R.string.tile_picture, sonyPanel("PictureSettingActivity"))
        add(R.string.tile_sound, sonyPanel("SoundSettingActivity"))
        add(R.string.tile_screen, sonyPanel("ScreenSettingActivity"))
        add(R.string.tile_network, Intent(Settings.ACTION_WIFI_SETTINGS))
        add(R.string.tile_all_settings, Intent(Settings.ACTION_SETTINGS))

        autoStartTile = Tile(this, R.dimen.setting_tile_width, R.dimen.setting_tile_height)
        autoStartTile.setOnClickListener {
            prefs.autoStart = !prefs.autoStart
            updateAutoStartTile()
        }
        settingsRow.addView(autoStartTile)
    }

    private fun updateAutoStartTile() {
        val sub = if (prefs.autoStart) {
            getString(R.string.autostart_on, primaryInput()?.label ?: "—")
        } else {
            getString(R.string.autostart_off)
        }
        autoStartTile.bind(getString(R.string.tile_autostart), sub)
    }

    private fun sonyPanel(activity: String) = Intent().setComponent(
        ComponentName("com.sony.dtv.configsettings", "com.sony.dtv.configsettings.$activity"),
    )

    private fun launch(intent: Intent, label: String) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.cannot_open, label))
        } catch (e: SecurityException) {
            toast(getString(R.string.cannot_open, label))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val SPLASH_MS = 3_000L
        const val IDLE_MS = 20_000L
    }
}
