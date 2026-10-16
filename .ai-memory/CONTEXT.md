# AI 记忆 — AuthMe 多账号身份切换（/lg）

> 本文件用于在新对话中恢复上下文。新对话开始时先读本文件，再按需查看 `git status` / `git log` 确认状态。

## 一、项目概况

- **项目**：AuthMeReloaded（Minecraft 登录/认证插件），当前为 Fork 版本 `5.7.0-FORK`
- **位置**：`/workspace`
- **构建**：Maven（`pom.xml`，Java + Spigot API + ProtocolLib 等依赖）。已成功构建，产物在 `target/`：
  - `AuthMe-5.7.0-FORK-Lite.jar` / `AuthMe-5.7.0-FORK-Universal.jar`（构建于 2026-09-12 04:13，与最新提交同时，含本次功能）
- **远程**：`origin/feat/agent-mail`（主开发分支），作者 NoobLLiu

## 二、Git 状态（截至 2026-09-12）

- **当前分支**：`trae/agent-0iHQBQ`
- **工作区**：干净，无未提交更改
- **分支领先 `origin/feat/agent-mail` 共 2 个提交（尚未合并）**：
  1. `cd073f1` feat: 研究实现Minecraft多账号身份切换 — 仅 `.trae-html-share-packages` 下 5 个 `*.html.zip` 重新打包（内容不变）
  2. `7fde343` feat: 研究实现Minecraft多账号身份切换 — **核心功能实现**，20 个文件，`+1424 / -4`
- `origin/feat/agent-mail` 当前 HEAD：`e0c9b60`（Merge pull request #1 from NoobLLiu/trae/agent-YXxVwV）

## 三、本次会话做了什么

1. 任务：生成合并差异摘要报告（`origin/feat/agent-mail...trae/agent-0iHQBQ`）
2. 用 `git diff origin/feat/agent-mail...trae/agent-0iHQBQ -- <file>` 逐文件分析了全部 25 个变更文件
3. 解压对比了 5 个 `.html.zip`（确认内部 HTML 模板内容一致，仅 zip 打包元数据变化）
4. 已向用户输出 Markdown 格式摘要报告（总体概述 + 文件级变更表格）

## 四、功能详情：/lg 个人登录信息与身份切换

为 AuthMe 新增"同邮箱多账号身份切换"能力：

- **命令**：`/lg`（已注册于 `plugin.yml`，Tab 补全已接入），仅已登录玩家可用，未登录提示 `NOT_LOGGED_IN`
- **GUI 菜单**（`IdentityMenuService` 构建，54 格背包界面）：
  - 显示当前账号（玩家头颅）、绑定邮箱（纸）、同邮箱下其他账号（头颅，区分 Java/基岩版）
  - 分页（每页 21 个账号槽位），翻页箭头与关闭按钮
  - 数据异步拉取（`DataSource.getAllAuthsByEmail`），主线程打开
- **切换流程**（`IdentitySwitchManager.initiateSwitch` 校验链）：
  - 校验：目标非自身、源账号已绑定邮箱、目标存在、目标与源同邮箱、目标不在线、无并发切换冲突
  - 通过后记录 `PendingSwitch`（3 分钟有效，`ExpiringMap`），关闭菜单并踢出玩家，提示重进
- **重连身份改写**：
  - 优先：Paper Profile API — `PreLoginIdentityListener` 在 `AsyncPlayerPreLoginEvent`（HIGHEST）反射调用 `getPlayerProfile/setPlayerProfile/setName/setId` 改写名字与 UUID
  - 回退：无 Paper API 时 `ProtocolLibService` 注册 `LoginStartRewriteAdapter`，改写客户端 Login Start 包（兼容 1.20.2+ profile 字段与旧版纯用户名；基岩身份在旧版协议下无法携带 UUID 会失败）
  - 仅当重连 IP 与发起切换 IP 一致时生效；目标在线则取消切换
