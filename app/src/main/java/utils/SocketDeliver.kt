package utils

import ScheduleTask.ScheduleTask
import android.util.Log
import cn.rmshadows.textsend.viewmodels.ServerFragmentViewModel
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import java.io.IOException
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动一个服务器端口，监听。 分发Socket
 *
 * @author jessie
 */
class SocketDeliver(val tsviewModel: TextsendViewModel, val viewModel: ServerFragmentViewModel) :
    Runnable {
    // 创建一个线程池
    private val executorService = Executors.newFixedThreadPool(10)
    val TAG = Constant.TAG

    override fun run() {
        val lport = viewModel.uiState.value.serverListenPort.toInt()
        Log.i(TAG, "启动移动端Textsend服务(监听：${lport})...")
        try {
            /* backlog是ServerSocket类中的参数，用来指定ServerSocket中等待客户端连接队列的最大数量，并且每调用一次accept方法，就从等待队列中取一个客户端连接出来，因此队列又空闲出一个位置出来，这里有两点需要注意：
                1、将等待队列设置得过大，容易造成内存溢出，因为所有的客户端连接都会堆积在等待队列中；
                2、不断的调用accpet方法如果是长任务容易内存溢出，并且文件句柄数会被耗光。
             */
            server = ServerSocket(
                lport,
                viewModel.uiState.value.maxConnection.toInt()
            )
            // 监听服务是否停止
            scheduleControl.set(true) // 开启定时器
            Thread {
                val Task = Runnable {
                    // 如果服务停止 Socket停止
                    if (!tsviewModel.uiState.value.isServerMode) {
                        stopSocketDeliver(tsviewModel, viewModel)
                        scheduleControl.set(false)
                    } else {
                        // 显示连接数
                        val clientCount = socketList.size
                        // 更新连接数
                        if (!clientCount.equals(viewModel.uiState.value.clientCount)) {
                            if(clientCount.equals(0)){
                                // 连接0就是客户端没连接
                                tsviewModel.update(null,null,false,null,null,null,null)
                            }else{
                                // 连接不为0,且客户端未连接更新状态
                                if(!tsviewModel.uiState.value.isClientConnected){
                                    tsviewModel.update(null,null,true,null,null,null,null)
                                }
                            }
                            viewModel.update(null, null, null, null, null, clientCount)
                        }
                    }
                }
                ScheduleTask(
                    Task,
                    1,
                    1,
                    scheduleControl,
                    TimeUnit.SECONDS
                ).startTask()
            }.start()
            // 控制Socket是否继续分发
            socketDeliveryControl.set(true)
            val SocketDeliveryTask = Runnable {
                // 分发socket
                if (socketList.size < viewModel.uiState.value.maxConnection.toInt()) {
                    val socket: Socket
                    try {
                        Log.i(TAG, "Socket is delivering......")
                        socket = server!!.accept()
                        val client = ServerMessageController(socket, tsviewModel, viewModel)
                        // 断开后删除列表的方法写在ServerMessageController
                        socketList.add(client)
                        // 启动定时任务 如果连接成功则取消运行 不成功就断开Socket
                        val connectTimeout = Runnable {
                            try {
                                Thread.sleep(8000)
                                if (client.connectionStat != 2) {
                                    client.closeCurrentClientSocket()
                                    Log.i(TAG, "连接超时，断开客户端。")
                                } else {
                                    Log.i(TAG, "检测到客户端连接成功")
                                }
                            } catch (e: InterruptedException) {
                                throw RuntimeException(e)
                            }
                        }
                        Thread(connectTimeout).start()
                        executorService.execute(Thread(client))
                    } catch (e: IOException) {
                        socketDeliveryControl.set(false)
                        throw RuntimeException(e)
                    }
                }
            }
            ScheduleTask(
                SocketDeliveryTask, 1, 1, socketDeliveryControl,
                500, 800, TimeUnit.SECONDS
            ).startTask()
        } catch (e: BindException) {
            stopSocketDeliver(tsviewModel, viewModel)
            Log.i(TAG, "Port Already in use.")
        } catch (e: Exception) {
            stopSocketDeliver(tsviewModel, viewModel)
            e.printStackTrace()
        }
    }

    companion object {
        // Socket ID Mode
        val socketList = Collections.synchronizedList(LinkedList<ServerMessageController>())

        // 服务Socket
        var server: ServerSocket? = null

        // 控制定时器停止
        val scheduleControl = AtomicBoolean(false)

        // 控制Socket分发 true为允许分发 false 不允许分发，但保持现有连接
        val socketDeliveryControl = AtomicBoolean(false)

        /**
         * 服务端会把消息广播给所有客户端
         */
        fun sendMessageToAllClients(m: Message?) {
            for (s in socketList) {
                Thread(ServerMessageTransmitter(s!!, m)).start()
            }
        }

        /**
         * 检测到服务端停止，从内部停止socket
         */
        fun stopSocketDeliver(tsviewModel: TextsendViewModel, viewModel: ServerFragmentViewModel) {
            // 关闭服务端Socket
            try {
                server!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            // 关闭所有客户端Socket)(现有连接)
            for (s in socketList) {
                try {
                    s.socket.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // 二次赋值了
            scheduleControl.set(false)
            socketDeliveryControl.set(false)
            // 这里更新UI
            viewModel.update(null, null, null, false, null, null)
            tsviewModel.update(null, null, false, null, null, null, -1)
            Log.i(Constant.TAG, "Socket Server shutdown.")
        }
    }
}