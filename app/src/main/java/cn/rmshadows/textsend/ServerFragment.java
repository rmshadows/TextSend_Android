package cn.rmshadows.textsend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import cn.rmshadows.textsend.databinding.ServerConfigFragmentBinding;

public class ServerFragment extends Fragment {
    private ServerConfigFragmentBinding binding;
    private static Fragment fragment;
    // Do not place Android context classes in static fields; this is a memory leak
//    private static EditText server_port;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        MainActivity.pageViewIndex = -1;
        binding = ServerConfigFragmentBinding.inflate(inflater, container, false);
        fragment = ServerFragment.this;
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 不再是类变量
        EditText server_port = binding.serverPort;
        // 按钮点击事件
        binding.start.setOnClickListener(view1 -> {
            // 没填端口号默认54300
            if (server_port.getText().toString().equals("")) {
                MainActivity.setServerListenPort("54300");
            } else {
                // 设置自定义端口号
                MainActivity.setServerListenPort(server_port.getText().toString());
            }
            // 前往服务端二维码页面即启动
            NavHostFragment.findNavController(fragment).navigate(R.id.action_ServerConfigFragment_to_ServerQRFragment);
        });

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /**
     * 前往客户端连接页面
     */
    public static void goClient() {
        NavHostFragment.findNavController(fragment)
                .navigate(R.id.action_ServerConfigFragment_to_ClientConfigFragment);
    }

}