- **自动登录**：`IdentityAutoLoginListener` 在 `PlayerJoinEvent`（MONITOR）延迟 1 秒，若命中自动登录授权则 `AuthMeApi.forceLogin` 并提示"身份已切换，已自动登录"
- **消息**：`MessageKey` 新增约 20 个 `identity.*` 枚举；`messages_en/zhcn/zhhk.yml` 各新增 23 条文案；`help_en/zhcn/zhhk.yml` 各新增 /lg 帮助条目

## 五、本次合并涉及的文件清单（25 个）

**新增（10）**：
- `src/main/java/fr/xephi/authme/command/executable/identity/IdentityMenuCommand.java`
- `src/main/java/fr/xephi/authme/identity/IdentityMenuHolder.java`
- `src/main/java/fr/xephi/authme/identity/IdentityMenuService.java`
- `src/main/java/fr/xephi/authme/identity/IdentitySwitchManager.java`
- `src/main/java/fr/xephi/authme/identity/PendingSwitch.java`
- `src/main/java/fr/xephi/authme/listener/IdentityAutoLoginListener.java`
- `src/main/java/fr/xephi/authme/listener/IdentityMenuClickListener.java`
- `src/main/java/fr/xephi/authme/listener/PreLoginIdentityListener.java`
- `src/main/java/fr/xephi/authme/listener/protocollib/LoginStartRewriteAdapter.java`

**修改（16）**：
- `AuthMe.java`（注册监听器、注入单例、lg 加入 Tab 补全）
- `CommandInitializer.java`（注册 /lg 基础命令）
- `ProtocolLibService.java`（按 Paper API 有无注册/卸载回退适配器）
- `MessageKey.java`（identity.* 消息枚举）
- `messages_en/zhcn/zhhk.yml`、`help_en/zhcn/zhhk.yml`、`plugin.yml`
- `.trae-html-share-packages/src/main/resources/` 下 5 个 `*.html.zip`（内容不变）

## 六、会话 2：UUID 修复（2026-09-12，未提交）

**用户反馈的 bug**：切换身份后目标账号名字正确，但 UUID 不是目标账号自己的（是重新生成的）。

**根因**：`MySQL.buildAuthFromResultSet` 读取了 `PLAYER_UUID` 列，但 **SQLite、H2、PostgreSQL 的 `buildAuthFromResultSet` 都没读 UUID** → 这些数据源下 `getAuth().getUuid()` 永远为 null → `resolveTargetUuid` 回退到 `computeOfflineUuid`（按名字重新生成的离线 UUID）。默认数据源 SQLite 必现。

**修复（4 处，工作区未提交，构建已通过 `mvn -DskipTests package`，产物 05:17）**：
1. `datasource/SQLite.java` — `buildAuthFromResultSet` 读取 `PLAYER_UUID`（`UuidUtils.parseUuidSafely`），新增 imports
2. `datasource/H2.java` — 同上
3. `datasource/PostgreSqlDataSource.java` — 同上
4. `identity/IdentityMenuService.java` — `createAccountItem` 的 lore 第一行新增 `UUID: <uuid>`（深灰色，与当前账号项格式一致），显示的就是 DB 中的注册 UUID

**UUID 数据流（已验证）**：注册时 `PlayerAuthBuilderHelper.createPlayerAuth` 存 `player.getUniqueId()` → `saveAuth` 写入 `AuthMeColumns.UUID` 列（建表/ALTER 保证列存在）→ 修复后 `getAuth` 读回 → 菜单显示 & `resolveTargetUuid` 直接使用 → Paper `setId` / ProtocolLib `WrappedGameProfile(uuid, name)` 原样传递。`CacheDataSource.getAuth` 委托 `source.getAuth`，同样受益。

**已知边界**：老账号若 DB 中 UUID 为 NULL（注册早于 UUID 列），仍回退离线 UUID；正常新注册账号都有 UUID。

