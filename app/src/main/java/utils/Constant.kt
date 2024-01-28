package utils

class Constant {
    companion object {
        // Public
        // 单个Msg拆分的长度
        const val MSG_LEN = 1000
        // 加密用的Token
        const val AES_TOKEN = "cn.rmshadows.TS_TOKEN"
        // TAG
        const val TAG: String = "==>>APP DEBUG<<=="
        // 清除文本的标致
        const val CF = "㊒㊚㊖㊛"

        // Server
        // 服务器消息自带的ID
        const val SERVER_ID = "-200"
        // 服务器成功接收的反馈信息
        const val FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback"
    }
}