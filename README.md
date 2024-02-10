 ### TextSend_Android

 - 手机、电脑文字互传、手机与手机之间文字互传。
 - 安卓版本：8.0+
 - 电脑服务端传送门：[Gitee](https://gitee.com/rmshadows/TextSend_Desktop)   [Github](https://github.com/rmshadows/TextSend_Desktop)
 - uTools插件传送门：[Github](https://github.com/rmshadows/TextSend_Utool)
 - 当前版本：4.0.4

>注意：说明文档虽然是旧的，但是大致用法没变。

 ### 使用方法：

 #### 手机连接电脑

 1. **首先** ！！电脑和手机要在 **同一个局域网** ！！比如同一个Wifi。

 1. 配置好电脑服务端后(电脑端应用主界面点了“启动”)， 3.1.0版本以上可以弹出二维码，点击右下角图标进行二维码扫一扫连接。请注意：这里的二维码是服务端猜测的服务端IP地址，如果与实际不同请手动修改。

![MainUI](https://images.gitee.com/uploads/images/2021/0908/211045_fe3f87bb_7423713.png "屏幕截图.png")

![QR-scan](https://images.gitee.com/uploads/images/2021/0908/211219_8e71af41_7423713.png "屏幕截图.png")

 1. 

 1.关于如何查看电脑实际IP地址：

 Windows：打开CMD，输入`ipconfig`

 ![ipconfig](https://images.gitee.com/uploads/images/2020/0711/155301_98c7745d_7423713.png "屏幕截图.png")

 或者 -右下角“网络和Internet设置”-“更改适配器选项”-选择网卡，右击-“状态”-“详细信息”-“查看IPv4”-

 ![1](https://images.gitee.com/uploads/images/2020/0711/155531_7fd5d3ef_7423713.png "屏幕截图.png")

 ![2](https://images.gitee.com/uploads/images/2020/0711/155700_10b63197_7423713.png "屏幕截图.png")

 ![3](https://images.gitee.com/uploads/images/2020/0711/160339_2e853f88_7423713.png "屏幕截图.png")

 Linux:很简单：`ifconfig`

 1. 连接成功后便可以互传消息，电脑发送到手机的文字存在手机的剪贴板，点击粘贴就行了。

![ClientSend](https://images.gitee.com/uploads/images/2021/0908/211524_ee48d821_7423713.png "屏幕截图.png")

 #### 手机连接手机

首先，两台手机在同一个Wifi下，一台手机点击 菜单-切换模式。

![Mode](https://images.gitee.com/uploads/images/2021/0908/211656_f0dd7974_7423713.png "屏幕截图.png")

来到服务端页面：

![ServerUI](https://images.gitee.com/uploads/images/2021/0908/211716_b0b58e7d_7423713.png "屏幕截图.png")

点击Start启动服务

![ServerStart](https://images.gitee.com/uploads/images/2021/0908/211746_50d802c4_7423713.png "屏幕截图.png")

掏出另一台手机扫描二维码进行连接。连接成功后会有提示信息：

![connected](https://images.gitee.com/uploads/images/2021/0908/211838_6462f861_7423713.png "屏幕截图.png")

注意：这里的二维码依然是猜测的ip，实际IP可以查看 设置-无线网-无线网信息中的IPv4地址:

![IP-Phone](https://images.gitee.com/uploads/images/2021/0908/212007_c6542afb_7423713.png "屏幕截图.png")

### 更新日志

- 2024.2.10——4.0.4
  - 修复了Object模式下接收器首次接收失效的bug
  - 修复了Object模式下接收器出现信息冗余问题

- 2024.2.9——4.0.3
  - 修改Object模式的接收器、发射器

- 2024.01.25——4.0.2（dev）
  - 尝试适配uTool插件端，引入JSON传输模式+Object传输模式
  - 支持IPv6
- 2021.09.08——3.1.3
  - 重构了应用
  - 发布aab & apk软件包
  - 添加了服务端功能
- 2020.07.15——2.1.0
  - 旧版本

### 开发备忘录

- APP版本：
  - `build.gradle.kts`
  - `strings.xml`

 ### 截屏

 ![ss](https://images.gitee.com/uploads/images/2020/0711/160554_6d64861e_7423713.png "屏幕截图.png")

 ![icon](https://images.gitee.com/uploads/images/2021/0908/212040_06084dbc_7423713.png "屏幕截图.png")

 ![show1](https://images.gitee.com/uploads/images/2021/0908/212101_aa78d419_7423713.png "屏幕截图.png")

 ![show2](https://images.gitee.com/uploads/images/2021/0908/212123_a8f7d202_7423713.png "屏幕截图.png")

 ![show3](https://images.gitee.com/uploads/images/2021/0908/212145_cf27dbb7_7423713.png "屏幕截图.png")

 ![show4](https://images.gitee.com/uploads/images/2021/0908/211746_50d802c4_7423713.png "屏幕截图.png")



