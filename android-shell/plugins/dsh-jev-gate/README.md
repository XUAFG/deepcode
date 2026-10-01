# dsh-jev-gate

审批接缝的**机器应答者**：用 TypeSafe 的 [Jev](https://docs.typesafe.ai)（System One 判断模型）
判定待批准的命令是否安全，高置信度「干净」就直接放行，其余一律落回人类确认。

## 为什么需要它

部署里 `dsh-user-approval` 只组合了**人类**应答者。每次沙箱升级（`sandbox_permissions`）
都要停下等人点确认，Agent 的整个回合被挂住。Jev 的设计定位正是这种「有界判断」：
延迟 70–500 ms、输出免费，且一次请求可并行问多个问题。

**Jev 不会让模型生成得更快**，它省掉的是「等你点确认」的那段停顿。这是它唯一的作用，
不要指望它加速推理本身。

## 默认是安全的

出厂 `mode: 'observe'`：**只记录、绝不授权**。它会把你原本会看到的每一次审批写进
`$DSH_HOME/jev-gate.log`，但返回值和没装它一样。

确认日志符合预期后再启用：

```sh
# 在应用内置控制台里执行（也可以直接写文件）
printf '%s' '{"mode":"enforce"}' > "$DSH_HOME/jev-gate.json"
```

改完立即生效，不需要重启、不需要重打包——配置在每次决策时按 mtime 重读。

## 两道闸门

**1. 本地拒绝列表（先于网络）**。类别性危险的命令形状永远不会自动放行，且**不会外发给 Jev**
（既是安全也是隐私）。覆盖 `rm -rf /`、`mkfs`、`dd of=/dev/block/*`、`sudo`、
`curl | sh`、`reboot`、包管理器增删、fork bomb、`git reset --hard` / `push --force`、
`npm publish` 等。完整清单见 `src/policy.js` 的 `DEFAULT_HARD_DENY`，每条都带 id 便于审计。

**2. Jev 判定**。只有 `risk ≤ allowThreshold` **且** `confidence ≥ minConfidence` 才返回
`allowed-once`。任何缺失——无裁决、无命令文本、置信度不足、概率非有限值、响应形状不认识、
超时、非 2xx、没有 key——一律 `defer`。

`defer` 就是 `next()`，也就是**你现在的行为**。所以最坏情况下这个插件等于不存在。

## 两处接缝，分工是刻意的

| 接缝 | 角色 |
|---|---|
| `tools/pre-execute` | **纯被动观察**。永远 `return next()`，不可能改变任何决策；只按 `callId` 记住工具参数。`prepend: true` 保证记录先于执行发生。 |
| `approval/request` | **决策点**。返回 `'allowed-once'` 授权，或 `next()` 交给人类。 |

为什么需要两处：`approval/request` 的事件只带 `toolName` / `callId` / `reason`，
**刻意不含参数**（源码注释："arguments are not duplicated here"）。要靠 `callId` 关联回
`tools/pre-execute` 记录的参数，才看得到真正要执行的命令。

## 配置

`cordis.patch.yml` 里的 `config` 提供默认值；`$DSH_HOME/jev-gate.json` 覆盖它（优先级更高）。

| 字段 | 默认 | 含义 |
|---|---|---|
| `mode` | `observe` | `off` / `observe` / `enforce` |
| `provider` | `openrouter` | `openrouter` 或 `typesafe`，决定端点与模型 id 默认值 |
| `endpoint` | OpenRouter Decisions | 绝对 URL，可指向任何兼容网关 |
| `model` | `typesafe/jev-1.13` | 端点接受的具体 id |
| `apiKeyRef` | `JEV_API_KEY` | 环境变量名 / 凭据引用名 |
| `apiKeyFile` | 空 | 兜底：纯文本密钥文件路径 |
| `tools` | `bash` 等 | 允许判定的工具白名单 |
| `allowThreshold` | `0.1` | 风险概率上限，超过则 defer |
| `minConfidence` | `0.75` | 置信度下限，低于则 defer |
| `timeoutMs` | `4000` | 单次调用的最后期限 |
| `hardDenyPatterns` | 内置清单 | 覆盖内置拒绝列表（`{id, re}` 数组） |

### 密钥来源，按顺序

1. `process.env[apiKeyRef]`
2. `ctx.credentials.resolve(apiKeyRef)`（应用的凭据存储）
3. `apiKeyFile` 指向的文件

**密钥永远不进日志**：`diag` 只记录 `<set:长度>` 形式的脱敏标记。

## 注意

- **本插件只在 `web` profile 上挂载。** 本轮的构建链（`scripts/first-build.py` →
  `stage-upstream-experiment.py`）只组装 `runtime/home/.dsh/profiles/web`，`headless` 保持基线快照原样。
  另一条链（`android-shell/scripts/inject-all.py`，其 `PROFILES` 为 `web` + `headless`）会用同一份权威
  patch 覆盖两者——若日后切到那条链，注意 `headless` 是无人值守姿态、**没有人类应答者**，
  在那里开 `enforce` 等于让模型独自决定放行，请勿在不清楚后果时这么做。
- `headless-bad` 是负控夹具，**不得**被本插件覆盖。
- Jev 是第三方托管服务：启用 `enforce` 意味着把**命令文本**发给 TypeSafe 或 OpenRouter。
  本地拒绝列表命中的命令不会外发，其余会。

## 开发

```sh
npm ci             # 零依赖，只为生成 node_modules 约定
npm run build      # src/ → lib/，再逐模块真实导入 + 桩上下文断言两处接缝
npm test           # 上述构建 + 25 个用例：拒绝列表、fail-closed 表、模式闸门、端到端
```

源码在 `src/`，`lib/` 是构建产物——仓库的 `android-shell/.gitignore` 忽略 `plugins/*/lib/`，
同仓其它插件（如 `dsh-model-capability`）也是这个布局。**不要直接改 `lib/`**，改 `src/` 后重新构建。
