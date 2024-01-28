package cn.rmshadows.textsend.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import cn.rmshadows.textsend.R
import utils.IPAddressFilter


class InputIpAddressDialogFragment : DialogFragment() {
    private var onInputReceived: OnInputIpReceived? = null

    interface OnInputIpReceived {
        fun onInputIpReceived(input: String)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        //为了样式统一和兼容性，可以使用 V7 包下的 AlertDialog.Builder
        val builder: AlertDialog.Builder = AlertDialog.Builder(requireContext())
        // 设置主题的构造方法
        val inflater: LayoutInflater = requireActivity().layoutInflater
        val view: View = inflater.inflate(R.layout.fragment_input_dialog, null)
        builder.setView(view)
            .setMessage("手动指定IP地址(IPv6需要中括号'[Ipv6]')：")
            .setPositiveButton("确定") { _, _ ->
                val et = view.findViewById<EditText>(R.id.inputEditText)
                val ipInput: String = et.text.toString()
                if (et.text.isNotEmpty()) {
                    if(IPAddressFilter.getIpType(ipInput) != 1 && IPAddressFilter.getIpType(ipInput) != 2){
                        Toast.makeText(
                            context,
                            "请输入有效的IPv4或者IPv6地址(需要中括号)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }else{
                        onInputReceived?.onInputIpReceived(ipInput)
                    }
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

    fun setOnInputListener(listener: OnInputIpReceived) {
        onInputReceived = listener
    }

    companion object {
        const val TAG = "getIpDialog"
    }
}
