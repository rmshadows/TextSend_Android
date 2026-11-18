package cn.rmshadows.textsend.fragments

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import cn.rmshadows.textsend.ClientConnectionSettings
import cn.rmshadows.textsend.FileTransfer
import cn.rmshadows.textsend.LanAuthGate
import cn.rmshadows.textsend.LanDiscover
import cn.rmshadows.textsend.PinSettings
import cn.rmshadows.textsend.KeepAlive
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.ReceiveFolder
import cn.rmshadows.textsend.ThemeSettings
import cn.rmshadows.textsend.databinding.FragmentSettingsBinding
import com.google.android.material.card.MaterialCardView
import protocol.FileNames

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var applying = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        applying = true
        binding.keepAliveSwitch.isChecked = granted
        applying = false
        KeepAlive.setEnabled(granted)
        if (!granted) {
            Toast.makeText(context, "没有通知权限，保活通知出不来", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showStoredSettings()
        bindThemeCards()
        binding.keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            if (checked && Build.VERSION.SDK_INT >= 33 && !hasNotifPermission()) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@setOnCheckedChangeListener
            }
            KeepAlive.setEnabled(checked)
        }
        val openNotifSettings = View.OnLongClickListener {
            openAppNotificationSettings()
            true
        }
        binding.keepAliveSwitch.setOnLongClickListener(openNotifSettings)
        binding.keepAliveRow.setOnLongClickListener(openNotifSettings)
        binding.qrAutoSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            KeepAlive.setQrAutoConnect(checked)
        }
        binding.rememberConnectionSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            ClientConnectionSettings.setRememberEnabled(checked)
        }
        binding.lanDiscoverSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            LanDiscover.setEnabled(checked)
            syncLanPortUi(checked)
            binding.lanDiscoverMode.isEnabled = checked
            binding.lanAnnounceSwitch.isEnabled = checked
        }
        binding.lanPortFollowSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            LanDiscover.setPortFollow(checked)
            syncLanPortUi(LanDiscover.isEnabled())
        }
        binding.lanAnnounceSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            LanDiscover.setAnnounce(checked)
        }
        binding.lanDiscoverMode.setOnCheckedChangeListener { _, id ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            LanDiscover.setAuto(id == R.id.lanDiscoverAuto)
        }
        binding.lanPauseSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            LanDiscover.setPauseWhenConnected(checked)
        }
        binding.lanDiscoverPortInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || applying) {
                return@setOnFocusChangeListener
            }
            val port = binding.lanDiscoverPortInput.text?.toString()?.trim()?.toIntOrNull()
            if (port == null || !LanDiscover.setPort(port)) {
                Toast.makeText(context, R.string.settings_lan_port_invalid, Toast.LENGTH_SHORT).show()
                binding.lanDiscoverPortInput.setText(LanDiscover.getPort().toString())
            }
        }
        binding.authRateInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || applying) {
                return@setOnFocusChangeListener
            }
            val n = binding.authRateInput.text?.toString()?.trim()?.toIntOrNull()
            if (n == null || !LanAuthGate.setRatePerMin(n)) {
                Toast.makeText(context, R.string.settings_auth_rate_invalid, Toast.LENGTH_SHORT).show()
                binding.authRateInput.setText(LanAuthGate.getRatePerMin().toString())
            }
        }
        binding.authCooldownInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || applying) {
                return@setOnFocusChangeListener
            }
            val n = binding.authCooldownInput.text?.toString()?.trim()?.toIntOrNull()
            if (n == null || !LanAuthGate.setCooldownSec(n)) {
                Toast.makeText(context, R.string.settings_auth_cooldown_invalid, Toast.LENGTH_SHORT).show()
                binding.authCooldownInput.setText(LanAuthGate.getCooldownSec().toString())
            }
        }
        binding.authBlacklistInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || applying) {
                return@setOnFocusChangeListener
            }
            LanAuthGate.setBlacklistText(binding.authBlacklistInput.text?.toString().orEmpty())
            binding.authBlacklistInput.setText(LanAuthGate.getBlacklistText())
        }
        binding.customPinSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            if (checked && !PinSettings.isValidPin(binding.customPinInput.text?.toString())) {
                applying = true
                binding.customPinSwitch.isChecked = false
                applying = false
                Toast.makeText(context, R.string.settings_custom_pin_invalid, Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            PinSettings.setEnabled(checked)
        }
        binding.customPinInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || applying) {
                return@setOnFocusChangeListener
            }
            val pin = binding.customPinInput.text?.toString()?.trim() ?: ""
            if (pin.isNotEmpty() && !PinSettings.isValidPin(pin)) {
                Toast.makeText(context, R.string.settings_custom_pin_invalid, Toast.LENGTH_SHORT).show()
                binding.customPinInput.setText(PinSettings.getPin())
                return@setOnFocusChangeListener
            }
            PinSettings.setPin(pin)
        }
        refreshCacheSize()
        binding.buttonClearCache.setOnClickListener {
            val n = FileTransfer.clearSendCache(requireContext())
            if (n < 0) {
                Toast.makeText(context, "发送中不能清除缓存", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            refreshCacheSize()
            Toast.makeText(
                context,
                getString(R.string.settings_cache_cleared, FileTransfer.formatBytes(n)),
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.inboxPathText.text = getString(
            R.string.settings_inbox_hint,
            FileNames.FOLDER
        )
        binding.buttonOpenInbox.setOnClickListener {
            ReceiveFolder.openManager(requireContext())
        }
        binding.buttonBrowseInbox.setOnClickListener {
            ReceiveFolder.browse(requireContext())
        }
        binding.buttonResetSettings.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_reset)
                .setMessage(R.string.settings_reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_reset) { _, _ -> resetSettings() }
                .show()
        }
        binding.buttonHelp.setOnClickListener {
            val body = layoutInflater.inflate(R.layout.dialog_help, null)
            body.findViewById<android.widget.TextView>(R.id.helpBody).text =
                HtmlCompat.fromHtml(
                    getString(R.string.about_help),
                    HtmlCompat.FROM_HTML_MODE_LEGACY
                )
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_help)
                .setView(body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            refreshCacheSize()
            applying = true
            binding.keepAliveSwitch.isChecked = KeepAlive.isEnabled()
            applying = false
        }
    }

    private fun hasNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun openAppNotificationSettings() {
        val ctx = context ?: return
        val pkg = ctx.packageName
        val intents = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", pkg, null))
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(ctx, "打不开系统通知设置", Toast.LENGTH_SHORT).show()
    }

    private fun showStoredSettings() {
        applying = true
        binding.keepAliveSwitch.isChecked = KeepAlive.isEnabled()
        binding.qrAutoSwitch.isChecked = KeepAlive.isQrAutoConnect()
        binding.rememberConnectionSwitch.isChecked = ClientConnectionSettings.isRememberEnabled()
        binding.lanDiscoverSwitch.isChecked = LanDiscover.isEnabled()
        binding.lanPauseSwitch.isChecked = LanDiscover.isPauseWhenConnected()
        binding.lanPortFollowSwitch.isChecked = LanDiscover.isPortFollow()
        syncLanPortUi(LanDiscover.isEnabled())
        binding.lanDiscoverMode.isEnabled = LanDiscover.isEnabled()
        binding.lanAnnounceSwitch.isChecked = LanDiscover.isAnnounce()
        binding.lanAnnounceSwitch.isEnabled = LanDiscover.isEnabled()
        if (LanDiscover.isAuto()) {
            binding.lanDiscoverAuto.isChecked = true
        } else {
            binding.lanDiscoverManual.isChecked = true
        }
        binding.authRateInput.setText(LanAuthGate.getRatePerMin().toString())
        binding.authCooldownInput.setText(LanAuthGate.getCooldownSec().toString())
        binding.authBlacklistInput.setText(LanAuthGate.getBlacklistText())
        binding.customPinSwitch.isChecked = PinSettings.isEnabled()
        binding.customPinInput.setText(PinSettings.getPin())
        applying = false
    }

    private fun syncLanPortUi(discoverOn: Boolean) {
        val follow = LanDiscover.isPortFollow()
        binding.lanPauseSwitch.isEnabled = discoverOn
        binding.lanPortFollowSwitch.isEnabled = discoverOn
        binding.lanDiscoverPortInput.setText(LanDiscover.getPort().toString())
        binding.lanDiscoverPortInput.isEnabled = discoverOn && !follow
    }

    private fun resetSettings() {
        val ctx = context ?: return
        val themeWas = ThemeSettings.get(ctx)
        ctx.getSharedPreferences("textsend_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("keep_alive", false)
            .putBoolean("qr_auto_connect", true)
            .putBoolean("client_remember_enabled", false)
            .remove("client_last_ip")
            .remove("client_last_port")
            .remove("client_last_pin")
            .putBoolean("lan_discover", false)
            .putBoolean("lan_discover_auto", true)
            .putBoolean("lan_discover_announce", true)
            .putBoolean("lan_discover_pause", false)
            .putBoolean("lan_discover_port_follow", true)
            .putInt("lan_discover_port", 54300)
            .putInt("connect_port", 54300)
            .putInt("auth_rate_per_min", 3)
            .putInt("auth_cooldown_sec", 120)
            .putString("auth_blacklist", "")
            .putBoolean("custom_pin_enabled", false)
            .putString("custom_pin", "")
            .commit()
        KeepAlive.setEnabled(false)
        LanDiscover.setEnabled(false)
        showStoredSettings()
        if (themeWas != ThemeSettings.DEFAULT) {
            applying = true
            ThemeSettings.applyChoice(requireActivity(), ThemeSettings.DEFAULT)
            return
        }
        refreshThemeSelection()
        applying = false
        Toast.makeText(ctx, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun bindThemeCards() {
        val cards = listOf(
            binding.themeDefault to ThemeSettings.DEFAULT,
            binding.themeDark to ThemeSettings.DARK,
            binding.themePaper to ThemeSettings.PAPER,
            binding.themeSlate to ThemeSettings.SLATE
        )
        val current = ThemeSettings.get(requireContext())
        cards.forEach { (card, id) ->
            markThemeCard(card, id == current)
            card.setOnClickListener {
                if (id == ThemeSettings.get(requireContext())) {
                    return@setOnClickListener
                }
                ThemeSettings.applyChoice(requireActivity(), id)
            }
        }
    }

    private fun refreshThemeSelection() {
        val current = ThemeSettings.get(requireContext())
        listOf(
            binding.themeDefault to ThemeSettings.DEFAULT,
            binding.themeDark to ThemeSettings.DARK,
            binding.themePaper to ThemeSettings.PAPER,
            binding.themeSlate to ThemeSettings.SLATE
        ).forEach { (card, id) -> markThemeCard(card, id == current) }
    }

    private fun markThemeCard(card: MaterialCardView, selected: Boolean) {
        card.isChecked = selected
        card.strokeWidth = if (selected) 3 else 1
        val ta = requireContext().obtainStyledAttributes(
            intArrayOf(
                com.google.android.material.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorOutline
            )
        )
        val primary = ta.getColor(0, 0)
        val outline = ta.getColor(1, 0)
        ta.recycle()
        card.strokeColor = if (selected) primary else outline
    }

    private fun refreshCacheSize() {
        val n = FileTransfer.sendCacheBytes(requireContext())
        binding.cacheSizeText.text = getString(R.string.settings_cache_size, FileTransfer.formatBytes(n))
    }

    override fun onDestroyView() {
        if (_binding != null && !applying) {
            val rate = binding.authRateInput.text?.toString()?.trim()?.toIntOrNull()
            if (rate != null) {
                LanAuthGate.setRatePerMin(rate)
            }
            val cool = binding.authCooldownInput.text?.toString()?.trim()?.toIntOrNull()
            if (cool != null) {
                LanAuthGate.setCooldownSec(cool)
            }
            LanAuthGate.setBlacklistText(binding.authBlacklistInput.text?.toString().orEmpty())
        }
        super.onDestroyView()
        _binding = null
    }
}
