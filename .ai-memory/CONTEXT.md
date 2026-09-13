# AI 记忆 — AuthMe 多账号身份切换（/lg）

> 本文件用于在新对话中恢复上下文。新对话开始时先读本文件，再按需查看 `git status` / `git log` 确认状态。

## 一、项目概况

- **项目**：AuthMeReloaded（Minecraft 登录/认证插件），当前为 Fork 版本 `5.7.0-FORK`
- **位置**：`/workspace`
- **构建**：Maven。含两个模块：AuthMe 主插件（根 `pom.xml`）+ `authme-geyser-extension/`（Geyser 扩展，独立 pom，依赖 `org.geysermc.geyser:api:2.11.2-SNAPSHOT` provided，无 Floodgate 依赖）
- **一键构建**：`./build.sh [all|authme|extension]`（Windows 用 `build.bat`），产物复制到 `dist/`；也可在根目录/扩展目录分别 `mvn -DskipTests package`。⚠️ 当前 `target/`、`dist/` 均为空（上次会话清理过临时产物），部署前需重新构建
- **远程**：`origin/feat/agent-mail`（主开发分支），作者 NoobLLiu

## 二、Git 状态（截至 2026-09-13）

- **当前分支**：`trae/agent-MIWt87`，HEAD `d37ffe83`
- **工作区**：干净，无未提交更改
- **分支内容**：在 e7684e39（PR #2 合并）之上有 11 个提交，标题均为 "feat: 实现基岩版玩家身份切换功能"，累计 `+826 / -5`（17 个文件）：新增 `authme-geyser-extension/` 模块（pom.xml、AuthMeGeyserExtension、IdentitySwitchListener、PendingSwitchStore、extension.yml）+ AuthMe 侧 `IdentitySwitchManager`（+99）、`PreLoginIdentityListener`（+8）+ CI 修复（build.yml / maven.yml）+ `build.sh` / `build.bat`
- **远程**：`origin/feat/agent-mail` HEAD 为 `0222c1eb`（Merge pull request #6 from NoobLLiu/trae/agent-MIWt87，合并的是本分支早期状态）；origin **不是**当前 HEAD 的祖先（历史有分叉），本地最新提交尚未合并回 origin

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

**当时限制**：基岩版玩家暂时无法切换到 Java 版身份（Floodgate 架构限制，从自己的 GeyserSession 创建 Player，不遵循事件 profile）。→ 已在六之七/六之八通过 Geyser 扩展在连接层改写身份解决（见下文）。

## 六之七、会话 8：Geyser 扩展创建与加载错误修复（2026-09-13，已提交）

**目标**：让基岩版玩家（Geyser+Floodgate 连接）也能用 /lg 切换到 Java 版身份。AuthMe 本体无法完成（六之六的 Floodgate 架构限制），故新建 Geyser 扩展在连接层改写身份。