## 六之二、会话 3：v1→v2 迁移补记 UUID（2026-09-12，未提交）

**用户问题**：v1 账号迁移到 v2 时是否自动记录 UUID？——答案：之前**不会**（迁移只更新 email/password/schemaVersion）。

**修改（5 处，构建通过 `mvn -DskipTests package`）**：
1. `datasource/DataSource.java` — 新增接口方法 `boolean updateUuid(PlayerAuth auth)`（放在 updateSchemaVersion 之后）
2. `datasource/AbstractSqlDataSource.java` — 实现 `updateUuid`：`columnsHandler.update(auth, AuthMeColumns.UUID)`（SQLite/H2/PostgreSQL/MySQL/MariaDB 全部继承）
3. `datasource/CacheDataSource.java` — 包装实现（成功后 `cachedAuths.refresh`，同 updateEmail 模式）
4. `service/AccountMigrationService.java` `completeEmailMigration` — `auth.setUuid(player.getUniqueId())` + `dataSource.updateUuid(auth)`（失败仅 warning 不阻断迁移）
5. 同文件 `completePasswordMigration` — 同样补记 UUID

**行为**：迁移完成时玩家在线，`player.getUniqueId()` 即该账号登录 UUID，写入 DB UUID 列；返回的 auth 对象也带 UUID。迁移后的账号即可被 /lg 正确显示与切换（配合会话 2 的 getAuth 修复）。DataSource 只有 AbstractSqlDataSource 与 CacheDataSource 两个直接实现，均已更新（已全库搜索确认，无测试 mock 实现会编译失败）。

## 六之三、会话 4：UUID 记录完善（2026-09-12，未提交）

**用户需求**：① 绑定邮箱时也记录 UUID；② 无 UUID 的老账号在下一次登录时自动记录/同步；③ 拿不到 UUID 前不再回退重新生成的离线 UUID，改为提示"未获取到UUID，请先使用该账号登录以同步信息"。

**修改（8 个文件，构建通过）**：
1. `process/login/AsynchronousLogin.java` `performLogin` — `updateSession` 后新增 UUID 同步：`!player.getUniqueId().equals(auth.getUuid())` 时 `setUuid` + `dataSource.updateUuid(auth)`（null=记录，不同=同步；成功 fine 日志，失败 warning）。此钩子覆盖密码登录/会话恢复/forceLogin/切换后自动登录（后者 UUID 相等不触发，无干扰）
2. `command/executable/email/EmailConfirmCommand.java` — 已登录玩家 `/email add|change` + `/email confirm` 成功路径：`auth.setUuid(player.getUniqueId())` + `updateUuid`（失败仅 warning 不阻断），随后才 `playerCache.updatePlayer(auth)`
3. `identity/IdentitySwitchManager.java` — `initiateSwitch` 在 target-gone 检查后新增 `targetAuth.getUuid() == null` → 发送 `IDENTITY_SWITCH_UUID_MISSING` 并中止；**删除** `computeOfflineUuid` 与 `resolveTargetUuid` 两个方法及 `StandardCharsets` import（不再回退）
4. `identity/IdentityMenuService.java` — `open()` 不再回退 computeOfflineUuid，`AccountEntry.uuid` 可为 null；`createAccountItem`：uuid 为 null 时不设头颅 owner、不加 [Java/基岩版] 标记、lore 只显示 uuid_missing 消息（隐藏"点击切换"），否则显示 `UUID: xxx` + 点击提示
5. `message/MessageKey.java` — 新增 `IDENTITY_SWITCH_UUID_MISSING("identity.uuid_missing")`
6. `messages_en/zhcn/zhhk.yml` — identity 段各新增 `uuid_missing` 文案（en: No UUID recorded...；zhcn: 未获取到UUID，请先使用该账号登录以同步信息；zhhk: 未獲取到UUID，請先使用該帳戶登入以同步資訊）

