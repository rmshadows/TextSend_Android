package utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

import cn.rmshadows.textsend.MainActivity;
import cn.rmshadows.textsend.ClientMsgFragment;

public class ClientMsgController implements Runnable {
	final static String FB_MSG = MainActivity.FB_MSG;
	final static int MSG_LEN = MainActivity.MSG_LEN;
	final static int SERVER_ID = MainActivity.SERVER_ID;
	final static String AES_TOKEN = MainActivity.AES_TOKEN;

	private static Socket socket;
	private static ObjectOutputStream oosStream;
	private static ObjectInputStream oisStream;
	// 服务器分配的ID
	public static int id;

//	/**
//	 * @deprecated 弃用的构造函数
//	 * @param addr
//	 * @param port
//	 */
//	public ClientMsgController(String addr, int port){
//		// 下面的流是唯一的，否则socket报错
//		try {
//			Socket client = new Socket(addr ,port);
//			socket = client;
//			MainActivity.is_client_connected = true;
//				Looper.prepare();
//			Toast.makeText(MainActivity.mainActivity,"连接成功",Toast.LENGTH_SHORT).show();
//				Looper.loop();//如果启用这个，会陷入死循环
//			oosStream = new ObjectOutputStream(client.getOutputStream());
//			oisStream = new ObjectInputStream(client.getInputStream());
//		} catch (IOException e) {
//			e.printStackTrace();
//			MainActivity.is_client_connected = false;
//		}
//	}

	public ClientMsgController(Socket client){
		// 下面的流是唯一的，否则socket报错
		try {
			MainActivity.is_client_connected = true;
			MainActivity.showToast("连接成功", Toast.LENGTH_SHORT);
			socket = client;
			oosStream = new ObjectOutputStream(client.getOutputStream());
			oisStream = new ObjectInputStream(client.getInputStream());
			Log.d("==>>APP_LOG<<==","客户端监听启动完成");
		} catch (Exception e) {
			e.printStackTrace();
			Log.d("==>>APP_LOG<<==","客户端监听启动失败");
			MainActivity.showToast("客户端监听启动失败", Toast.LENGTH_SHORT);
			MainActivity.is_client_connected = false;
		}
	}

	@Override
	public void run() {
		new Thread(new ClientMsgR(socket, oisStream, oosStream)).start();
	}

	/**
	 * PC端主动发送信息到移动端的方法
	 * 
	 */
	public static void sendMsgToServer(Message m) {
		new Thread(new ClientMsgT(oosStream, m)).start();
	}
}

/**
 * 客户端发送Msg到服务端
 * 
 * @author jessie
 *
 */
class ClientMsgT implements Runnable {
	private Message msg;
	private ObjectOutputStream oos;

	public ClientMsgT(ObjectOutputStream out, Message m) {
		this.msg = m;
		this.oos = out;
	}

	@Override
	public void run() {
		try {
			System.out.print("发送加密后的数据：");
			msg.printData();
			oos.writeObject(msg);
			oos.flush();
		} catch (Exception e) {
			e.printStackTrace();
			MainActivity.is_client_connected = false;
		}
	}
}

/**
 * 客户端接收服务端信息
 * 
 * @author jessie
 *
 */
class ClientMsgR implements Runnable {
	ObjectInputStream ois;
	ObjectOutputStream oos;
	private Socket socket;

	public ClientMsgR(Socket socket, ObjectInputStream in, ObjectOutputStream out) {
		this.ois = in;
		this.oos = out;
		this.socket = socket;
	}

	@Override
	public void run() {
		try {
			boolean get_id = true;
			while (true) {
				Message msg = (Message) ois.readObject();
				if (msg.getId() == ClientMsgController.SERVER_ID) {
					if (get_id) {
						// 获取ID
						ClientMsgController.id = Integer.valueOf(msg.getNotes());
						System.out.println("客户端获取到ID：" + String.valueOf(ClientMsgController.id));
						get_id = false;
					} else {
						if (msg.getNotes().equals(ClientMsgController.FB_MSG)) {
							// 处理反馈信息
							System.out.println("服务器收到了消息。");
							ClientMsgFragment.cleanText();
						} else {
							String text = decryptMsgToString(msg);
							// 反馈服务器
							msgFeedBack(oos);
							System.out.println("收到服务器的消息："+text);
							copyToClickboard(text);
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			MainActivity.is_client_connected = false;
			MainActivity.showToast("连接断开！", Toast.LENGTH_SHORT);
		}
	}

	/**
	 * 将所给的加密msg对象转为解密后的string
	 * 
	 * @param m message类
	 * @return
	 */
	private String decryptMsgToString(Message m) {
		String str = "";
		for (String s : m.getData()) {
			System.out.println("正在解密："+s);
			str += AES_Util.decrypt(ClientMsgController.AES_TOKEN, s);
		}
		return str;
	}

	// 反馈消息到服务端
	private static void msgFeedBack(ObjectOutputStream out) throws IOException {
		System.out.println("客户端发送反馈信息");
			out.writeObject(new Message(null, ClientMsgController.MSG_LEN, ClientMsgController.id, ClientMsgController.FB_MSG));
	}

	/**
	 * 复制收到的消息到剪贴板
	 * 
	 * @param text
	 */
	private static void copyToClickboard(String text) {
		MainActivity.mainActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try{
					//获取剪贴板管理器：
					ClipboardManager cm = (ClipboardManager) MainActivity.mainActivity.getSystemService(Context.CLIPBOARD_SERVICE);
					// 创建普通字符型ClipData
					ClipData mClipData = ClipData.newPlainText("Label", text);
					// 将ClipData内容放到系统剪贴板里。
					cm.setPrimaryClip(mClipData);
				}catch (Exception e){
					e.printStackTrace();
				}
			}
		});
	}
}
