# Root 策略与固定属主维护（0.14.3，PR #308）

> 源码交接，不是验收报告。当前版本声明为 0.14.3 / versionCode 45，尚未构建本轮双 ABI APK；没有本轮 APK/hash/测试通过证据。用户当前停止点为 #308 按正常 GitHub CI/review 合并、完整 0.14.3 同步、双 ABI 测试包交付；外部测试保持未执行，不作为等待合并的占位阻塞。发布未授权。

## 1. 模型执行与应用维护分开

模型 root 执行须满足实际 root 通道、用户开启 AI root 及当前 versionCode 的知情同意。RootGrant.isGranted 是有效授权，不只读原始开关；root Shizuku 与显式授权 su 是替代通道，uid2000 不是 root。升级使旧 consent 失效，重新同意不恢复旧开关；关闭和撤销始终允许。su 只对明确的派发前通道不可用回退一次；拒绝、命令非零 exit、超时、Binder 派发后结果不明不重跑。

应用维护只修固定本应用 dataDir，目标为 ApplicationInfo 的完整 Android UID。su 从已安装签名 APK 的 sourceDir 加载 RootRepairMain；固定五参数 fullUid、dataDir、relativeSubpath、cap、timeoutMs，逐项检查并独立 shell 引用。无可写快照 dex/JAR、任意 uid/gid、任意命令、递归 restorecon 入口。Shizuku v4 追加 configuration 回读确认完整 UID/可信 anchor；旧或未确认服务不派发。

## 2. 耐久租约：先记账，后真正 UID0 派发

