# ApkCloner

Мінімалістичний Android-застосунок для встановлення клонів APK під іншим `applicationId`
(аналог "second space"/"dual apps", що вбудовані в деякі заводські прошивки).

## Як це працює

Замість повної декомпіляції через apktool, застосунок патчить **лише бінарний
AndroidManifest.xml** напряму — знаходить у глобальному String Pool рядки, що дорівнюють або
починаються з оригінального package name (`com.example.app`, `com.example.app.MainActivity`,
`com.example.app.provider`, дозволи типу `com.example.app.permission.C2D_MESSAGE` тощо), і
переписує префікс на новий package name. Це працює, бо `Context#getPackageName()` в рантаймі
повертає значення, яке PackageManager бере з інстальованого маніфесту — а не хардкоджене
значення з dex-коду.

Пайплайн (`CloneEngine` → `ApkSignerHelper` → `ApkInstaller`):

1. Копіюємо `sourceDir` обраного застосунку.
2. `AxmlStringPoolPatcher` перезаписує string pool у `AndroidManifest.xml`.
3. Перезбираємо zip (APK), зберігаючи метод стиснення кожного entry (STORED/DEFLATED).
4. Підписуємо новим self-signed ключем через `AndroidKeyStore` + Google's `apksig`
   (той самий рушій, що використовує офіційний `apksigner`) — без Bouncy Castle і без
   зовнішнього `.jks`.
5. Встановлюємо через публічний `PackageInstaller` Session API.

## Обмеження (навмисні, для мінімалізму)

- **dex/код не патчиться.** Якщо застосунок хардкодить свій package name як текстовий
  рядок у коді (рідкість), клон може працювати некоректно.
- **Firebase/push-сповіщення** в клоні не працюватимуть без окремого налаштування Firebase
  проєкту під новий package name + SHA-1.
- **Застосунки з anti-tamper/signature pinning** (банкінг тощо) відмовляться запускатись.
- **zipalign не виконується** — якщо потрібно, додайте крок вирівнювання окремо
  (наприклад, через `zipalign` з Android SDK build-tools у CI) — функціональності це не
  заважає, лише продуктивність mmap для великих APK.
- Усі клони підписуються одним і тим самим ключем, згенерованим на пристрої в
  `AndroidKeyStore` — оновлення клону іншим ключем пізніше призведе до помилки
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Збірка

Локально (потрібен встановлений Gradle 8.7+ і Android SDK):

```bash
gradle assembleDebug
```

Або просто запуште в `main` — GitHub Actions (`.github/workflows/android-build.yml`)
збере debug APK і виставить його як build-артефакт (без потреби комітити Gradle wrapper —
workflow сам встановлює потрібну версію Gradle).

## Дозволи, які має надати користувач на пристрої

- "Install unknown apps" для цього застосунку (запитується автоматично при першому запуску).
- Сповіщення (Android 13+) — для індикатора прогресу під час патчингу/підпису.
