package cn.rmshadows.textsend.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import cn.rmshadows.textsend.MainActivity
import cn.rmshadows.textsend.databinding.FragmentMessengerBinding


class MessengerFragment : Fragment() {
    private val TAG: String = MainActivity.TAG
    private var _binding: FragmentMessengerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessengerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 发送
        binding.buttonSend.setOnClickListener{

        }

    }

    fun cleanEditText() {
        binding.editText.text.clear()
    }

    override fun onDestroyView() {
        // 服务端、客户端的统称
        MainActivity.isClientConnected = false
        super.onDestroyView()
        _binding = null
    }

    companion object {
        val instance = MessengerFragment()
    }

    interface clearETListener {
        fun onclearEditText()
    }
}