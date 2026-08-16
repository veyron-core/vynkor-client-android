# UI Reference — Screenshots from AI chat apps

Reference screenshots captured from the device (MI 6, dark theme, Russian locale)
to use as design examples for the vynkor Android agent app.

## Where

| App | Folder |
|---|---|
| Gemini (Google app, AI mode) | `gemini/` |
| ChatGPT | `chatgpt/` |
| Claude | `claude/` |
| DeepSeek | `deepseek/` |

## Measured design specs (from uiautomator dumps + pixel analysis, 1080×1920)

### Composer (input island) — the key reference

| App | Shape | Color | Screen bg | Side margin | Corner radius | Structure |
|---|---|---|---|---|---|---|
| Gemini | floating island | `#1E1F20` | `#131314` | ~13dp | ~28–32dp | two rows: input on top; icon row below — plus-circle LEFT, [mic, lens, blue circular send] RIGHT |
| ChatGPT | floating bar | `#212121` | `#000000` | ~14dp | ~24dp | single row: placeholder + attach LEFT, [dictation, voice/send] RIGHT |
| Claude | floating bar | `#20201F` | `#151515` | ~16dp | ~24dp | two rows: input; icon row — [add, model chip (Sonnet 5), thinking chip] + mic + voice mode |
| DeepSeek | pill input + icon row | `#242424` | `#0F0F0F` | ~14dp | capsule | pill input with "hold to speak"; icon row below: reasoning/search/attach/voice |

### Common patterns

- **Send button**: circular accent-colored button (blue in Gemini/ChatGPT, green-ish in Claude)
  appears on the right when text is present; mic icon otherwise.
- **Model chip**: Claude shows the active model (e.g. "Sonnet 5") as a chip inside the composer.
- **Message footer actions**: all apps place action icons BELOW the assistant message
  (Copy / Like / Dislike / Share / Retry / Narrate) — Gemini also uses long-press → menu.
- **User message**: right-aligned bubble (Claude `#2C2C2A`, DeepSeek `#333333`, ChatGPT dark).
- **Assistant message**: plain left-aligned text, no bubble.
- **Home/welcome**: large greeting text + suggestion cards/chips (Gemini "What do you want to
  know?", Claude "Afternoon, behzod", ChatGPT suggestion cards).
- **Chat list**: grouped by day (DeepSeek "Today / Yesterday / 7 days", Gemini "Recent").
- **Theme switch**: DeepSeek has System / Light / Dark picker.

## Screenshots per app

- `gemini/01_main_composer.png` — main screen, keyboard open: floating island composer
- `gemini/04_history_list.png` — "Recent" chat history list
- `gemini/05_home.png` — home with suggestion chips
- `chatgpt/01_home.png` — home: suggestion cards + composer
- `chatgpt/02_menu.png` — drawer: new chat / recent chats / account settings
- `chatgpt/03_settings.png` — settings list
- `chatgpt/04_chat.png` — chat: user/assistant messages, copy-code button, composer
- `claude/01_home.png` — home: greeting, upgrade card, composer with model chips
- `claude/03_chat_view.png` — chat: user bubble right, assistant text, inline footer actions
- `claude/04_menu.png` — menu: recents list + settings
- `claude/05_settings.png` — settings
- `deepseek/01_home.png` — home: mode selector (Fast/Expert/Recognition) + pill composer
- `deepseek/02_chat.png` — chat: user bubble right, assistant text, footer actions
- `deepseek/03_sidebar.png` — sidebar: chats grouped by day + search
- `deepseek/04_settings.png` — settings
- `deepseek/05_appearance.png` — theme picker (System/Light/Dark)

## vynkor app after redesign

- `vynkor_new_composer.png` — welcome screen: floating island composer (24dp radius,
  `#292932` on `#141218`, side margins 12dp), model chip + mic; send swaps with mic
  when typing; toolbar without model subtitle
- `vynkor_new_composer_chat.png` — chat state: same island under messages