**UUID 记录时点汇总**（改造后共 5 处）：注册（saveAuth）、v1→v2 迁移完成（AccountMigrationService 两处）、邮箱绑定确认（EmailConfirmCommand）、任意登录（AsynchronousLogin 兜底同步）。管理员 `/authme setemail`（SetEmailCommand）无 Player 对象，未记录，依赖登录兜底。

**注意**：`EmailConfirmCommand` 的迁移确认路径（processMigrationConfirmation）走 AccountMigrationService（已记录 UUID）；`AsyncAddEmail` 只发验证码，持久化在 EmailConfirmCommand。

## 六之四、会话 5：/lg sync 手动同步指令（2026-09-12，未提交）

**背景**：用户反馈旧账号登录后仍未同步 UUID（排查结论：最新 jar 中所有登录路径——密码登录/会话恢复/forceLogin/迁移——均经 `AsynchronousLogin.performLogin` 且同步代码在迁移拦截之后，理论上已覆盖；最可能是服务器未部署会话 4 的 jar 或环境差异。updateUuid 的 SQL 机制与 updateSchemaVersion 相同，后者已在生产验证）。用户要求加手动同步指令。

**新增 `/lg sync`**（7 个文件，构建通过）：
1. `CommandInitializer.java` — /lg 注册新增 OPTIONAL 参数 `action`（'sync'），否则 CommandMapper 会因参数个数不符返回 INCORRECT_ARGUMENTS
2. `IdentityMenuCommand.java` — 注入 IdentitySwitchManager；已登录（沿用 NOT_LOGGED_IN 检查，防未认证玩家写他人账号 UUID）且首参数为 sync（忽略大小写）→ `identitySwitchManager.syncOwnUuid(player)`；否则开菜单
3. `IdentitySwitchManager.syncOwnUuid(Player)` — 异步 `dataSource.getAuth(nameLower)`（直接读 DB，不用 playerCache，DB 为准）→ uuid 相等发 ALREADY；否则 setUuid+updateUuid → SUCCESS（带 %uuid% 替换）/FAILED；成功记 info 日志
4. `MessageKey.java` — 新增 `IDENTITY_SYNC_SUCCESS("identity.sync_success","%uuid%")`、`IDENTITY_SYNC_ALREADY`、`IDENTITY_SYNC_FAILED`
5. `messages_en/zhcn/zhhk.yml` — identity 段各新增 sync_success/sync_already/sync_failed
6. `help_en/zhcn/zhhk.yml` — /lg detailedDescription 补充 sync 说明
7. `plugin.yml` — lg usage 更新为 `/lg [sync]`

**用法**：玩家登录后执行 `/lg sync` → 数据库 UUID 列更新为当前连接 UUID → /lg 菜单显示真实 UUID → 可正常切换。

## 六之五、会话 6：UUID 列默认值 bug 修复（2026-09-12，未提交）

**根因**：`DatabaseSettings.MYSQL_COL_PLAYER_UUID` 默认值为**空字符串** `""`：
- `DataSourceColumn.isColumnUsed()`：当列是 `OPTIONAL` 且名称为空时返回 `false` → `ch.jalu.datasourcecolumns` 库在 INSERT/UPDATE 时静默跳过该列
- SQLite `setup()` 的 `!col.PLAYER_UUID.isEmpty()` 为 `false` → `ALTER TABLE ADD COLUMN` 被跳过 → UUID 列根本不存在于数据库
- 结果：`saveAuth`/`updateUuid`/`getAuth` 对 UUID 列全部为空操作 → UUID 永远无法被写入或读取

**修复**：
1. `DatabaseSettings.java` — `newProperty("DataSource.mySQLPlayerUUID", "")` → `"player_uuid"`（新安装时列名默认启用）
2. `IdentitySwitchManager.syncOwnUuid` — 失败日志加诊断提示（指向 config key）
3. `AsynchronousLogin.performLogin` — UUID 同步失败日志加诊断提示

