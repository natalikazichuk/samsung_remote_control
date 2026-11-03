# Пульт для Samsung (Android, Kotlin)

## Установка
1. Android Studio → New Project → **Empty Activity** (Compose), package `com.example.samsungremote`.
2. Замените `MainActivity.kt` и добавьте `SamsungRemote.kt` в ту же папку пакета.
3. В `app/build.gradle.kts` добавьте зависимость:
   ```
   implementation("com.squareup.okhttp3:okhttp:4.12.0")
   ```
4. В `AndroidManifest.xml` перед `<application>`:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.VIBRATE" />
   ```
5. Запустите на телефоне, который в той же Wi-Fi сети, что и ТВ.

## Использование
- IP телевизора: Настройки ТВ → Общие → Сеть → Состояние сети → Параметры IP.
- Нажмите **OK**, на экране ТВ выберите «Разрешить». Токен сохранится, повторно подтверждать не придётся.
- Если запроса нет: Настройки → Общие → Диспетчер внешних устройств → Диспетчер подключения устройств → Уведомление о доступе → включить.

## Заметки
- Работает на Samsung Smart TV с Tizen (примерно 2016+). Старые модели (2014–2015) используют другой протокол.
- Проверка сертификата отключена, потому что у ТВ самоподписанный сертификат. Приложение подключается только к IP в локальной сети.

## Сборка APK через GitHub Actions
Запушьте проект в репозиторий → вкладка **Actions** → **Build APK** → откройте последний запуск → в разделе **Artifacts** скачайте `samsung-remote-apk`.
