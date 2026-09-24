# Сборка подписанного Android-релиза

Для обновления уже установленного приложения все версии должны подписываться
одним постоянным ключом. Потеря ключа или пароля лишит пользователей возможности
установить обновление поверх прежней версии.

## Подготовка ключа

Создайте keystore вне репозитория и храните независимую резервную копию:

```bash
keytool -genkeypair -v \
  -keystore /private/path/family-music-release.jks \
  -alias family_music \
  -keyalg RSA -keysize 4096 -validity 10000
```

Создайте закрытый `signing.properties`:

```properties
storeFile=/private/path/family-music-release.jks
storePassword=CHANGE_ME
keyAlias=family_music
keyPassword=CHANGE_ME
```

Keystore, properties и пароли запрещено добавлять в Git, APK или диагностические
отчёты. Публичный сертификат и его fingerprint секретами не являются.

## Сборка

Перед сборкой увеличьте `versionCode` и обновите `versionName` в
[`app/build.gradle`](app/build.gradle).

```bash
ANDROID_HOME=/path/to/android-sdk \
FAMILY_MUSIC_SIGNING_PROPERTIES=/private/path/signing.properties \
./gradlew --no-daemon clean testDebugUnitTest assembleRelease
```

Проверьте подпись и сохраните контрольную сумму:

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

Для GitHub Actions keystore передаётся Base64-строкой через encrypted secret, а
пароли — отдельными encrypted secrets. Workflow должен создавать временные файлы
только внутри runner и удалять их после сборки. Личный ключ и пароли нельзя
печатать в job log или прикладывать как artifact.

Используются secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` и `ANDROID_KEY_PASSWORD`. После увеличения `versionCode` и
`versionName` выпуск создаётся аннотированным тегом той же версии:

```bash
git tag -a v1.0.29 -m "Family Music Android v1.0.29"
git push origin v1.0.29
```

Workflow проверит совпадение тега с `versionName`, запустит тесты, проверит
подпись и приложит к GitHub Release APK `f_music_v1.0.28.apk` и `SHA256SUMS`.
