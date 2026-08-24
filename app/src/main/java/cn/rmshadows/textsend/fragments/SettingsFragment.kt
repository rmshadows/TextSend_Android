package cn.rmshadows.textsend.fragments

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import cn.rmshadows.textsend.FileTransfer
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
        if (granted) {
            applying = true
            binding.keepAliveSwitch.isChecked = true
            applying = false
        } else {
            applying = true
            binding.keepAliveSwitch.isChecked = false
            applying = false
            Toast.makeText(context, "没有通知权限，状态栏可能看不到连接通知", Toast.LENGTH_SHORT).show()
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
        applying = true
        binding.qrAutoSwitch.isChecked = KeepAlive.isQrAutoConnect()
        refreshNotifSwitch()
        applying = false
        bindThemeCards()
        binding.keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            if (Build.VERSION.SDK_INT < 33) {
                return@setOnCheckedChangeListener
            }
            if (checked) {
                if (!hasNotifPermission()) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                return@setOnCheckedChangeListener
            }
            applying = true
            binding.keepAliveSwitch.isChecked = hasNotifPermission()
            applying = false
            Toast.makeText(context, "保活关不掉。要隐藏通知，到系统设置里关。", Toast.LENGTH_SHORT).show()
        }
        binding.qrAutoSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            KeepAlive.setQrAutoConnect(checked)
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
            refreshNotifSwitch()
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

    private fun refreshNotifSwitch() {
        if (Build.VERSION.SDK_INT < 33) {
            binding.keepAliveSwitch.visibility = View.GONE
            return
        }
        binding.keepAliveSwitch.visibility = View.VISIBLE
        applying = true
        binding.keepAliveSwitch.isChecked = hasNotifPermission()
        applying = false
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
        super.onDestroyView()
        _binding = null
    }
}
