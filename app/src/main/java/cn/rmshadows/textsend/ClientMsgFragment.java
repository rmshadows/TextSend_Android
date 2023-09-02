package cn.rmshadows.textsend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import cn.rmshadows.textsend.databinding.ClientMsgFragmentBinding;
import utils.ClientMessageController;
import utils.Message;
import utils.SocketDeliver;

public class ClientMsgFragment extends Fragment {
    private ClientMsgFragmentBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        MainActivity.pageViewIndex = 2;
        binding = ClientMsgFragmentBinding.inflate(inflater, container, false);
        // 绑定控件
        MainActivity.clientMessageFragment = ClientMsgFragment.this;
        MainActivity.editTextSending = binding.toSend;
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.buttonSend.setOnClickListener(view1 -> {
            if (!MainActivity.isServerMode) {//如果是客户端
                if (!MainActivity.isClientConnected) {
                    backToPreviousNav();
                } else {
                    // 客户端发送消息
                    ClientMessageController.sendMessageToServer(
                            new Message(ClientMessageController.clientId, MainActivity.editTextSending.getText().toString(), MainActivity.MSG_LEN, null));
                }
            } else {
                // 服务端发送消息
                SocketDeliver.sendMessageToAllClients(
                        new Message(MainActivity.SERVER_ID, MainActivity.editTextSending.getText().toString(), MainActivity.MSG_LEN, null));
            }
        });
    }

    @Override
    public void onDestroyView() {
        // 如果已经连接, 退出发送页面视为退出
        if (!MainActivity.isServerMode) {
            if (MainActivity.isClientConnected && MainActivity.clientSocket != null) {
                MainActivity.closeClientSocket();
            }
        }
        super.onDestroyView();
        binding = null;
    }

    /**
     * 返回上一个页面 客户端连接或者服务端二维码页面
     */
    public static void backToPreviousNav() {
        try {
            if (MainActivity.isServerMode) {
                MainActivity.mainActivity.runOnUiThread(() -> NavHostFragment.findNavController(MainActivity.clientMessageFragment)
                        .navigate(R.id.action_ClientMsgFragment_to_ServerQRFragment));
            } else {
                NavHostFragment.findNavController(MainActivity.clientMessageFragment)
                        .navigate(R.id.action_ClientMsgFragment_to_ClientConfigFragment);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}