[RootMaintenanceLease](<dsh-mobile-apk/app/src/main/java/com/dsharnessmobile/shell/RootMaintenanceLease.kt#L56-L105>) 使用私有 SharedPreferences 同步 commit epoch、startedAt、operation；不能取得启动 epoch 或不能持久化即拒绝派发。su 命令/helper 与 Shizuku 实际 uid0 的每个操作在派发前建立租约。Shizuku RpcLease 在持久化之后再次检查实际 UID 与有效 consent；pull/push 每个 chunk 均在本地准备完毕后、RPC 紧前重新检查，而非只检查传输开始。

租约仅由原始 owner caller 在得到确定完成回执后 finish；普通异常、exit/drain 超时、readError、cleanupIncomplete、Binder 完成未知及清租约 commit 失败均转 UNKNOWN。杀 su 客户端、父进程退出、关闭本地管道或本地 reader 结束，都不能证明特权后代停止。UNKNOWN 阻止新受控 RPC/命令、维护和启动前读取快照事务，不自动重试副作用。

同一 boot 内应用重启/recreate/进程重建仍从耐久记录恢复 UNKNOWN，不能靠 app restart、重置 Shizuku 或手工清除解除。仅 saved/current epoch 均非空、同属 boot-id 或同属 boot-count、且值不同，才证明设备真正重启并允许清除旧租约；来源方案切换、epoch 缺失不算新 boot。没有产品手工清除出口。

su helper 必须有完整 JSON 信封（布尔 ok、非负整数 checked/healed/failures/unverifiedMutations、remaining 为 -1/0、布尔 truncated/deadlineExceeded）及已确认 exit0/2，且 transport 完整、未截断。已确认的失败/部分修复信封可以 finish 租约，但仍返回 repair-incomplete/ok=false；“工作已结算”不等于“修复成功”。信封缺失/畸形不 finish。完整成功还须 exit0、failures=unverifiedMutations=remaining=0、无 cap/deadline 截断。

## 3. 所有权边界与限制

OwnershipRepairCore 在访问前预留预算：上限 200000 entries、64 层、120000ms，默认维护 20000ms；cap/deadline 是变更前约束而非事后统计。Android adapter 用 O_PATH/O_NOFOLLOW pin 元数据，只对普通文件/目录 reopen 已持有 inode；后代经 held directory FD 与校验 basename 访问。fstat 对照身份后 fchown，再 fstat 验证；checked/healed/failures/unverifiedMutations 分别报告，应用 UID 祖先也继续深遍历。

symlink 不跟随；root-origin regular hardlink、foreign owner、跨 device、特殊节点、循环、深度/cap/deadline 与列举/变更异常显式报告。要求 protected_hardlinks=1，未知/关闭拒绝；root-origin group/other-writable regular 拒绝。fstat→fchown 不是抵御另一恶意 root 的原子事务，维护需要特权 namespace quiescence。RootExecutionFence 只串行本应用受控入口，不能锁外部 root 或已获授权命令主动脱离 stdout/父进程的后台后代；任意 root shell 不是进程树沙箱，维护前仍需 namespace quiescence。SELinux 不重标，selinuxRelabeled=false；uid 归一不证明标签或应用读写验收通过。remaining=0 只用于完整成功，部分/未知为 -1。

## 4. I/O、栅栏与共享维护状态

[RootExecutionFence](<dsh-mobile-apk/app/src/main/java/com/dsharnessmobile/shell/RootExecutionFence.kt#L18-L34>) 每次接收 Context 并在锁前/锁内检查耐久租约；公平 ReentrantReadWriteLock 的读锁使用零毫秒 timed tryLock，尊重排队写者，不用会插队的裸 tryLock。维护取写锁，命令取读锁；忙时不排无限等待。

ProcIo 的 destroy/各 stream close 都在独立 daemon cleanup worker，waiter 只按共同 cleanup 预算 join；read/close 不在输出内存锁内。返回 text/flags 是有界不可变部分快照，晚到 reader 不能改已返回结果；readError 不当 EOF。ShizukuCaptureIo 使用执行 UID 拥有的0700目录（root 在本应用 cache，shell 在自身目录），随机名、CREATE_NEW/NOFOLLOW_LINKS 创建 .part；不覆盖已存在输出路径。writer/flush/close 真结束且 exit、drain、cleanup、读取与 cap 均完整后才无覆盖发布；不完整返回 spoolReady=false、空路径与不可变 inline/size，不能作为可取的 ready spool。

RootOwnershipJobs 是进程级 single flight；UI request 立即返回 repair-started/repair-running，root 状态既有轮询读实际结果。state 合并本地 worker 和当前耐久 lease：worker 已返回但 lease 未 finish 仍 running/pending、completedAt=0，UNKNOWN/超30s overdue；结果和 lease 分开呈现，不把旧 complete result 冒充当前租约完成。caller 最多等待30s，不取消共享 worker，也不以 caller 超时释放耐久隔离。相近 Activity/Service 启动仅复用5s内已结算扫描；用户维护与普通重试重新检查。

## 5. 启动生命周期所有权

Activity 的 StartupFlowOwnership 用一份 CAS 状态绑定 running、token/generation、destroyed；旧 finally 不能清新 flow。Service 使用独立 ServiceEpoch，恢复/Binder 等待后每个后续副作用重新检查当前 epoch、instance、停机和 interrupt。销毁/关闭取消该 caller wait 与该 epoch 定时器，不取消 RootOwnershipJobs 共享 root worker。

维护 pending 使用独立有限六次延迟预算 [2,4,8,16,30,30] 秒；耗尽保持 pending，不自旋、不重复 helper，不重置已用预算。它与普通引擎失败的两次5/10秒重试不是同一预算。WatchdogV2 的 WakeLockOwner/EpochResourceOwner 按 epoch 获取、续期、释放；晚到 acquisition 被释放，旧 Service teardown 不能释放新实例锁。

## 6. 外部验证与未覆盖边界

新增 pure core、参数解析、I/O/capture settlement、RPC lease wiring、startup ownership fixtures 已写，未在本次 source/doc 分工执行。源码扫描类 fixture 只证明接线形状，不代替实际 UID0/Binder/设备副作用取证；RootMaintenanceLeaseTest已登记pure newBoot/scheme切换与源码wiring，但durable lease同boot新进程restore、真实boot、commit失败及late acknowledgement仍需外部行为反证。测试需求见 [0.14.3 测试交接](<docs/0.14.3-TEST-REQUIREMENTS.md>)。不得沿用原作者 head 的测试/设备证据，不宣称完整三层、安全或可发布验收通过。
