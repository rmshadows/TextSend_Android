package cn.rmshadows.textsend.fragments

import android.app.Dialog
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


class InputPortNumberDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        //为了样式统一和兼容性，可以使用 V7 包下的 AlertDialog.Builder
        val builder: AlertDialog.Builder = AlertDialog.Builder(requireContext())
        // 设置主题的构造方法
        val inflater: LayoutInflater = requireActivity().getLayoutInflater()
        val view: View = inflater.inflate(R.layout.fragment_input_dialog, null)
        builder.setView(view)
            .setMessage("请输入端口号（默认54300）：")
            .setPositiveButton("确定") { dialog, which ->
                val et = view.findViewById<EditText>(R.id.inputEditText)
                val portInput:String = et.text.toString()
                if (! et.text.isEmpty()) {
                    Log.d(TAG, "监听端口：${MainActivity.serverListenPort} => $portInput")
                    MainActivity.serverListenPort = portInput
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

    companion object {
        const val TAG = "getPortDialog"
    }

}
