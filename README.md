# Spotify Panel

HUD-панель Spotify для Fabric 1.21.11.

## Команды
- `/spotify` — показать/скрыть панель
- `/spotify setup <client_id>` → `/spotify login`
- `/spotify playlist add <ссылка>` — подключить любой плейлист по ссылке (`open.spotify.com/playlist/...`, `spotify:playlist:...` или ID)
- `/spotify playlist remove <ссылка>`
- `/spotify proxy <host:port>` — пустить запросы через HTTP-прокси; `/spotify proxy off` — выключить
- `/spotify play|pause|toggle|next|prev|volume <0-100>`

## Про 403
Прокси помогает только если Spotify недоступен по сети. Если 403 приходит на список треков чужого плейлиста — это ограничение Spotify API для приложений в Development mode, прокси его не обходит; плейлист при этом всё равно играет кнопкой ▶.
