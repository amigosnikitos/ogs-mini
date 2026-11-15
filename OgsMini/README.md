# OGS Mini — простой клиент online-go.com для Android

Kotlin + Jetpack Compose. Вход через OAuth2, список ваших партий, игра на доске, подсчёт.

## Что умеет

- Вход через браузер (OAuth2 Authorization Code + PKCE) или запасной вход по логину/паролю.
- Список активных партий с пометкой «Ваш ход».
- Открыть любую партию по номеру (чужую — в режиме наблюдения).
- Игра: тап по пересечению → «Подтвердить», «Пас», «Сдаться». Проверяются занятая точка, ко и самоубийство.
- Подсчёт: тап по группе отмечает её мёртвой, «Принять» подтверждает результат.
- Realtime-соединение с автопереподключением, часы (приблизительные).

Пока нет: создание и принятие вызовов, поиск игры, чат, отмена хода, разбор партий, уведомления, партии с форой и свободной расстановкой.

## Запуск

1. Зарегистрируйте своё приложение на <https://online-go.com/oauth2/applications/>:
   - **Client type**: Public
   - **Authorization grant type**: Authorization code
   - **Redirect URIs**: `gobo://callback`

   Скопируйте Client ID. Секрет не нужен.
2. Откройте папку проекта в Android Studio (нужен JDK 17+) и дождитесь синхронизации Gradle.
   Версии зафиксированы: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9. Если Studio предложит обновить — можно согласиться.
3. Запустите на устройстве или эмуляторе с Android 8.0+.
4. В приложении вставьте Client ID и нажмите «Войти через браузер».

Вход по паролю работает, только если сервер разрешил вашему приложению grant `password`. Если такого варианта в форме регистрации нет — используйте вход через браузер.

Юнит-тесты логики (без эмулятора): `./gradlew :core:test`.

## Как получить APK

- **Android Studio**: меню Build → Build Bundle(s) / APK(s) → Build APK(s). Файл появится в `app/build/outputs/apk/debug/app-debug.apk`.
- **Командная строка** (нужен установленный Android SDK): `./gradlew :app:assembleDebug`.
- **Без установки Android Studio**: загрузите проект в репозиторий на GitHub. Файл `.github/workflows/build-apk.yml` сам соберёт debug-APK при пуше (или вручную: Actions → Build APK → Run workflow). Готовый файл лежит в Artifacts запуска под именем `ogs-mini-debug-apk`.

Чтобы поставить APK на телефон, разрешите установку из неизвестных источников для приложения, через которое открываете файл.

## Что проверено

- `:core` (доска, захваты, ко, самоубийство, разбор сообщений OGS, состояние партии, PKCE по вектору из RFC 7636) скомпилирован, **17 тестов проходят**.
- Типы логики приложения (ViewModel, REST, WebSocket) проверены компилятором на заглушках Android и OkHttp.

## Что НЕ проверено

- Сборка APK и экраны Compose: в среде, где писался проект, не было Android SDK.
- Работа с живым сервером OGS: не было ни сети до него, ни учётной записи. Протокол realtime-API в документации описан скупо, поэтому:
  - адрес WebSocket неоднозначен — клиент перебирает `wss://wsp.online-go.com`, `wss://online-go.com/ws`, `wss://online-go.com/socket`;
  - нумерация ходов (`move_number`) проверяется терпимо, при расхождении состояние запрашивается заново;
  - расстановка в партиях с форой берётся из `initial_state`, если сервер её прислал;
  - формат часов поддержан только для вариантов с `thinking_time`.

Если что-то не работает, смотрите Logcat с тегом `OgsMini`.

## Устройство

```
core/   чистый Kotlin, без Android: GoBoard, GameSession, Wire (JSON-сообщения), Pkce, Coord
app/    Android: AppViewModel, Store, net/OgsApi (REST), net/OgsSocket (WebSocket), ui/* (Compose)
```

Используемые адреса: `/oauth2/authorize/`, `/oauth2/token/`, `GET /api/v1/ui/config` (поле `user_jwt`),
`GET /api/v1/ui/overview` (`active_games`). Сообщения WebSocket — JSON-массивы `[команда, данные]`:
`authenticate`, `net/ping`, `game/connect`, `game/move` (`"dd"`, пас — `".."`), `game/resign`,
`game/removed_stones/set|accept`; события `game/<id>/gamedata|move|clock|phase|removed_stones`.

Документация OGS: <https://docs.online-go.com>
