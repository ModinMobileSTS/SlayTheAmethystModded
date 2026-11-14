# Steam CM 会话与退出登录

## 所有权

- `SharedSteamCmSessions` 是 Android 进程内共享入口；主进程的创意工坊/下载、`:steamcloud` 的云同步、`:game` 的在线状态各自持有一条共享连接。
- `SharedSteamCmSession` 串行合并登录，借用句柄关闭不影响其他调用。显式传入的账号只是声明，必须与当前凭据一致；每次 RPC 也检查账号归属。
- 登录失败后同一账号有 30 秒连接冷却，防止排队任务反复鉴权。重新登录、账号变化或创建新的会话管理器不继承旧冷却。
- `invalidate()` 永久撤销管理器与已有借用句柄。下一次获取入口创建新管理器，不能让旧句柄恢复。
- `OkHttpSteamCmSession.close()` 是终态：清除重连计划、关闭 socket、终止待处理握手/登录/RPC、取消心跳作用域。普通网络故障保留有界重试，但每次重试与发送登录包前必须校验凭据。

## 退出顺序

`SteamAccountLogoutCoordinator` 先原子更新无凭据的 `steam-cm-account-epoch`，再暂停下载队列、撤销本进程连接、清理网页会话、停止在线状态及云同步，最后在云操作锁内切换独立存档并清空凭据/缓存。

这个标记不需要等待云同步持有的操作锁。其他进程通过 `FileObserver` 立即关闭共享 CM，发送和重连时的校验也会拒绝旧 epoch。新创建的管理器只能使用与当前 epoch 一致的凭据，否则匿名连接。标记保留在磁盘上，所以清理中断或进程重启不能复活旧凭据。

登录开始时记录 epoch，提交时核对；退出前开始的登录不能在退出后把凭据写回来。只有本地存档迁移能读取被撤销的账号身份，以免把存档保存到错误账号目录。清理步骤分别执行并汇总错误，不因存档切换或某个缓存清理失败而跳过凭据清理。游戏持有存档租约时，保留账号 ID 并延迟独立存档切换，不删除本地存档。

下载暂停保留分块和历史，退出不会删除已安装模组。用户可以手动恢复，后续新任务仍可匿名下载。撤销/取消不能触发公共 CDN 降级或账号过期提示。

## CM 登录失败提示

带账号的 CM 登录（包括底层自动重连）只有在 Steam 明确返回凭据拒绝时，才上报 `SteamCmLoginFailureNotice`：ClientLogon 的 EResult 5（InvalidPassword）、27（Expired）、106（InvalidSignature）、109（ExpiredLoginAuthCode）。由 `isSteamCmCredentialRejection` 统一分类，不匹配异常文案，也不把泛化 AccessDenied 当成过期证据。DNS/TLS/握手/超时/断线/节点路由不可用、EResult 20、84/87 限流、未知错误、匿名连接失败和主动取消均不触发账号过期提示。失败事件保存在无 token 的小型文件中，使用跨进程锁去重，并按账号、凭据版本及 epoch 校验。

启动器统一显示：**Steam CM 登录失败，Steam 账号登录状态疑似过期，建议重新登录账号。** 提供登录入口；同一凭据版本关闭后不重复弹出，重新登录/退出会清理旧提示。CM 登录失败不走云同步原有的强制退出分支，避免网络故障直接清除账号或覆盖专用提示。

`SteamProtocolException.isCmLogonFailure` 仍只标记失败发生在登录阶段，不表示凭据失效；网络失败也保留该标记，避免云同步回落到旧强制退出逻辑。提示回调的触发条件与这个阶段标记独立。

新提示带 `classificationVersion=1`；没有分类版本的旧持久化提示不再显示，避免升级后仍弹出历史网络故障误报。后续收到明确凭据拒绝时可以覆盖旧记录，正常去重和关闭逻辑不变。

## Steam 游玩状态恢复

