# Spotify Panel (Fabric, Minecraft 1.21.11)

Панель Spotify в стиле приложения, парящая рядом с игроком (как окна в Apple Vision Pro).

## Сборка
Нужна Java 21+ (шаблон Fabric в CI использует 25).

    ./gradlew build        (Windows: gradlew.bat build)

Готовый мод: `build/libs/spotify-panel-1.1.0.jar` (файл БЕЗ `-sources`). Кладите в `mods/` вместе с Fabric API.
Без компьютера для сборки: залейте папку на GitHub - вкладка Actions соберёт jar (Artifacts).

## Настройка Spotify (если ещё не делали - логин от старой версии подхватится сам)
1. developer.spotify.com/dashboard -> Create app, Redirect URI: `http://127.0.0.1:8888/callback`
2. В игре: `/spotify setup <client_id>` затем `/spotify login`

## Управление
- H - показать/скрыть, J - закрепить в мире / снова следовать за телом, K - курсор (мышь свободна, клик по панели, колёсико прокручивает)
- Команды: `/spotify play|pause|toggle|next|prev|volume <0-100>|lock|cursor|show`
- Положение: `/spotify set angle|distance|width|tilt <число>` (angle - сдвиг вправо от тела, tilt - насколько ниже глаз)

## Про лимиты Spotify (429)
Приложения в Development Mode имеют жёсткую квоту. Мод опрашивает плеер раз в 7 с (3 с при курсоре),
кеширует плейлисты на диск, а после 429 сам ждёт Retry-After (время видно над панелью).
