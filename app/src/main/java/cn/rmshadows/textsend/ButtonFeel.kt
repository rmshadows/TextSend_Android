package cn.rmshadows.textsend

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * 按下立刻轻震，并且不吃掉触摸，水波纹还能出来。
 * 布局每次变动时给新出现的按钮补上，代码里 new 出来的按钮再调一次 arm。
 */
object ButtonFeel {
    fun install(activity: AppCompatActivity) {
        val decor = activity.window.decorView
        if (decor.getTag(R.id.tag_btn_feel) == true) {
            return
        }
        decor.setTag(R.id.tag_btn_feel, true)
        decor.viewTreeObserver.addOnGlobalLayoutListener { armTree(decor) }
    }

    fun arm(view: View) {
        if (view.getTag(R.id.tag_btn_feel) == true) {
            return
        }
        // 消息页等已绑按住手势的按钮只有一个 OnTouchListener 槽，不能再盖
        if (view.getTag(R.id.tag_btn_hold) == true) {
            return
        }
        view.setTag(R.id.tag_btn_feel, true)
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && v.isEnabled) {
                v.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            }
            false
        }
    }

    private fun armTree(view: View) {
        if (view is Button) {
            arm(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                armTree(view.getChildAt(i))
            }
        }
    }
}
