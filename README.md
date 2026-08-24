 ### TextSend_Android

 - 手机、电脑文字互传、手机与手机之间文字互传。
 - 安卓版本：8.0+
 - 电脑服务端传送门：[Gitee](https://gitee.com/rmshadows/TextSend_Desktop)   [Github](https://github.com/rmshadows/TextSend_Desktop)
 - uTools插件传送门：[Github](https://github.com/rmshadows/TextSend_Utool)
 - 当前版本：5.0.10

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

### 连不上电脑（安卓 14 / 努比亚等）

手机和手机互连、安卓 9 连电脑通常正常。部分安卓 14（实测努比亚 Z60 Ultra / MyOS）**连 Linux/Windows 电脑会握手失败**，提示像「请确认 PIN」——多半不是 PIN 错了。

原因：厂商「网络加速 / 智能双通道」会同时用 Wi‑Fi 和蜂窝。TCP 能建连，电脑的 HELLO 也能到手机，但手机发出的握手数据被加速模块拿走、走不了局域网，电脑一直读不到。飞行模式关掉蜂窝后，只剩 Wi‑Fi，立刻正常。重启后也能连一小会儿（加速还没挂上）。

连电脑前请：

1. 打开**飞行模式**，再单独打开 **Wi‑Fi**（不要开移动数据）。
2. 或关闭：**网络加速 / 智能双通道 / 游戏加速 / 应用加速**。
3. 电脑若有多块网卡（有线 + USB 网卡）都在 `192.168.x`，尽量只留一张，避免进出走不同网卡。

连上后状态栏可能有一条保活通知，选相册时不要划掉。

### 如何打开 / 查看日志

应用没有单独的「日志开关」，连上 USB 后用 logcat 即可。握手、文件发送都会打到这里（不会打印 PIN / 长密钥 / aesKey）。

**Android Studio**

1. 手机打开「开发者选项」→「USB 调试」，用数据线连电脑。
2. 用 Android Studio 打开本工程，或只开 Logcat 窗口。
3. 顶部设备选中这台手机，包名过滤：`cn.rmshadows.textsend`。
4. 搜索框再滤：`==>>APP DEBUG<<==` 或 `【握手】` / `【发送】` / `【接收】`。

**命令行（已装 platform-tools）**

```bash
adb logcat -v time -s "==>>APP DEBUG<<==" Textsend
```

只看握手：

```bash
adb logcat -v time | grep -E "握手|TCP 已通|FILE_META|session ready"
```

重现问题前先清一下再抓：

```bash
adb logcat -c
adb logcat -v time -s "==>>APP DEBUG<<==" Textsend
```

电脑端同时看控制台里的 `Log: 【握手】` / `timeout after 0/8 bytes`。若手机已「TCP 已通」且电脑是 `0/8`，就是上面说的加速把上行数据吃掉了，优先飞行模式 + 只开 Wi‑Fi。

### 更新日志

5.0.0 起为协议 v1（二进制帧 / AES-GCM / `ts://`），与旧 4.0.x JSON 协议不互通。

- 2026.8.23——5.0.10
  - 长按「发送」改为打勾选文件夹；磁盘上的真实长文件名会照发
  - 应用内查看可进入隐藏目录（如 `.logger`）
  - 设置里点「切换」不再闪退
  - 列表为空时用系统选择器，不申请全部存储权限
  - 主题：默认 / 暗色 / 纸墨 / 石板（设置里切换会重建界面）
  - 传输页「文件」「发送」在顶部；客户端 IP/端口/PIN 描边框；服务端 PIN 单独按钮（点复制短连接串，长按只复制数字）

- 2026.8.22——5.0.9
  - 可收文件夹树；长按「发送」或从文件管理器分享文件夹，按相对路径发出（不限个数）
  - 应用内查看可一键清空接收目录
  - 设置文案对齐：缓存只拷 &lt;16MB；连接通知关不掉

- 2026.8.22——5.0.8
  - 选文件不再闪退；大文件不进 cache；返回键先断开
  - 大文件 ACK 超时与选相册掉线
  - 安卓 14 连电脑：飞行模式 + Wi‑Fi；README 补充 logcat

- 2026.8.21——5.0.7
  - 文件投送：消息页「文件」多选（最多 50）；进度/速度/取消
  - 收到的文件落到系统下载/`TextSend`（写不进则应用专属 Download/TextSend）；图片也落地，不进系统剪贴板
  - 与 PC 5.0.42 同一套 FILE_* / 续传；可收电脑发来的文件夹树；本机不发文件夹

- 2026.8.20——5.0.6
  - 服务端端口占用不再闪退，改为提示「端口已被占用」

- 2026.8.20——5.0.5
  - 服务端未启动时点端口号即可改端口

- 2026.8.20——5.0.4
  - 废地址留在服务端列表末尾、默认不选；客户端连失败/握手失败短提示

- 2026.8.20——5.0.3
  - 服务端复制连接串/PIN；客户端默认 `192.168.`；设置里后台保活（默认关）

- 2026.8.20——5.0.2
  - 客户端 IP/端口/PIN·密钥可改；粘贴 `ts://` 与扫码自动填入

- 2026.8.20——5.0.1
  - 修 ACK 超时（先回 ACK 再改 UI）；消息页重建不拆连接

- 2026.8.18——5.0.0
  - 对齐协议 v1：扫码 `ts://…/kPSK`、手输 IP+PIN；文字 TEXT+ACK

- 2024.2.15——4.0.5
  - 修复了服务端端口号无法更改的bug
  
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