**新增模块 authme-geyser-extension/**（独立 Maven 项目，非 Bukkit 插件）：
1. `pom.xml` — 依赖 `org.geysermc.geyser:api:2.11.2-SNAPSHOT`（provided，与服务器 Geyser 版本一致）；无 Floodgate 依赖
2. `src/main/resources/extension.yml` — 扩展元数据：
   ```yaml
   id: authme-geyser
   name: AuthMeGeyser        # 必须匹配 ^[A-Za-z_.-]+$（不能有空格）
   main: com.authme.geyser.AuthMeGeyserExtension
   api: 2.11.2               # 必须匹配 ^\d+\.\d+\.\d+$（三段数字，跟随 Geyser 版本）
   version: '1.0.0'
   author: AuthMe
   ```
3. `AuthMeGeyserExtension.java` — 主类。**Geyser 2.11.2 的 Extension 接口没有 onEnable()/onDisable()**，生命周期改为事件驱动：`@Subscribe onPostInitialize(GeyserPostInitializeEvent)` 初始化 PendingSwitchStore、注册 IdentitySwitchListener、启动过期清理调度器；`@Subscribe onGeyserShutdown(GeyserShutdownEvent)` 关闭调度器。加载器只实例化主类并自动注册其中的事件监听。
4. `PendingSwitchStore.java` — 读写 AuthMe 与扩展之间的共享 pending switch 文件（AuthMe 本体 initiateSwitch 时 writeGeyserPendingSwitch 写入，扩展按 XUID 消费）。构造函数直接接收 AuthMe 目录：先查 `user.dir/plugins/AuthMe`，再从扩展 dataFolder 逐级向上搜索（Spigot 布局 `<server>/plugins/Geyser-Spigot/extensions/<id>/` 向上 2 级不够，旧版 bug 已修复）。
5. `IdentitySwitchListener.java` — SessionLoginEvent 监听（详见六之八最终版）。

**期间修复的加载错误**（用户日志 `Invalid extension name, must match: ^[A-Za-z_.-]+$`）：
- name 从 "AuthMe Geyser Extension"（含空格）改为 AuthMeGeyser
- api 从 `1.0` 改为 `2.11.2`（三段式校验，潜伏问题）
- 扩展最初针对 Geyser API 2.4.3 编译而服务器运行 2.11.2，新版接口无 onEnable/onDisable → 升级依赖并事件化重构
- 顺带修复 CI（build.yml / maven.yml）、新增 build.sh / build.bat 一键构建脚本

## 六之八、会话 9：基岩→Java 身份改写完全重写（Floodgate 绕过）（2026-09-13，已提交）

**用户反馈**：扩展加载成功但基岩玩家切换后仍以 BE_ 身份加入；日志 `applying Bedrock switch ... (original: 'null', ...)` 与 `could not modify session or FloodgatePlayer`；截图"基岩版暂不支持切换到Java版身份"（该消息来自 AuthMe `IdentitySwitchJoinListener.java:75` 的失败提示，说明两端对接本身是好的，问题在扩展改写无效）。

**根因（克隆 Geyser 2.11.2 源码逐层核实，临时克隆已清理）**：
1. `javaUsername()`/`javaUuid()` 是从 playerEntity **计算的属性，无字段可反射**（旧扩展反射失败 → 日志 original: 'null'）
2. Floodgate 数据流：`GeyserSessionAdapter.packetSending` 将 BedrockData（bedrockUsername/xuid）加密附加到 handshake hostname → Floodgate 插件据此强制创建 `BE_前缀 + XUID UUID` 的 Player，**无视 Paper profile/ProtocolLib 的一切上层改写**
3. 关键时序：`connectDownstream()` 先触发 `SessionLoginEvent`（扩展拦截点）→ 创建 GeyserSessionAdapter（读 `remoteServer().authType()`）→ 用 `protocol` 建立下游连接
4. `MinecraftProtocol.profile` 是可替换字段；authType=OFFLINE 时 adapter 不附加 Floodgate 数据

**修复（最终版代码）**：
1. `authme-geyser-extension/.../IdentitySwitchListener.java` — 完全重写，`@Subscribe onSessionLogin(SessionLoginEvent)` 中：
   - 反射替换 `protocol.profile` 为 `new GameProfile(targetUuid, targetName)`（类名 `org.geysermc.mcprotocollib.auth.GameProfile`，构造参数 (UUID, String)）→ LoginStart 包携带目标 Java 身份
   - 反射替换 `remoteServer` 为 `OfflineAuthRemoteServer` 委托包装（全部方法透传，仅 `authType()` 返回 OFFLINE）→ GeyserSessionAdapter 不再附加 Floodgate 加密数据 → 服务器视为普通 Java 连接，Floodgate 完全不介入
   - 目标是基岩账号（Floodgate UUID 前缀 `00000000-0000-0000-`）时跳过并警告（基岩→基岩不支持）
   - 通过 `connection.xuid()` 读 XUID 并消费 pending 文件
2. `identity/IdentitySwitchManager.java` — 新增 `getPendingSwitchByTarget(String name)`（sourceByTarget 反查）
3. `listener/PreLoginIdentityListener.java` — 按 source 查 pending 失败后增加按 target 反查分支：扩展改写后的连接以**目标名**到达，而离线服务器按名字重算 offline UUID，必须在此阶段用 pending 中的 UUID 修正

**预期成功日志**（下次真实服务器验证时核对）：
- `[authme-geyser] AuthMe identity switch: connection '<基岩名>' will join the Java server as '<目标名>' (Floodgate bypassed)`
- 不再出现 `[floodgate] Floodgate 玩家 ... 加入了`

**部署**：两个 jar 必须同时更新——AuthMe 插件 jar + `authme-geyser-extension-1.0.0.jar` → `plugins/Geyser-Spigot/extensions/`。当前 target/ 已清理，需先 `./build.sh` 重新构建。

**注意**：此方案未经真实服务器验证。若仍有问题，优先核对 Geyser 2.11.2 下 SessionLoginEvent 触发时序与 `protocol`/`remoteServer` 字段名。

## 七、后续可继续的工作（新会话候选）

1. **验证基岩→Java 身份切换**：六之八的新方案（protocol.profile 反射改写 + OFFLINE 包装绕过 Floodgate）尚未在真实服务器验证；成功标志与失败排查见六之八
2. **合并分支**：将本地 `trae/agent-MIWt87` 的最新提交合并回 `origin/feat/agent-mail`（origin 已合并过早期状态 PR #6，历史有分叉，需先处理）
3. **功能验证**：在测试服验证 /lg 菜单、Java→Java 切换、UUID 同步、自动登录等既有功能
4. **潜在改进点**（未做，仅提示）：
   - 其余语言（如 zhtw/ja 等）的 help/messages 翻译尚未补充
   - 无 Paper API + 基岩目标的切换失败仅日志警告，无玩家提示
   - `PendingSwitch` 与自动登录均依赖 `ExpiringMap`，3 分钟窗口固定，未做成配置项
   - 基岩→基岩切换明确不支持（扩展中跳过并警告）
