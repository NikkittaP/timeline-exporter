# Play Store screenshots — «Aurora»

Превращает сырые снимки экрана от **screengrab** в оформленные картинки для
Google Play: градиентный фон, устройство в рамке, локализованный заголовок.
Шаблон — обычный HTML/CSS, рендерит headless Chromium через Playwright.

Chromium сам занимается шейпингом текста, поэтому арабский (RTL + лигатуры),
китайский, японский, корейский, хинди и тайский рендерятся корректно —
без libraqm и ручного бидирекционального кода.

```
fastlane/screenshots_raw/<locale>/<slot>/03_map_fullscreen.png   ← сырые снимки
                    ↓  node render.mjs
fastlane/metadata/android/<locale>/images/phoneScreenshots/01_map.png   ← в стор
```

---

## Установка (один раз)

```bash
cd tools/store_shots
npm install
npx playwright install chromium
```

Node.js 18+. Шрифты берутся из `tools/fonts/` (те же Noto, что использовал
`add_hero_caption.py`) плюс Inter из npm — ничего скачивать вручную не нужно.

---

## Обычный цикл

```bash
# 1. снять сырые скриншоты (как и раньше)
bundle exec fastlane screens_phone       # ← сам вызовет ingest + decorate

# всё, готово. Проверить:
#   fastlane/metadata/android/en-US/images/phoneScreenshots/
```

Лейны `screens_phone` / `screens_tablet7` / `screens_tablet10` теперь делают
три шага: capture → ingest (унести сырое в `screenshots_raw/`) → decorate.

### Перерисовать, не пересобирая APK и не гоняя эмулятор

Сырые снимки лежат в `fastlane/screenshots_raw/` — правьте тексты и рендерьте
сколько угодно:

```bash
bundle exec fastlane decorate_screens                    # всё: 18 локалей × 3 экрана
bundle exec fastlane decorate_screens slot:phone
node render.mjs --locales ru-RU,ar --slot phone          # то же напрямую
```

### Прочие команды

```bash
node render.mjs --palette orange           # другая палитра
node render.mjs --frames 01_map,05_privacy # только эти кадры
node render.mjs --no-feature               # без feature graphic
node render.mjs --preview-only             # рендер в ./preview, метаданные не трогаются
node render.mjs --ingest --slot phone      # только перенести сырое из metadata в raw
```

---

## Что где лежит

```
config.json          палитры, размеры, геометрия, список кадров и локалей
locales/<play>.json  тексты: eyebrow / title / sub на каждый кадр
template/theme.js    вся вёрстка и стили
render.mjs           рендерер
```

### Тексты

`locales/en-US.json`:

```jsonc
"privacy": {
  "eyebrow": "Private by design",
  "title":   "100% {on-device}",          // {…} красится акцентным цветом
  "sub":     "Your location data never leaves your phone"
}
```

Имена файлов — это **коды локалей Play Console** (те же папки, что в
`fastlane/metadata/android/`). 18 языков уже заполнены; строки, кроме
английского и русского, — черновой перевод, стоит вычитать.

### Кадры

`config.json → frames` связывает сырой снимок с текстом и именем в сторе:

```jsonc
{ "out": "01_map", "src": "03_map_fullscreen", "key": "map" }
```

`src` — имя файла от screengrab, `key` — ключ в `locales/*.json`,
`out` — как файл будет называться в сторе (порядок = сортировка по имени;
первые три видны без прокрутки).

### Экраны (slots)

| slot | кадр (портрет) | кадр (ландшафт) | папка Play |
|---|---|---|---|
| `phone` | 1080×1920 | — | `phoneScreenshots` |
| `sevenInch` | 1200×1920 | 1920×1200 | `sevenInchScreenshots` |
| `tenInch` | 1600×2560 | 2560×1600 | `tenInchScreenshots` |

**Ориентация определяется по снимку.** Планшетные эмуляторы у нас снимают
в ландшафте (1920×1080 и 2560×1600) — для таких кадров берётся ландшафтный
размер из `slots[].landscape`, заголовок уходит наверх, а устройство
центрируется в оставшемся месте. Play принимает и 16:9, и 9:16.

Пропорции устройства берутся из самого снимка, а его размер подбирается так,
чтобы заполнить место под заголовком: короткое устройство центрируется,
высокое — слегка уходит за нижний край. Ничего руками подгонять не нужно.

Кегль заголовка масштабируется от короткой стороны кадра, так что 10"
планшет не получает гигантский текст.

### Палитры

`config.json → palette`: `blue` (по умолчанию, под иконку), `orange`,
`midnight`. Добавить свою — дописать блок в `palettes`.

---

## Локализация и длинные строки

* Заголовок и подпись **сами уменьшаются**, пока не влезут в
  `type.titleLines` / `type.subLines` строк.
* Если после этого текст всё равно упирается в устройство, рендерер уменьшает
  кегль дальше и печатает предупреждение — это сигнал сократить строку,
  а не баг.
* Нет `captures/<locale>/` — берётся `defaultLocale`, в лог падает
  предупреждение (полезно, когда эмулятор не смог переключить язык).
* Шрифт под язык выбирается в `fonts.stacks`; для CJK Inter стоит первым,
  чтобы латиница и «·» не разъезжались по полуширинным метрикам.
* Ненулевой exit code — только если нет строки, нет сырого снимка или нет
  файла локали.

---

## Идемпотентность

`--ingest` **переносит** файлы из `metadata/.../images/<slot>Screenshots/`
в `screenshots_raw/<locale>/<slot>/`, а рендер кладёт в папку метаданных
маркер `.decorated`. Повторный `--ingest` такую папку пропустит, так что
оформленную картинку нельзя случайно оформить второй раз. Хотите переснять —
удалите `.decorated`.

---

## CI

```yaml
- run: npm ci --prefix tools/store_shots
- run: npx --prefix tools/store_shots playwright install --with-deps chromium
- run: node tools/store_shots/render.mjs
```
