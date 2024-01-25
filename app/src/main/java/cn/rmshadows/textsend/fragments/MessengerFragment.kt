package cn.rmshadows.textsend.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import cn.rmshadows.textsend.databinding.FragmentMessengerBinding
import cn.rmshadows.textsend.viewmodels.ServerFragmentViewModel
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import utils.Constant


class MessengerFragment : Fragment() {
    private val TAG: String = Constant.TAG
    private var _binding: FragmentMessengerBinding? = null
    private val binding get() = _binding!!
    private lateinit var tsviewModel: TextsendViewModel
    private lateinit var sfviewModel: ServerFragmentViewModel
//    private lateinit var cfviewModel: ClientFragmentViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessengerBinding.inflate(inflater, container, false)
        val tsviewModel: TextsendViewModel by viewModels()
        val sfviewModel: ServerFragmentViewModel by viewModels()
//        val cfviewModel: ClientFragmentViewModel by viewModels()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 发送
        binding.buttonSend.setOnClickListener {

        }

    }

    fun cleanEditText() {
        binding.editText.text.clear()
    }

    override fun onDestroyView() {
        // 服务端、客户端的统称
        tsviewModel.update(3, null, false, null)
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