# Velocity Stay Server

[![CI](https://github.com/twme-ai/velocity-stay-server/actions/workflows/ci.yml/badge.svg)](https://github.com/twme-ai/velocity-stay-server/actions/workflows/ci.yml)

這是一個支援 Velocity 3 與 Velocity 4 的代理端插件。它會記住玩家最後成功進入的後端伺服器，並在下次登入時優先送回該伺服器；後端重啟或關機時，玩家會留在 Velocity 連線中，插件則依設定週期重試原伺服器。

同一個 JAR 可用於 Velocity 3/4，不需要在 Paper 後端安裝其他插件。

## 行為

- 每次 `ServerConnectedEvent` 成功後，以玩家 UUID 記錄後端伺服器名稱。
- 玩家重新登入時，若記錄的伺服器仍有註冊於 Velocity，便將它設為初始伺服器。
- 如果這次初始連線失敗，插件不攔截錯誤，完全沿用 Velocity 設定的預設 fallback 順序。
- 玩家已在某台後端時，若連線中斷、關機原因符合關鍵字，或後端 ping 失敗，插件會暫停 kick 流程並每隔數秒重連原伺服器。
- 若後端仍在線且回傳一般 kick、白名單或封禁訊息，插件不會形成無限重試，而會沿用 Velocity 原本的處理。

## 安裝

需求：Velocity 3.x 或 4.x。插件本身使用 Java 17 bytecode；執行時請使用該 Velocity 版本所要求的 Java 版本。

1. 從 [GitHub Releases](https://github.com/twme-ai/velocity-stay-server/releases) 下載 JAR，或執行 `mvn clean package` 自行建置。
2. 將 `velocity-stay-server-1.0.0.jar` 放進 Velocity 的 `plugins/`。
3. 確認 `velocity.toml` 包含以下設定，然後重新啟動 Velocity：

```toml
failover-on-unexpected-server-disconnect = true
```

這個設定是 Velocity 對後端程序崩潰、網路中斷等「未正常送出 kick 封包」情形觸發 failover 事件的必要條件；Velocity 預設值為 `true`。

首次啟動後會產生：

- `plugins/velocity-stay-server/config.properties`
- `plugins/velocity-stay-server/last-servers.properties`

## 設定

```properties
restore-last-server=true
retry.enabled=true
retry.delay-seconds=5
retry.max-attempts=0
retry.outage-check-timeout-millis=2000
retry.reason-keywords=server closed,restart,restarting,server is starting,server still starting,shutdown,shutting down,server_shutdown
messages.waiting=Server {server} is restarting. Retrying every {seconds} seconds...
messages.reconnected=Reconnected to {server}.
messages.gave-up=Could not reconnect to {server}; using Velocity's fallback.
```

| 設定 | 說明 |
| --- | --- |
| `restore-last-server` | 是否在登入時優先使用上次成功進入的伺服器。 |
| `retry.enabled` | 是否在已連線的後端中斷時保留玩家並重試。 |
| `retry.delay-seconds` | 每次重新連線的間隔，範圍為 1 到 3600 秒。 |
| `retry.max-attempts` | 最大重試次數；`0` 表示不限次數。超過後交回 Velocity fallback。 |
| `retry.outage-check-timeout-millis` | 一般 kick 後檢查後端是否離線的 ping 逾時，範圍為 250 到 30000 毫秒。 |
| `retry.reason-keywords` | 視為重啟/關機的 kick 原因關鍵字，不分大小寫、以逗號分隔；`*` 代表所有 kick。 |
| `messages.*` | 留空可停用訊息。支援 `{server}`、`{seconds}` 與 `{attempt}` 變數。 |

設定檔於 Velocity 啟動時載入；修改後請重新啟動代理端。

## 建置驗證

```bash
# Velocity 3 API，產生正式 JAR
mvn clean package

# Velocity 4 API 相容性測試（官方 4.0.0 artifact 需使用 JDK 25）
mvn -Dvelocity.api.version=4.0.0 clean test
```

測試涵蓋資料持久化、登入還原、初始連線失敗 fallback、後端關機重連，以及正常 kick 不被誤判為重啟。
