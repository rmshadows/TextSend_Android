package cn.rmshadows.textsend;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import cn.rmshadows.textsend.databinding.ClientMsgFragmentBinding;
import utils.ClientMsgController;
import utils.Message;
import utils.ServerMsgController;

public class ClientMsgFragment extends Fragment {
    private static ClientMsgFragment clientMsgFragment;
    private static EditText editText;

    private ClientMsgFragmentBinding binding;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        MainActivity.page_view_index = 2;
        binding = ClientMsgFragmentBinding.inflate(inflater, container, false);
        // 绑定控件
        clientMsgFragment = ClientMsgFragment.this;
        editText = binding.toSend;

        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.buttonSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(!MainActivity.is_server){//如果是客户端
                    if(!MainActivity.is_client_connected){
                        back2PreviousNav();
                    }else{
                        // 客户端发送消息
                        ClientMsgController.sendMsgToServer(new Message(editText.getText()
                                .toString(),MainActivity.MSG_LEN, ClientMsgController.id, null));
                    }
                }else{
                    ServerMsgController.sendMsgToClient(new Message(editText.getText()
                            .toString(),MainActivity.MSG_LEN, ServerMsgController.SERVER_ID, null));
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        // 如果已经连接, 退出发送页面视为退出
        if(!MainActivity.is_server){
            if(MainActivity.is_client_connected && MainActivity.socket_of_client != null){
                MainActivity.closeClientSocket();
            }
        }
        super.onDestroyView();
        binding = null;
    }

    // 返回上一个页面 客户端连接或者服务端二维码页面
    public static void back2PreviousNav(){
        try{
            if(MainActivity.is_server){
                MainActivity.mainActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        NavHostFragment.findNavController(clientMsgFragment)
                                .navigate(R.id.action_ClientMsgFragment_to_ServerQRFragment);
                    }
                });
            }else{
                NavHostFragment.findNavController(clientMsgFragment)
                        .navigate(R.id.action_ClientMsgFragment_to_ClientConfigFragment);
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    // 清除文本框内容
    public static void cleanText() {
        // 清空
        if(MainActivity.is_client_connected || MainActivity.is_server_running){
            MainActivity.mainActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    //Accessibility content change on non-UI thread. Future Android versions will throw an exception.
                    editText.setText("");
                }
            });
        }
    }

}