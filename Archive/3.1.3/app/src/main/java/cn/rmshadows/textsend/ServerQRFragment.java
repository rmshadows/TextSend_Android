package cn.rmshadows.textsend;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.king.zxing.util.CodeUtils;

import cn.rmshadows.textsend.databinding.ServerQrFragmentBinding;
import utils.SocketDeliver;

public class ServerQRFragment extends Fragment {
    private static ServerQRFragment serverQRFragment;
    private TextView tvTitle;
    private ImageView ivCode;

    private ServerQrFragmentBinding binding;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        MainActivity.page_view_index = -2;
        binding = ServerQrFragmentBinding.inflate(inflater, container, false);
        // 绑定控件
        serverQRFragment = ServerQRFragment.this;

        super.onCreate(savedInstanceState);
        ivCode = binding.imageView;
        tvTitle = binding.tvHint;

        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 生成二维码
        String QR_code = MainActivity.Prefer_Addr + ":" + MainActivity.server_port;
        tvTitle.setText(QR_code);
        createQRCode(QR_code);

        if(MainActivity.is_server_running){
            if(MainActivity.is_server_connected){
                SocketDeliver.disconnect_all_client();
            }
        }else {
            // 启动服务
            new Thread(new SocketDeliver()).start();
            MainActivity.is_server_running = true;
            MainActivity.showToast("Text Send 服务已启动", Toast.LENGTH_SHORT);
        }
    }

    @Override
    public void onDestroyView() {
        // 如果未连接，就断开
        if(!MainActivity.is_server_connected){
            SocketDeliver.stopSocketDeliver();
            MainActivity.is_server_running = false;
        }
        super.onDestroyView();
        binding = null;
    }

    // 返回连接页面
    public static void goServerConfig(){
        NavHostFragment.findNavController(serverQRFragment).navigate(R.id.action_ServerQRFragment_to_ServerFragment);
    }

    //前往发送页面
    public static void goMsg(){
        MainActivity.mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // System.err: android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views.
                NavHostFragment.findNavController(serverQRFragment).navigate(R.id.action_ServerQRFragment_to_ClientMsgFragment);
            }
        });
    }

    /**
     * 生成二维码
     * @param content
     */
    private void createQRCode(String content){
        new Thread(() -> {
            //生成二维码相关放在子线程里面
            Bitmap logo = BitmapFactory.decodeResource(getResources(),R.mipmap.ic_launcher_logo);
            Bitmap bitmap =  CodeUtils.createQRCode(content,600,logo);
            MainActivity.mainActivity.runOnUiThread(()->{
                //显示二维码
                ivCode.setImageBitmap(bitmap);
            });
        }).start();

    }

}