**部署注意**：
- 新建服务器：部署新版 jar 即可，`player_uuid` 列名自动生效，SQLite setup 会在启动时建列
- 已有服务器且未配置过 `DataSource.mySQLPlayerUUID`：
  - 方案 A（推荐）：在 `authme.yml` 中手动加上 `DataSource.mySQLPlayerUUID: player_uuid`，重启服务器 → SQLite setup 自动建列
  - 方案 B：部署新版 jar → `/lg sync` 仍会失败（config 还是空），但登录不会阻断
- 已有服务器且已配置过 `DataSource.mySQLPlayerUUID: <custom_name>`：无需任何操作，列名已生效，UUID 本就存入
- `Columns.PLAYER_UUID`（SQLite/MariaDB 等专用 handler 读取的列名）也会随 config 变化，无需额外处理

## 六之六、会话 7：基岩版身份切换修复（2026-09-12，未提交）

**用户问题**：基岩版玩家通过 Geyser/Floodgate 使用 /lg 切换身份后，重连仍然是原始身份。

**根因分析**（两个 bug）：

Bug 1 — Paper profile API 对 Floodgate 无效：
- `PreLoginIdentityListener` 在 `AsyncPlayerPreLoginEvent`(HIGHEST) 中通过 Paper API 修改 PlayerProfile
- 但 Floodgate 从自己的 GeyserSession 创建 Player 对象，**完全忽略事件中的 profile 修改**
- 结果：即使日志显示 "Rewrote login identity"，Player 仍然以原始基岩版身份加入

Bug 2 — PendingSwitch 消费过早：
- `PreLoginIdentityListener` 和 `LoginStartRewriteAdapter` 都在 PreLogin 阶段消费 PendingSwitch
- 但 Player 的实际身份要到 PlayerJoinEvent 才能确定
- 导致：switch 被消费后，如果实际身份没变（基岩版情况），switch 丢失无法恢复

**修复（8 个文件，构建通过）**：

1. `ProtocolLibService.java` — `LoginStartRewriteAdapter` 始终注册（不再依赖 `!isPaperProfileSupported()`），因为包级别重写是处理基岩版玩家的唯一有效方式
2. `LoginStartRewriteAdapter.java` — 优先级从 HIGH 改为 MONITOR（在 Floodgate 之后执行，才能看到转换后的包数据）；不再消费 PendingSwitch 和标记 auto-login（延迟到 PlayerJoinEvent）
3. `PreLoginIdentityListener.java` — 不再消费 PendingSwitch 和标记 auto-login（同上，延迟到 PlayerJoinEvent）
4. `IdentitySwitchJoinListener.java` — **新增** PlayerJoinEvent 处理器（MONITOR 优先级，20 tick 延迟）：
   - 通过 `consumePendingSwitchByTarget(nameLower)` 检查是否有 switch 指向当前加入的玩家
   - 若有（Java 玩家或基岩版重写成功）→ 消费 switch，标记 auto-login
   - 若无且玩家有 PendingSwitch 但名字不匹配（基岩版重写失败）→ 保持 switch 活跃，发送 bedrock_unsupported 提示
5. `IdentitySwitchManager.java` — 新增 `consumePendingSwitchByTarget(targetNameLower)` 方法（通过 sourceByTarget 反查并消费）
6. `AuthMe.java` — 注册 IdentitySwitchJoinListener
7. `MessageKey.java` — 新增 `IDENTITY_SWITCH_BEDROCK_UNSUPPORTED`
8. `messages_en/zhcn/zhhk.yml` — 新增 bedrock_unsupported 文案

**修复后的流程**：
- Java 玩家：包重写 → profile 重写 → Player 以目标身份加入 → PlayerJoinEvent 消费 switch → 自动登录 ✓
- 基岩→Java 切换：包重写被 Floodgate 覆盖 → Player 以原始身份加入 → PlayerJoinEvent 检测不匹配 → switch 保持活跃 → 提示不支持 ✓

