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

import cn.rmshadows.textsend.databinding.ClientConfigFragmentBinding;
import cn.rmshadows.textsend.databinding.ServerConfigFragmentBinding;

public class ServerFragment extends Fragment{

    private ServerConfigFragmentBinding binding;
    private static Fragment fragment;
    private static EditText server_port;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        MainActivity.page_view_index = -1;
        binding = ServerConfigFragmentBinding.inflate(inflater, container, false);
        fragment = ServerFragment.this;
        server_port = binding.serverPort;

        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(server_port.getText().toString().equals("")||server_port.getText().toString().equals(null)){
                    MainActivity.server_port = "54300";
                }else{
                    MainActivity.server_port = server_port.getText().toString();
                }
                NavHostFragment.findNavController(fragment)
                        .navigate(R.id.action_ServerConfigFragment_to_ServerQRFragment);
            }
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
    public static void goClient(){
        NavHostFragment.findNavController(fragment)
                .navigate(R.id.action_ServerConfigFragment_to_ClientConfigFragment);
    }

}
