# Hikarinagi Suwayomi / Mihon 扩展

支持热门、最近更新、搜索、漫画详情、卷／话／番外目录和逐页阅读。

## 登录

- **Suwayomi-Server**：在 WebUI 上传 APK。启用服务端 KCEF WebView，从 Hikarinagi 图源打开 Suwayomi 的 WebView，在官网登录，等待跳回官网后关闭 WebView。扩展复用 Suwayomi 的 Cookie 容器。用客户端浏览器单独打开官网时，会话保存在客户端浏览器的 Cookie 容器里。
- **Mihon**：安装 APK 并在扩展列表中信任它。在 Hikarinagi 图源菜单中选择「在 WebView 中打开」并登录官网。

如果 Suwayomi 没有服务端 WebView，在普通浏览器登录官网，从开发者工具「网络」里选一个发给 `www.hikarinagi.org` 的请求，复制其请求头中的 `Cookie` 值，再到 **Hikarinagi 图源设置 → Hikarinagi 登录 / 导入会话** 粘贴保存。只接受官网的 `hikari_access_token`、`hikari_refresh_token` 和 `hikari_id_token`，由宿主的 Cookie 容器保存。Cookie 相当于登录凭证，请在自己的 Suwayomi 实例操作。输入「清除」可清除扩展会话。

登录后刷新图源或重新打开章节。旧版已缓存的「需登录」标签需要刷新漫画章节列表。阅读权限和会话有效性由官网在打开章节时验证。

在登录设置中输入「验证」后，扩展会通过同一个限速队列请求一次官网账号接口。重新打开设置即可看到验证结果和账号昵称。验证结果保存在宿主进程内，打开设置本身不会访问官网。

## 请求速度

扩展发起的请求一次只执行一条，响应体完整接收后至少等待 1.5 秒。包括列表、封面、详情、章节、页清单和单页图片。站点 WebView 自身加载页面所发出的请求由 WebView 管理。

- HTTP 429 或站点限流错误至少暂停 2 分钟。收到 `Retry-After` 或限流重置时间时，以更晚的时间为准。
- HTTP 403 至少暂停 5 分钟。服务异常、网络故障和会话限制也会触发暂停。连续异常会延长等待时间。
- 暂停期间排队和新发起的请求在本地失败。冷却后手动重试，扩展没有后台重试任务。冷却计时保存在宿主进程内，若重启宿主，请继续等到提示的时间再试。

## 构建

使用 JDK 17 和 Android SDK 35，设置 `JAVA_HOME`、`ANDROID_HOME`。Windows 下运行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

产物为 `build/outputs/apk/debug/hikarinagi-mihon-extension-debug.apk`，使用本机的调试签名。APK 的应用名称使用字符串资源，以便 Suwayomi 的 APK 解析器可以读取资源表。
