# Local Setup

## Telegram Bot Configuration

Telegram notifications use a bot token loaded from `.env` via [spring-dotenv](https://github.com/paulschwarz/spring-dotenv).

### Steps

1. Get your token from [@BotFather](https://t.me/botfather) on Telegram
2. Copy `.env.example` to `.env` in the project root:
   ```bash
   cp .env.example .env
   ```
3. Edit `.env` and set your token:
   ```
   TELEGRAM_BOT_TOKEN=your_actual_token_here
   ```

### Rules

- **NEVER commit `.env`** — it is in `.gitignore`
- **DO commit `.env.example`** — it is the template with no real values
- If `TELEGRAM_BOT_TOKEN` is not set, the app starts normally but Telegram is silently disabled with a warning in the log

### Startup behavior

| Condition | Result |
|-----------|--------|
| `.env` exists, token set | Bot fully operational |
| `.env` missing or token empty | App starts, bot disabled, WARNING logged |
| `telegram.enabled: false` in `application.yml` | Bot disabled regardless of token |
