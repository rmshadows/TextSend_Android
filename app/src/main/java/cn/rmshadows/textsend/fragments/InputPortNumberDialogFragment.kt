package cn.rmshadows.textsend.fragments

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import cn.rmshadows.textsend.R

class InputPortNumberDialogFragment : DialogFragment() {

    // 在这里定义接口，在要显示DialogFragment的Fragment中实现这个接口
    interface OnInputPortReceived {
        fun onInputPortReceived(input: String)
    }

    // 接口
    private var onInputReceived: OnInputPortReceived? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        //为了样式统一和兼容性，可以使用 V7 包下的 AlertDialog.Builder
        val builder: AlertDialog.Builder = AlertDialog.Builder(requireContext())
        // 设置主题的构造方法
        val inflater: LayoutInflater = requireActivity().layoutInflater
        val view: View = inflater.inflate(R.layout.fragment_input_dialog, null)
        // 设置只能输入数字
        val et = view.findViewById<EditText>(R.id.inputEditText)
        if (et != null) {
            et.inputType = InputType.TYPE_CLASS_NUMBER
        }else{
            Log.e(TAG, "onCreateDialog: EditText为null!")
        }
        builder.setView(view)
            .setMessage("请输入端口号（输入0默认54300）：")
            .setPositiveButton("确定") { _, _ ->
                var portInput:String = et.text.toString()
                if(portInput != ""){
                    if (portInput == "0"){
                        portInput = "54300"
                    }
                    try {
                        portInput.toInt()
                        onInputReceived?.onInputPortReceived(portInput)
                    }catch (e: Exception){
                        Log.w(TAG, "onCreateDialog: 输入的可能不是数字！")
                    }
                    dismiss() // 关闭对话框
                }
            }
            .setNegativeButton("取消", null)
        return builder.create()
    }

    // 接收从Fragment中传过来的接口实现
    fun setOnInputListener(listener: OnInputPortReceived) {
        onInputReceived = listener
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
