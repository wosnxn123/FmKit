# 贡献指南

本仓库维护 Paper / Folia 插件 **FmKit**（背包整理与云背包）。

## 环境要求

| 项 | 要求 | 原因 |
| --- | --- | --- |
| JDK | 25 | paper-api 26.2 的字节码为 Java 25，低版本 javac 读不了依赖的 class 文件 |
| 产物字节码 | Java 21（`--release 21`） | 插件本身只用 Java 21 语言特性，服务端 JRE 门槛更低 |
| 服务端 | Paper 或 Folia 26.2 | `pom.xml` 依赖 `paper-api:26.2.build.115-stable` |
| Node | 用于跑 e2e 机器人（`bot/`） | mineflayer ^4.37.1、minecraft-data ^3.113.2 |

## 构建

两种方式产物等价，任选其一。

```powershell
# 1) Maven（CI 用的就是这条）
cd FmKit && mvn -B -ntp package
# 产物：target/FmKit-1.1.2.jar

# 2) build.ps1（不装 Maven 也能编，classpath 取本地 ~/.m2/repository 里的 jar）
pwsh FmKit/build.ps1     # 成功时最后一行打印 BUILD_OK
```

`build.ps1` 用 `M2_REPO` 环境变量覆盖本地仓库位置。它把 `config.yml`、`plugin.yml` 拷进 `target/classes` 后直接 `jar cf`，不经 Maven 生命周期。

细节见 [docs/构建与测试.md](docs/构建与测试.md)。

## 测试

改动行为的 PR 必须附上真实服务端的验证结果，不接受只过编译的改动。

```bash
cd bot
npm install
node fmkit_test.js       # FmKit 端到端
```

e2e 用 mineflayer 挂真实客户端 + RCON 下管理指令，需要一个开了 RCON 的 26.2 服务端并装好待测插件。跑法、环境变量与断言分组见 [docs/构建与测试.md](docs/构建与测试.md#自动化测试)。

## 提交与发布

- 提交信息用中文，`类型: 摘要` 前缀（`fix:`／`test:`／`docs:`／`ci:`／`chore:`）；版本提交写成 `FmKit 1.1.2：摘要`。
- 发布推 `v*` 标签，由 `.github/workflows/build.yml` 自动构建并上传插件的 jar。
- 版本变更同时更新 [CHANGELOG.md](CHANGELOG.md) 与 `pom.xml`、`plugin.yml`、`build.ps1` 里的版本号（`build.ps1` 的 jar 名是写死的）。

## 文档约定

- 文档一律中文。
- `README.md` 面向使用者，任务导向，能链出去就不复述。
- `DESIGN.md` 记录取舍与原因（为什么这么做、否掉了什么方案），不是 API 清单。
- `docs/` 下三份是参考手册：指令、配置、构建与测试。
- 不写没验证过的数字。槽位、断言数这类事实要么来自源码，要么来自实测输出。

## 许可

本仓库以 GPL-3.0 授权（见 [LICENSE](LICENSE)）。提交贡献即表示同意以同一许可发布你的改动。
