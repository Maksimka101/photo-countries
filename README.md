# Где я был

Приложение считает, в каких странах и сколько дней вы провели, — по времени и геометкам ваших фото.

- **Источники:** Яндекс Диск и/или галерея телефона (нужен хотя бы один, второй подключается позже).
- **Страны:** список стран с суммой дней и числом визитов.
- **Таймлайн:** лента пребываний по годам; транзиты и короткие заезды помечены, чужие фото можно скрыть вручную.
- **Годы:** год как полоса дней в цветах стран; тап по стране показывает даты и длительность.

Сами фото не скачиваются и никуда не отправляются: читаются только время и координаты съёмки.
Страна определяется на устройстве по полигонам [Natural Earth](https://www.naturalearthdata.com/).

## Сборка

Kotlin Multiplatform + Compose Multiplatform: Android и desktop (JVM). Нужны JDK 21 и Android SDK
(`sdk.dir` в `local.properties`).

```sh
./gradlew :shared:jvmTest              # тесты
./gradlew :androidApp:assembleDebug    # APK: androidApp/build/outputs/apk/debug/
./gradlew :shared:run                  # desktop (только Яндекс Диск, вход вставкой токена)
```

Релиз (`assembleRelease`) пока подписывается отладочным ключом.

## Устройство

- `shared/` — весь код: `core` (точки, страны, лента), `yandex` (API Диска, OAuth), `gallery` (MediaStore),
  `data` (файловое хранилище), `ui` (экраны).
- `androidApp/` — Activity, манифест, ресурсы.
- `prototype/` — исходный прототип на Python и упаковщик полигонов `pack_countries.py`.

Вход в Яндекс — OAuth implicit flow с возвратом на `photocountries://auth`. ID клиента лежит в
`shared/.../yandex/OAuth.kt`; он публичный, секрета у клиента нет.
