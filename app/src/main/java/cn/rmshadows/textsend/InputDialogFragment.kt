package cn.rmshadows.textsend

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import cn.rmshadows.textsend.databinding.FragmentInputDialogBinding


class InputDialogFragment : DialogFragment() {
    private var _binding: FragmentInputDialogBinding? = null
    private val binding get() = _binding!!
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        //为了样式统一和兼容性，可以使用 V7 包下的 AlertDialog.Builder
        val builder: AlertDialog.Builder = AlertDialog.Builder(requireContext())
        // 设置主题的构造方法
        val inflater: LayoutInflater = requireActivity().getLayoutInflater()
        val view: View = inflater.inflate(R.layout.fragment_input_dialog, null)
        builder.setView(view)
            .setMessage("请输入端口号（默认54300）：")
            .setPositiveButton("确定") { dialog, which ->
                val portInput = binding.inputListenPort.text.toString()
                Log.d(MainActivity.TAG, "text input: $portInput")
//                if (binding.inputListenPort.text.isBlank()) {
//                    Log.d(MainActivity.TAG, "onCreateView: blank")
//                }
            }
            .setNegativeButton("取消", null)
        return builder.create()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentInputDialogBinding.inflate(inflater, container, false)
        return inflater.inflate(R.layout.fragment_input_dialog, container, false)
    }
}