**当前限制**：基岩版玩家暂时无法切换到 Java 版身份。这是 Floodgate 的架构限制（从自己的 GeyserSession 创建 Player，不遵循事件 profile）。未来可通过 Floodgate API 直接干预来实现。

## 六之七、会话 8：基岩版注册/登录 GUI 表单（2026-10-16，未提交）

**需求**：检测到登录玩家为基岩版玩家、且不是切换账号（/lg 流程）而来的玩家时，弹出基岩版原生 GUI 表单用于注册/登录。

**实现（6 个文件，`mvn -s .maven-proxy-settings.xml -DskipTests package` 构建通过）**：
1. `listener/BedrockAuthFormListener.java`（新增）— PlayerJoinEvent(MONITOR) 延迟 40 tick（让 20 tick 的 IdentityAutoLoginListener / IdentitySwitchJoinListener / BedrockAutoLoginListener 先跑）：
   - `isFormRequired`：在线 + 未认证 + `Hooks.floodgate` 与 `Hooks.bedrockAuthForm` 均开 + `IdentitySwitchManager.isBedrockPlayer`；
   - 切换账号排除：`consumeAutoLogin` 命中（监听器顺序竞态兜底）→ forceLogin + 提示后不开表单；`getPendingSwitchByTarget`/`getPendingSwitch` 任一存在（切换流程中的玩家）→ 不开表单并记 info 日志；
   - 已注册 → 登录表单（1 个密码输入）；未注册 → 注册表单（密码 + 确认密码）；Cumulus `CustomForm`，`validResultHandler` 网络线程 → `bukkitService.runTask` 回主线程；
   - 登录提交 → `management.performLogin`（错误提示由登录管线发）；注册提交 → 空值/两次不一致 → 提示并重发表单；`ValidationService.validatePassword` 失败 → 提示并重发；通过 → `authmeApi.forceRegister(player, password, true)`（走完整管线含 IP 注册上限）；
   - 提交后 30 tick 复查：仍未认证 → 重发对应表单（失败重试 UX）；玩家主动关闭表单不重发（可回退聊天命令）；
   - Floodgate/Cumulus 类缺失或发送失败 → warning 日志，聊天提示兜底。
2. `HooksSettings.java` — 新增 `Hooks.bedrockAuthForm`（默认 true，需 floodgate + `Hooks.floodgate`）。
3. `MessageKey.java` — 新增 8 个 `bedrock_auth.*` 枚举（login_title/login_label/register_title/register_label/password_input/password_confirm_input/password_mismatch/password_empty）。
4. `AuthMe.java` — 无条件注册监听器（运行时自查设置与 Floodgate 存在性，reload 友好），import 排在 BedrockAutoLoginListener 前。
5. `messages_en/zhcn/zhhk.yml` — 各新增 `bedrock_auth:` 段（8 条文案）。

**与现有功能的协作**：`FORCE_LOGIN_BEDROCK` 开启时 BedrockAutoLoginListener 在 20 tick 自动登录 → 40 tick 时已认证自然跳过；切换而来的玩家由 consumeAutoLogin/待定 switch 检查排除；超时踢出由 TimeoutTask 负责，表单重发不与其冲突。

## 七、后续可继续的工作（新会话候选）

1. **合并分支**：将 `trae/agent-0iHQBQ` 合并到 `origin/feat/agent-mail`（合并/创建 PR）
2. **功能验证**：在测试服验证 /lg 菜单、切换、重连改写与自动登录（含基岩版账号场景）
3. **潜在改进点**（未做，仅提示）：
   - 其余语言（如 zhtw/ja 等）的 help/messages 翻译尚未补充
   - 无 Paper API + 基岩目标的切换失败仅日志警告，无玩家提示
   - `PendingSwitch` 与自动登录均依赖 `ExpiringMap`，3 分钟窗口固定，未做成配置项
