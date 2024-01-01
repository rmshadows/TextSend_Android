package cn.rmshadows.textsend.fragments

import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.NavHostFragment
import cn.rmshadows.textsend.R
import com.google.zxing.Result
import com.king.camera.scan.AnalyzeResult
import com.king.camera.scan.CameraScan
import com.king.camera.scan.analyze.Analyzer
import com.king.zxing.BarcodeCameraScanFragment
import com.king.zxing.DecodeConfig
import com.king.zxing.DecodeFormatManager
import com.king.zxing.analyze.QRCodeAnalyzer


class QrScannerFragment : BarcodeCameraScanFragment() {
    /**
     * 初始化CameraScan
     */
    override fun initCameraScan(cameraScan: CameraScan<Result?>) {
        super.initCameraScan(cameraScan)
        // 根据需要设置CameraScan相关配置
        cameraScan.setPlayBeep(true)
    }

    /**
     * 创建分析器；由具体的分析器去实现分析检测功能
     */
    override fun createAnalyzer(): Analyzer<Result?> {
        //初始化解码配置
        val decodeConfig = DecodeConfig()
        //如果只有识别二维码的需求，这样设置效率会更高，不设置默认为DecodeFormatManager.DEFAULT_HINTS
        decodeConfig.setHints(DecodeFormatManager.QR_CODE_HINTS)
            //设置是否全区域识别，默认false
            .setFullAreaScan(false)
            //设置识别区域比例，默认0.8，设置的比例最终会在预览区域裁剪基于此比例的一个矩形进行扫码识别
            .setAreaRectRatio(0.8f)
            //设置识别区域水平方向偏移量，默认为0，为0表示居中，可以为负数
            .setAreaRectVerticalOffset(0).areaRectHorizontalOffset = 0
        // BarcodeCameraScanActivity默认使用的MultiFormatAnalyzer，这里可以改为使用QRCodeAnalyzer
        return QRCodeAnalyzer(decodeConfig)
    }

    /**
     * 扫描结果回调；分析后得到的结果
     */
    override fun onScanResultCallback(result: AnalyzeResult<Result>) {
        // 停止分析
        cameraScan.setAnalyzeImage(false)
        // 继续分析
        // cameraScan.setAnalyzeImage(true)
        // 返回结果
        val qrResult = result.result.text
        // 处理扫码结果相关逻辑（此处弹Toast只是为了演示）
        // val toast: Toast? = null
        // toast?.setText(qrResult)
        // toast?.show()
        // Use the Kotlin extension in the fragment-ktx artifact. 对应识别符"QRCODE" key是ScanResult
        setFragmentResult("QRCODE", bundleOf("ScanResult" to qrResult))
        // 返回客户端连接界面
        NavHostFragment.findNavController(this).navigate(R.id.action_qrScannerFragment_to_ClientFragment);
    }
}