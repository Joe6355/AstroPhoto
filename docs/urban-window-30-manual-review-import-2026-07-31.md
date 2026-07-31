# urban-window-30: импорт ручных решений 2026-07-31

## Входы

- Compact CSV: `C:\Users\79870\Downloads\review-decisions-template (1).csv`
- Compact CSV SHA-256: `a0591c5f616ec33452bb8da763c4843a05882cc4682e685e133be250da95521e`
- Canonical review queue SHA-256: `73480c8292c258f77b23f6eb011fe265c73403ed857f448883db3c98cd5d11af`
- Ground truth до импорта: `bb3f5241fca47d29f6f6ab3dbcf7b5f4ab8fca48b5ff7ce9eb471859abeca8de`
- Fixture manifest SHA-256: `f9eaadf471c1d4a48ca0bbf93c60ca2d5663a4306d2428e57278c2a416bbcca9`
- Schema: `astrophoto.ground-truth/2`

Compact CSV содержал 18 уникальных stable IDs: 8 `star/confirmed`, 8
`uncertain/rejected`, 2 `uncertain/needs_review` и 0
`sensor_defect/confirmed`. Все `reviewed_at` были валидными ISO-8601 и
перенесены без изменения. Поле `reviewed_by` было пустым во всех 18 решениях,
поэтому importer получил нейтральный идентификатор `project_owner` только для
этих явно выбранных решений.

## Результат

| Метрика | До | После |
|---|---:|---:|
| Всего строк | 24 | 24 |
| Strict confirmed stars | 2 | 10 |
| Strict confirmed sensor defects | 2 | 2 |
| Strict denominator | 4 | 12 |
| Automatic/unreviewed | 18 | 0 |
| Rejected | 0 | 8 |
| Needs review | 0 | 2 |

Новые confirmed stars:

- `candidate-x57900-y19400`
- `candidate-x55810-y22220`
- `candidate-x56100-y27400`
- `candidate-x57000-y36200`
- `candidate-x42300-y43500`
- `candidate-x61100-y48300`
- `candidate-x51700-y52400`
- `candidate-x56925-y74428`

Rejected:

- `candidate-x21900-y05600`
- `candidate-x21700-y07200`
- `candidate-x22100-y08800`
- `candidate-x21500-y44700`
- `candidate-x57600-y64000`
- `candidate-x44900-y71700`
- `candidate-x43800-y72700`
- `candidate-x49500-y72700`

Needs review:

- `candidate-x60700-y16800`
- `candidate-x42300-y41900`

Existing confirmed rows `star-01`, `star-02`, `defect-01`, `defect-02`,
`uncertain-01` и `uncertain-02` не изменены. Stable IDs, порядок строк,
числовые координаты, confidence, notes, support и residual fields совпадают с
исходным ground truth. Для семи решений, изменивших класс с `uncertain` на
`star`, штатный importer установил `coordinate_space=sky`; сами `x/y` не
изменились.

These labels were explicitly supplied through the manual review workflow. No additional candidates were confirmed automatically.

## Dry-run и audit

- Generated full queue SHA-256: `50846749b0bd5f800320dc28243c7388e7c23da791607586a1c71cd3de042b57`
- Первый import audit: 18 изменений,
  `bb3f5241fca47d29f6f6ab3dbcf7b5f4ab8fca48b5ff7ce9eb471859abeca8de`
  → `59b99a95ca1f039c97d957e455edb83b40651638ff1aaa485f4b5b65bf3c1b55`.
- Tracked ground truth после импорта byte-identical dry-run output; SHA-256:
  `59b99a95ca1f039c97d957e455edb83b40651638ff1aaa485f4b5b65bf3c1b55`.
- Повторный импорт результата: 0 изменений; before/after SHA-256 одинаковы.
- Atomic output и audit записаны успешно; временных файлов после операций не
  осталось.

До исправления реальный повторный импорт останавливался с
`Review annotation source differs for candidate-x21900-y05600`. Importer
расширен точным распознаванием уже применённого решения. Regression test теперь
повторно импортирует первый output, проверяет byte identity и 0 изменений.

## Проверки

- Ground-truth workflow: 26/26.
- Focused Stage 6, automatic sensor-filter и manual alignment: 74/74.
- Полный `testDebugUnitTest`: 977/977, failures 0, skipped 0.
- `assembleDebug`: успешно.
- `git diff --check`: успешно.
- Изменений в `app/src/main`, runtime processing, registration, transforms,
  frame weights, sensor mask, quality thresholds, sky mask и RAW/DNG нет.
- APK не устанавливался; фотографии, generated reports, ZIP, APK и device data
  в commit не включаются.

Следующий отдельный этап — sky-mask diagnostics. В рамках этого импорта sky mask
не менялся.
