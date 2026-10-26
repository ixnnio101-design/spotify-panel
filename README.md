# Spotify Panel (Fabric 1.21.11)

Голографическая панель Spotify рядом с игроком.

## Первый запуск
1. developer.spotify.com/dashboard → Create app. Redirect URI: `http://127.0.0.1:8888/callback`, API: Web API.
2. В User Management приложения добавь свой аккаунт. Владелец приложения должен иметь Premium.
3. В игре: `/spotify setup <Client ID>`, затем `/spotify login` (после обновления мода входи заново).

## Управление
- `H` — показать/скрыть панель, `J` — закрепить панель в мире, `K` — курсор без чата
- Чат (T) тоже даёт курсор; прицелом можно кликать левой кнопкой, когда он наведён на панель
- Команды: `/spotify`, `lock`, `cursor`, `play`, `pause`, `toggle`, `next`, `prev`, `volume N`, `logout`
- Положение: `/spotify set angle|distance|width <число>` (по умолчанию 60°, 1.5 блока, 1.4 блока)
