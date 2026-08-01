# urban-window-30: уточнение strict star ground truth 2026-08-01

## Основание

- Исходный commit: `db07ad35e670dee37d9d19f30a4ac5ab1fe5b9bb`.
- Schema: `astrophoto.ground-truth/2`.
- Ground truth до изменения: `59b99a95ca1f039c97d957e455edb83b40651638ff1aaa485f4b5b65bf3c1b55`.
- Ground truth после изменения: `93979af0d4440ee31996ff1d0dde1ac17968b2c830dcbf6099e89e6f98b9996a`.
- Adjustment timestamp: `2026-08-01T11:46:13.114Z`.
- Compact adjustment SHA-256: `b3d1ca89f6d2775314662342c7060238501c6674c8faca4aefd0727b37c75dfc`.
- Full importer queue SHA-256: `b4032bf1e1f2861d807c8329f01ecb43116d36fa3edc7bcb07aab7f93570b917`.

Four manually reviewed candidates were moved from `star/confirmed` to `uncertain/needs_review` because the available evidence was not strong enough for the strict star denominator. They were not deleted and remain available for future review.

Их недостаточно надёжно использовать как strict ground truth.

Это не утверждение, что данные четыре объекта точно не являются звёздами.

## Изменённые решения

| ID | До | После | Причина | Strict eligible после |
|---|---|---|---|---|
| `candidate-x57900-y19400` | `star/confirmed` | `uncertain/needs_review` | Camera и sky recurrence только 2/30; temporal support недостаточен. | нет |
| `candidate-x56100-y27400` | `star/confirmed` | `uncertain/needs_review` | Визуальная структура слабая; надёжный компактный stellar PSF не доказан. | нет |
| `candidate-x61100-y48300` | `star/confirmed` | `uncertain/needs_review` | Сильно вытянутый footprint около 1×5, elongation около 8.5. | нет |
| `candidate-x56925-y74428` | `star/confirmed` | `uncertain/needs_review` | Длинный вытянутый след около 13.5×2.3 px, elongation около 5.95. | нет |

Все четыре stable ID, `x/y`, confidence, coordinate space, support и residual
fields сохранены. `annotation_source=manual` и `reviewed_by=project_owner` не
изменены. Старые notes сохранены; новый rationale добавлен отдельной строкой
`Review adjustment`.

## Метрики

| Метрика | До | После |
|---|---:|---:|
| Всего строк | 24 | 24 |
| Strict confirmed stars | 10 | 6 |
| Strict confirmed sensor defects | 2 | 2 |
| Rejected | 8 | 8 |
| Needs review | 2 | 6 |
| Automatic/unreviewed | 0 | 0 |

Финальный strict star set:

- `star-01`
- `star-02`
- `candidate-x55810-y22220`
- `candidate-x57000-y36200`
- `candidate-x42300-y43500`
- `candidate-x51700-y52400`

Strict sensor defects: `defect-01`, `defect-02`.

## Audit и idempotency

- Dry-run изменил ровно четыре заданные строки.
- Изменённые поля: `class`, `review_status`, `reviewed_at`, `notes`.
- Остальные 20 строк semantic equivalent; порядок и 24 stable ID сохранены.
- First audit: 4 решения,
  `59b99a95ca1f039c97d957e455edb83b40651638ff1aaa485f4b5b65bf3c1b55`
  → `93979af0d4440ee31996ff1d0dde1ac17968b2c830dcbf6099e89e6f98b9996a`.
- Повторный import: 0 изменений, before/after SHA одинаковы, output
  byte-identical.
- Atomic temporary files после операций отсутствовали.

Importer минимально расширен только для явного перехода
`manual star/confirmed` → `uncertain/needs_review`. Защита catalog labels и
конфликтной замены confirmed class сохранена regression-тестом.

## Проверки

- Ground-truth workflow: 27/27.
- Focused Stage 6, automatic sensor-filter и manual alignment: 74/74.
- Полный `testDebugUnitTest`: 978/978, failures 0, skipped 0.
- `assembleDebug`: успешно.
- Runtime production code, registration, transforms, frame weights, sensor
  mask, sky mask, quality thresholds и RAW/DNG не изменялись.
- APK не устанавливался.
- Generated queues, build reports, изображения, crops, ZIP, APK и device data
  в Git не включаются.

Следующий отдельный этап: replay-only sky-mask diagnostics. В рамках этого
изменения sky mask не менялся.
