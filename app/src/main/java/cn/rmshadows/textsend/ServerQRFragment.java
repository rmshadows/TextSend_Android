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

import com.king.zxing.util.CodeUtils;

import cn.rmshadows.textsend.databinding.ServerQrFragmentBinding;
import utils.SocketDeliver;

public class ServerQRFragment extends Fragment {
    // Do not place Android context classes in static fields (static reference to ServerQRFragment which has field tvTitle pointing to TextView); this is a memory leak
//    private static ServerQRFragment serverQRFragment;
    private TextView tvTitle;
    private ImageView ivCode;

    private ServerQrFragmentBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        MainActivity.pageViewIndex = -2;
        binding = ServerQrFragmentBinding.inflate(inflater, container, false);
        // 绑定控件
//        serverQRFragment = ServerQRFragment.this;

        super.onCreate(savedInstanceState);
        ivCode = binding.imageView;
        tvTitle = binding.tvHint;

        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 生成二维码
        String QR_code = MainActivity.preferIpAddr + ":" + MainActivity.getServerListenPort();
        tvTitle.setText(QR_code);
        createQRCode(QR_code);
        // 启动服务
        if (MainActivity.isServerRunning()) {
            if (MainActivity.isServerConnected) {
                SocketDeliver.stopSocketDeliver();
            }
        } else {
            // 启动服务
            new Thread(new SocketDeliver()).start();
            MainActivity.setServerRunning(true);
            MainActivity.showToast("Textsend 服务已启动", Toast.LENGTH_SHORT);
        }
    }

    @Override
    public void onDestroyView() {
        // 如果未连接，就断开
        if (!MainActivity.isServerConnected) {
            SocketDeliver.stopSocketDeliver();
            MainActivity.setServerRunning(false);
        }
        super.onDestroyView();
        binding = null;
    }

    // 返回连接页面
//    public static void goServerConfig() {
////        NavHostFragment.findNavController(ServerQRFragment.this).navigate(R.id.action_ServerQRFragment_to_ServerFragment);
//        NavHostFragment.findNavController(serverQRFragment).navigate(R.id.action_ServerQRFragment_to_ServerFragment);
//    }

    //前往发送页面
//    public static void goMsg() {
//        MainActivity.mainActivity.runOnUiThread(() -> {
//            // System.err: android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views.
//            NavHostFragment.findNavController(serverQRFragment).navigate(R.id.action_ServerQRFragment_to_ClientMsgFragment);
//        });
//    }

    /**
     * 生成二维码
     *
     * @param content 内容
     */
    private void createQRCode(String content) {
        new Thread(() -> {
            //生成二维码相关放在子线程里面
            Bitmap logo = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher_logo);
            Bitmap bitmap = CodeUtils.createQRCode(content, 600, logo);
            MainActivity.mainActivity.runOnUiThread(() -> {
                //显示二维码
                ivCode.setImageBitmap(bitmap);
            });
        }).start();
    }

}