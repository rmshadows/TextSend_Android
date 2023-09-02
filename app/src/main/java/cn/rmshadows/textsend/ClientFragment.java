package cn.rmshadows.textsend;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import java.net.Socket;

import cn.rmshadows.textsend.databinding.ClientConfigFragmentBinding;
import utils.ClientMessageController;

public class ClientFragment extends Fragment {
    private static Fragment fragment;
    private ClientConfigFragmentBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        MainActivity.pageViewIndex = 1;
        Log.d("==>>APP_LOG<<==", "Creating connection view.");
        binding = ClientConfigFragmentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        EditText editTextIp = binding.addrIn;
        EditText editTextPort = binding.portIn;
        fragment = ClientFragment.this;
        // 刚刚使用过扫一扫 则将扫描结果填上去
        if (MainActivity.qrCodingYet) {
            MainActivity.qrCodingYet = false;
            setTextSendDesktopAddr(MainActivity.clientQrScanResult, editTextIp, editTextPort);
        }
        // 连接按钮 连接
        binding.buttonConnect.setOnClickListener(view1 -> {
            // 连接
            try {
//                    Toast.makeText(MainActivity.m,"连接成功",Toast.LENGTH_SHORT).show();
                String clientAddr;
                int clientPort = 54300;
                clientAddr = editTextIp.getText().toString();
                if (!editTextPort.getText().toString().equals("")) {
                    clientPort = Integer.parseInt(editTextPort.getText().toString());
                }
                // final引入线程
                String finalClientAddr = clientAddr;
                int finalClientPort = clientPort;
                new Thread(() -> {
                    try {
                        Socket socket = new Socket(finalClientAddr, finalClientPort);
                        Log.d("==>>APP_LOG<<==", "连接地址：" + finalClientAddr + ":" + finalClientPort);
                        new Thread(new ClientMessageController(socket)).start();
                        MainActivity.clientSocket = socket;
                    } catch (Exception e) {
                        MainActivity.showToast("连接失败", Toast.LENGTH_SHORT);
                        e.printStackTrace();
                    }
                }).start();
                // 连接成功就跳转发送页面
                NavHostFragment.findNavController(ClientFragment.this).navigate(R.id.action_ClientConfigFragment_to_ClientMsgFragment);
            } catch (Exception e) {
                Log.d("==>>APP_LOG<<==", "buttonConnect连接失败");
                MainActivity.isClientConnected = false;
                e.printStackTrace();
            }
        });
    }

    @Override
    public void onDestroyView() {
        Log.d("==>>APP_LOG<<==", "Exiting connection view.");
        super.onDestroyView();
        binding = null;
    }

    /**
     * 跳转到二维码扫描界面
     */
    public static void goScanQR() {
        NavHostFragment.findNavController(fragment).navigate(R.id.action_ClientConfigFragment_to_CaptureFragment);
    }

    /**
     * 跳转服务界面
     */
    public static void goServer() {
        NavHostFragment.findNavController(fragment).navigate(R.id.action_ClientConfigFragment_to_ServerFragment);
    }

    /**
     * 设置IP地址
     *
     * @param ip_address IP地址和端口
     */
    public static void setTextSendDesktopAddr(String ip_address, EditText editTextIp, EditText editTextPort) {
        try {
            String[] ip = ip_address.split(":");
            editTextIp.setText(ip[0]);
            editTextPort.setText(ip[1]);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}