- 游戏运行时回到前台，`GameSessionCoordinator.onResume()` 会重新请求 `SteamGamePresenceService.startIfEnabled()`。已有工作线程只唤醒检查，不重复创建线程或登录；服务已停止时则重新启动。游戏尚未就绪或正在退出时不启动。
- 上报服务在登录后监听当前 CM 会话变化，并每 30 秒检查一次空闲会话。断线恢复不依赖楼层、主菜单或 Rich Presence 文件发生变化。共享 CM WebSocket 每 30 秒发送 ping，以发现后台网络暂停后的半开连接；ping 不替代 Steam 协议心跳。
- `SteamGamePresenceSession` 按 CM 会话身份去重。新会话按顺序重发在线状态、AppID 646570 和最新游戏详情；同一连接内的前台恢复不会重复发送，详情发生变化时只发详情。其他功能先重连了共享 CM，也必须重发该会话的游戏状态。
- 网络/路由故障、限流以及单次 60 秒登录超时在原前台服务内重试，间隔为 30、60、120、240、300 秒，最高 5 分钟；成功后重置。回前台可缩短长退避，但保留至少 30 秒的共享登录冷却，文件变化不会绕过退避。登录超时只取消该次尝试，不关闭整个服务。
- 启动检查、周期循环、重新登录及每次发送均使用 `SteamCloudAuthStore.readCmAuthMaterialWithStatus()` 的状态契约：成功读取但无有效凭据、已撤销的 epoch 与临时文件/KeyStore 不可读分开处理。临时不可读也允许启动恢复服务；运行中不可读则停止发送、释放当前 presence 客户端且不发送清理 RPC，按同一有界退避重新读取，成功后重新借用 CM 并重发状态。旧的 nullable 读取接口保持原语义。
- `SteamGamePresenceLoop` 是服务实际使用的恢复循环，只跨重试保留账号身份、凭据版本和 epoch，不缓存 token。登录和每次发送前重新读取并校验；共享 CM 内层 nullable 校验造成的撤销异常也须重新核对状态，不能把临时读取失败误作登出。epoch 变化优先于加密文件读取错误，账号或凭据版本变化终止旧 worker，不复用旧 session。
- 游戏结束、关闭功能、确认无有效凭据、账号被撤销、明确凭据拒绝或主动取消时停止，不复活旧凭据、不无限重试失效账号。显式登出仍通过既有 epoch 发布、CM 撤销和 `stopService()` 立即停止，包括正在退避的服务。正常结束仍清除 Steam 游戏状态，并取消连接观察。
- 客户端交给 loop 后由 worker 唯一关闭；`onDestroy()` 只请求停止并中断，不抢先消费客户端引用。正常停止（含同步/等待期间中断）在重新确认原账号、凭据版本和 epoch 后尝试一次清理；清理时暂存并恢复线程中断标记。已观察到凭据不可读/撤销/拒绝的客户端禁止清理，即使停止同时发生、后续读取已恢复也不发送清理 RPC。
- 同进程的新旧 Service 实例通过 `SteamGamePresenceOwnership` 交接 presence 所有权。主线程仅在真正启动新 worker 前原子 claim，不等待覆盖网络 IO 的锁；重复唤醒已有 worker 不 claim，延迟起跑的旧 worker 也不能自行 claim。登录、各类上报与清理共用 worker 侧锁并在锁内校验 owner：旧 worker 若还未开始清理，新 owner 上传后旧 worker 只能无清理释放；旧清理已持锁时，新 claim 立即返回，但新 worker 的上报要等旧清理完成，因此旧 `AppID=0` 不会落在新上报之后。旧 owner 结束只 CAS 释放自己的身份，不撤销新 owner。锁顺序统一为进程 ownership gate → Service 的 `clientLock`，主线程不获取 gate。
- 这里恢复的是 Steam 的“正在游玩”状态，没有断网期间累计时长的本地补报队列，不能保证补回已断开的时段。

## 验证

- `SharedSteamCmSessionTest`：并发登录合并、旧账号快照、排队/进行中撤销、账号切换、匿名恢复、失败冷却。
- `OkHttpSteamCmSessionLogonTest`：关闭后禁止复活、进行中 RPC/登录撤销、旧凭据禁止重连、明确凭据拒绝才通知；DNS/TLS/reset/超时/路由不可用/限流/未知返回码及重连网络失败均不通知；匿名及取消不通知。
- `UgcWorkshopDownloaderRateLimitTest`：撤销不可降级至公共 CDN。
- `SteamAccountRevocationInstrumentedTest`：隔离目录验证持久化撤销、登录提交竞态、提示去重与清理、忽略旧未分类提示并允许明确拒绝替换；不使用真实账号、不请求 Steam。
- `SteamGamePresenceSessionTest`：首次上报顺序、前台重复检查去重、详情不变的断线恢复、共享连接被其他功能重连、首次借用必须登录、登录/详情失败和上传时断线。
- `SteamGamePresenceRetryPolicyTest`：退避上限、成功重置、前台恢复仍保留冷却、网络/限流/登录超时可重试、凭据拒绝/撤销/取消终止。
- `SteamGamePresenceLoopTest`：运行成功→读取失败→有界退避→重新读取/新客户端恢复；启动读取失败、登录前/发送间隙读取失败、共享 CM 内层校验失败；明确登出、epoch 撤销（含加密文件不可读）、退避期间登出再登录、账号切换均不复用旧会话；已上报后在同步/等待期间停止或中断只清理一次，读取失败/撤销与停止竞态不发清理 RPC，退出循环后的清理也重新检查凭据。测试执行服务同一生产循环及 AuthStore 的 epoch 状态读取 helper，无 Android 服务或真实 KeyStore。
- `SteamGamePresenceOwnershipTest`：两个真实生产 loop/helper 共享同一模拟 CM session identity，验证旧清理延迟到新上传后不会清掉新 AppID、旧清理持锁时 claim 非阻塞而新上报串行等待、已过循环检查的旧同步不得继续发送、旧线程晚启动或在新 owner 完成后启动均不能夺回所有权。原单实例停止与凭据边界测试也使用同一 gate。
- `SteamCloudAuthStoreTest`：状态读取区分确认缺失与不可读，成功/失败读取期间的 epoch 撤销均拒绝凭据，epoch 自身不可读时不返回凭据。
- `SteamCloudProtocolClientSessionObserverTest`：空闲时会话断开/重连立即通知及观察取消，不使用真实账号、不请求 Steam。
