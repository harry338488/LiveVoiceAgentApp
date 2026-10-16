# LiveVoiceAgentApp (Android 全雙工 Speech-to-Speech + 後端大腦 + Linkup 搜尋 + 對話紀錄留存)

這是一個專為 Android 打造的原生語音助理 App 專案（Kotlin + Jetpack Compose），完美實現你在 OpenAI Playground 體驗到的 **「前端 `gpt-live-1` 全雙工即時語音 + 後端 `gpt-6-luna`（或任意 LLM）任務推理 + Linkup 網路搜尋 + 完整對話與筆記留存」** 架構。

---

## ✨ 核心功能亮點

1. **支援三大即時語音協定 & 雙向打斷 (Full-Duplex Barge-in)**
   - **OpenAI GPT-Live (`/v1/live/sessions`)**：原生支援 `gpt-live-1`、`gpt-live-1.1`、`gpt-live-1-mini` 全雙工語音（邊聽邊講、毫秒級打斷、24kHz PCM16 串流 + Android 硬體回音消除 `AcousticEchoCanceler`）。
   - **xAI Grok Voice (`wss://api.x.ai/v1/realtime`)**：支援 `grok-voice-latest`、`grok-voice-3.0`。
   - **OpenAI Realtime (`/v1/realtime`)**：支援 `gpt-realtime-2.1` 等模型。

2. **輸入 API Key 後自動 Fetch 適用模型 (`/v1/models`)**
   - 在「**模型與 API 設定**」分頁輸入 OpenAI API Key 或 xAI API Key 後，點擊 **「從 API 自動 Fetch 適用模型清單」**，App 會自動呼叫 `GET /v1/models` 並將帳號可用的模型分類至：
     - **前端語音模型下拉選單**（如 `gpt-live-1`、`gpt-live-1.1`、`grok-voice-latest`）
     - **後端任務推理模型下拉選單**（如 `gpt-6-luna`、`gpt-6-sol`、`gpt-6-astra`、`gpt-5.6-luna`、`grok-4`）
   - 下拉選單同時支援手動輸入任何最新發表的模型名稱。

3. **雙模式後端委派 (`Delegation`)**
   - **Responses 雲端原生委派 (`delegation.type = "responses"`)**：直接由 OpenAI 雲端把 `gpt-live-1` 與後端 `gpt-6-luna` 綁定，前端負責口語寒暄與播報，後端負責深度推理與 Function Calling。
   - **Client 本機跨廠牌委派 (`delegation.type = "client"`)**：由 Android App 攔截 `session.delegation.created` 事件，可自由將前端 `gpt-live-1` 搭配 xAI `grok-4` 或任何自訂 OpenAI 相容端點。

4. **內建 Linkup Web Search API (`https://api.linkup.so/v1/search`)**
   - 自動為後端模型掛載 `linkup_web_search` 工具（支援 `standard` 與 `deep` 深度搜尋、`sourcedAnswer` 格式）。
   - 當語音對話觸發網路查詢時，除了讓 AI 用語音回答你之外，還會自動在對話畫面生成一張 **「🌐 Linkup 網路搜尋與來源」卡片**，列出所有參考網址，點擊即可用瀏覽器開啟。

5. **即時逐字稿、後端詳情筆記 ＆ 歷史對話留存（不怕 AI 一次講太多記不住！）**
   - 對話畫面會同時區分並即時保存：
     - `🗣️ 你`：你的語音即時轉錄與文字補充提問
     - `🎧 語音回覆 (gpt-live-1)`：AI 實際開口說出的話
     - `🧠 後端模型重點詳情 (gpt-6-luna)`：後端模型輸出的完整結構化文字與長篇清單
     - `🌐 Linkup 網路搜尋與來源`：搜尋摘要與可點擊的參考連結
   - **接續舊對話 (Resume / Seed Context)**：在「**對話紀錄留存**」分頁點擊任何一筆歷史紀錄的 **「載入並接續對話」**，App 會在建立 `gpt-live-1` 連線時自動透過 `session.input` 注入歷史上下文，讓 AI 完整記得上次聊到的所有內容！
   - **一鍵匯出 Markdown**：隨時點擊右上角複製按鈕，將整場對話與後端筆記匯出為 Markdown。

---

## 🚀 如何編譯成 APK 安裝到手機？

### 方法一：使用 Android Studio 編譯（推薦本機開發）
1. 開啟 **Android Studio** -> 選擇 **Open** -> 選擇本資料夾 `c:\Users\user\資料庫\LiveVoiceAgentApp`。
2. 等待 Gradle Sync 自動下載依賴完成。
3. 插上 Android 手機（開啟 USB 偵錯）直接點擊 **Run ▶️**，或點擊選單 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)** 產生 `app-debug.apk`。

### 方法二：免裝 Android Studio，使用 GitHub Actions 雲端自動打包 APK
專案內已附帶 [`.github/workflows/build-apk.yml`](file:///c:/Users/user/資料庫/LiveVoiceAgentApp/.github/workflows/build-apk.yml)：
1. 將 `LiveVoiceAgentApp` 資料夾上傳到你的 GitHub 私人 Repository。
2. 點擊 GitHub 頁面的 **Actions** 分頁 -> 執行 **Build Android APK**。
3. 約 2 分鐘後即可在 Artifacts 下載編譯好的 `LiveVoiceAgent-debug-apk` 並傳到手機安裝。
