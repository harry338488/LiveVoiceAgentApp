# LiveVoiceAgent

An Android client for real-time full-duplex voice models (`gpt-live-1`, `gpt-realtime-translate`, `grok-voice`) with backend LLM delegation, simultaneous interpretation, and language practice.

[繁體中文](#繁體中文) | [English](#english)

---

## 繁體中文

這是一個用 Kotlin + Jetpack Compose 寫的 Android 即時語音客戶端。主要解決單純用即時語音模型（Speech-to-Speech）時常見的幾個痛點：推理能力較弱、長篇內容講過去就忘了、無法查即時資料，以及擴音時容易自己聽到自己說話。

App 內部採用「前端語音 + 後端推理」的雙模型架構：前端（如 `gpt-live-1`）負責低延遲語音對談與自然打斷，需要深度思考、查資料或整理重點時，則交給後端模型（如 `gpt-6-luna`、`grok-4` 或自訂 OpenAI 相容端點）處理，並把結構化筆記直接留在對話氣泡下。

### 主要模式

1. **語音助理 (Voice Assistant)**
   - 支援 OpenAI `/v1/live/sessions` (`gpt-live-1`) 與 xAI `/v1/realtime` (`grok-voice`) 全雙工通話，支援隨時插話打斷 (Barge-in)。
   - 支援雲端原生委派 (`delegation: responses`) 與本機跨模型委派 (`delegation: client`)。當後端在查資料或長考時，前端語音不會死機卡住，可以繼續跟你寒暄或回報進度。
   - 整合即時網路搜尋（OpenAI Native Search / Linkup API）與 Android 手機行事曆讀寫（查詢行程、新增與修改事件）。

2. **同步口譯 (Simultaneous Interpretation)**
   - 串接 `gpt-realtime-translate`（或 `/v1/realtime` 口譯模式），針對低延遲邊聽邊翻調整了 Server VAD 與串流緩衝。
   - 預設開啟自動語種偵測（支援中、英、日等多語混講，保留日文漢字與假名），並提供 A ↔ B 雙向目標語言單鍵切換。
   - 支援在通話中隨時補充專有名詞、人名或背景提示詞 (Context / Glossary)。

3. **外語陪練 (Language Tutor)**
   - 支援日語、英語、韓語、西語、法語、德語的口語對練，可選擇程度（初學 N5-N4 / 日常 N3-N2 / 商務 N1）與情境角色扮演（點餐、旅遊問路、面試敬語、朋友閒聊或自訂情境）。
   - **支援中日／中外混講**：講外語卡詞時可以直接穿插中文詞彙，前端 AI 會在對話中順勢教你道地說法並繼續聊下去。
   - **背景家教筆記**：每輪對話結束後，後端模型會在背景自動分析並附在氣泡下方（含日文漢字平假名標音、中文翻譯、剛才那句話的文法／自然度修改建議，以及下一句可以直接照著唸的回覆範例），不會讓語音模型把長篇文法解釋唸出來打斷對話。

### 音訊與系統設計

- **全雙工音訊與防回音**：24kHz PCM16 串流。除了啟用 Android 硬體 `AcousticEchoCanceler` / `NoiseSuppressor` 外，在 `FullDuplexAudioEngine` 實作了播放预緩衝（消除網路抖動造成的破音）與擴音防自激閘門（避免外放喇叭時 AI 把自己的聲音當成使用者說話）。
- **背景常駐通話**：透過 `LiveVoiceForegroundService` 維持前景服務與 WakeLock，鎖定螢幕或切換到其他 App 時通話不會中斷。
- **歷史紀錄與接續對話**：對話紀錄儲存在本機，重新載入舊對話時會自動透過 `session.input` 注入上下文，讓 AI 記得之前聊過的內容；支援一鍵匯出 Markdown。

### 安裝與編譯

- **直接下載 APK**：可至 [Releases](https://github.com/harry338488/LiveVoiceAgentApp/releases) 或 [GitHub Actions](https://github.com/harry338488/LiveVoiceAgentApp/actions) 下載最新編譯好的 APK。專案已配置固定簽章，新版本可直接覆蓋安裝，不會洗掉已儲存的 API Key 與對話紀錄。
- **自行編譯**：
  ```bash
  git clone https://github.com/harry338488/LiveVoiceAgentApp.git
  cd LiveVoiceAgentApp
  ./gradlew assembleDebug
  ```
  編譯完成的 APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。

---

## English

**LiveVoiceAgent** is a native Android client (Kotlin + Jetpack Compose) built for real-time speech-to-speech APIs. Instead of relying on a single voice model for everything, it pairs a low-latency frontend voice model (`gpt-live-1`, `grok-voice`) with a deeper backend reasoning model (`gpt-6-luna`, `grok-4`, or any OpenAI-compatible endpoint).

### Key Features

- **Dual-Brain Voice Assistant**
  - Connects to OpenAI `/v1/live/sessions` and xAI `/v1/realtime` over WebSocket with full-duplex 24kHz PCM16 audio and instant barge-in.
  - Supports both server-side delegation (`responses`) and client-side cross-provider delegation (`client`). While the backend LLM performs reasoning or tool calls, the frontend voice model stays responsive.
  - Built-in web search (OpenAI Native Web Search & Linkup API) and local Android Calendar integration (`CalendarContract` read/write).

- **Simultaneous Interpretation**
  - Dedicated mode for `gpt-realtime-translate` with tuned VAD thresholds (`threshold = 0.35`, `silence_duration_ms = 220`) for continuous speech translation.
  - Supports automatic source language detection (handles code-switching across Chinese, English, Japanese, etc.), one-tap A ↔ B target language toggling, and live terminology/context injection.

- **Language Tutor Mode**
  - Conversational practice for Japanese, English, Korean, Spanish, French, and German across selectable proficiency levels and roleplay scenarios.
  - **Code-switching friendly**: Mix native words when stuck; the voice partner naturally supplies the phrasing and keeps the conversation moving.
  - **Silent backend coaching**: After each turn, the backend model attaches an expandable note card to the message bubble with Furigana readings (`漢字(かんじ)`), translation, phrasing feedback on what you said, and 2 ready-to-read reply suggestions for your next turn.

- **Audio Engine & Background Calls**
  - Hardware `AcousticEchoCanceler` + software anti-echo gating in `FullDuplexAudioEngine` to prevent speakerphone self-triggering, plus a 60ms playback pre-buffer to eliminate network jitter crackles.
  - Runs inside an Android `microphone` foreground service so voice sessions survive screen-off and app switching.
  - Persistent local chat history with context seeding (`session.input`) to resume past conversations, plus Markdown export.

### Build from Source

Requires JDK 17 and Android SDK 34.

```bash
git clone https://github.com/harry338488/LiveVoiceAgentApp.git
cd LiveVoiceAgentApp
./gradlew assembleDebug
```

Pre-built APKs are also available under [GitHub Actions](https://github.com/harry338488/LiveVoiceAgentApp/actions) and [Releases](https://github.com/harry338488/LiveVoiceAgentApp/releases).
