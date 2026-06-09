# Claude Code — Notification Hooks

## Ubuntu (~/.claude/settings.json)

```json
{
  "hooks": {
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "notify-send 'Claude Code' \"Задача завершена: $(basename $PWD)\""
          }
        ]
      }
    ],
    "Notification": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "notify-send 'Claude Code' \"Требуется ответ: $(basename $PWD)\""
          }
        ]
      }
    ]
  }
}
```

Установка: `sudo apt install libnotify-bin`

---

## macOS (~/.claude/settings.json)

```json
{
  "hooks": {
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "osascript -e 'display notification \"Задача завершена: '$(basename $PWD)'\" with title \"Claude Code\"'"
          }
        ]
      }
    ],
    "Notification": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "osascript -e 'display notification \"Требуется ответ: '$(basename $PWD)'\" with title \"Claude Code\"'"
          }
        ]
      }
    ]
  }
}
```

Установка: ничего не нужно, osascript встроен.

---

## События
- `Stop` — агент завершил задачу
- `Notification` — агент ждёт ответа пользователя (не спамит при авто-подтверждении)
