package cn.rmshadows.textsend.fragments

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import cn.rmshadows.textsend.MainActivity
import cn.rmshadows.textsend.R


class InputIpAddressDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        //为了样式统一和兼容性，可以使用 V7 包下的 AlertDialog.Builder
        val builder: AlertDialog.Builder = AlertDialog.Builder(requireContext())
        // 设置主题的构造方法
        val inflater: LayoutInflater = requireActivity().getLayoutInflater()
        val view: View = inflater.inflate(R.layout.fragment_input_dialog, null)
        builder.setView(view)
            .setMessage("手动指定IP地址：")
            .setPositiveButton("确定") { dialog, which ->
                val et = view.findViewById<EditText>(R.id.inputEditText)
                val ipInput:String = et.text.toString()
                if (! et.text.isEmpty()) {
                    Log.d(TAG, "IP地址：${MainActivity.preferIpAddr} => $ipInput")
                    MainActivity.preferIpAddr = ipInput
                }
            }
            .setNegativeButton("取消", null)
        return builder.create()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_input_dialog, container, false)
    }

    override fun onDismiss(dialog: DialogInterface) {
        MainActivity.isDialogFragmentAtomClose.set(true)
        super.onDismiss(dialog)
    }

    companion object {
        const val TAG = "getIpDialog"
    }

}
