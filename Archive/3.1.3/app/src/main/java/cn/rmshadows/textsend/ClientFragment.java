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
import utils.ClientMsgController;

public class ClientFragment extends Fragment {

    private static Fragment fragment;
    private static EditText et_ip_addr;
    private static EditText et_port;
    private ClientConfigFragmentBinding binding;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        MainActivity.page_view_index = 1;
        Log.d("==>>APP_LOG<<==","Creating connection view.");
        binding = ClientConfigFragmentBinding.inflate(inflater, container, false);

        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        fragment = ClientFragment.this;
        et_ip_addr = binding.addrIn;
        et_port = binding.portIn;

        // 如果刚刚使用过扫一扫
        if(MainActivity.qr_coding_yet){
            MainActivity.qr_coding_yet = false;
            setTextSendDesktopAddr(MainActivity.client_qr_scan_result);
        }

        binding.buttonConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 连接
                try{
//                    Toast.makeText(MainActivity.m,"连接成功",Toast.LENGTH_SHORT).show();
                    String client_addr = "";
                    int client_port = 54300;
                    client_addr = et_ip_addr.getText().toString();
                    if(!et_port.getText().toString().equals("")){
                        client_port = Integer.valueOf(et_port.getText().toString());
                    }

                    // 下面直接调用报错  android.os.NetworkOnMainThreadException
//                    Thread c = new Thread(new ClientMsgController(client_addr, client_port));
//                    c.start();

                    // final引入线程
                    String finalClient_addr = client_addr;
                    int finalClient_port = client_port;

                    new Thread(() -> {
                        try {
                            Socket socket = new Socket(finalClient_addr, finalClient_port);
                            Log.d("==>>APP_LOG<<==", "连接地址："+finalClient_addr+":"+String.valueOf(finalClient_port));
                            new Thread(new ClientMsgController(socket)).start();
                            MainActivity.socket_of_client = socket;
                        }catch (Exception e){
                            MainActivity.showToast("连接失败", Toast.LENGTH_SHORT);
                            e.printStackTrace();
                        }
                    }).start();

                    NavHostFragment.findNavController(ClientFragment.this)
                            .navigate(R.id.action_ClientConfigFragment_to_ClientMsgFragment);
                }catch (Exception e){
                    Log.d("==>>APP_LOG<<==","buttonConnect连接失败");
                    MainActivity.is_client_connected = false;
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        Log.d("==>>APP_LOG<<==","Exiting connection view.");
        super.onDestroyView();
        binding = null;
    }

    /**
     * 跳转到二维码扫描界面
     */
    public static void goScanQR(){
        NavHostFragment.findNavController(fragment)
                .navigate(R.id.action_ClientConfigFragment_to_CaptureFragment);
    }

    /**
     * 跳转服务界面
     */
    public static void goServer(){
        NavHostFragment.findNavController(fragment)
                .navigate(R.id.action_ClientConfigFragment_to_ServerFragment);
    }

    /**
     * 设置IP地址
     * @param ip_address IP地址和端口
     */
    public static void setTextSendDesktopAddr(String ip_address){
        try{
            String[] ip = ip_address.split(":");
            et_ip_addr.setText(ip[0]);
            et_port.setText(ip[1]);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

}