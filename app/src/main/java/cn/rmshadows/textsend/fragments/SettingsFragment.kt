package cn.rmshadows.textsend.fragments

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.rmshadows.textsend.KeepAlive
import cn.rmshadows.textsend.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var applying = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            KeepAlive.setEnabled(true)
        } else {
            applying = true
            binding.keepAliveSwitch.isChecked = false
            applying = false
            Toast.makeText(context, "没有通知权限，保活无法生效", Toast.LENGTH_SHORT).show()
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
        binding.keepAliveSwitch.isChecked = KeepAlive.isEnabled()
        applying = false
        binding.keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) {
                return@setOnCheckedChangeListener
            }
            if (checked && Build.VERSION.SDK_INT >= 33) {
                val ok = ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!ok) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return@setOnCheckedChangeListener
                }
            }
            KeepAlive.setEnabled(checked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
