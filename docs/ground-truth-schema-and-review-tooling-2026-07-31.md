# Ground Truth Schema и Manual Review Tooling — 2026-07-31

## Область изменений

Изменены только test/offline tooling, fixture metadata, тесты и документация.
Production-код в `app/src/main` не менялся; registration, transforms, frame weights,
sensor mask и JPEG/manual processing math не менялись.

## Аудит исходного состояния

| file/schema | поля | producer | consumer | manual/automatic | риск старого формата |
|---|---|---|---|---|---|
| `urban-window-30/ground-truth.csv`, legacy positional | `id,class,x,y,coordinate_space,support_frames,sky_residual_px,camera_residual_px,confidence,notes` | Stage 6 candidate diagnostics и fixture generator | `Stage6RegressionFixtureLoader`, Stage 6/automatic/manual tests | отсутствовало | `candidate-x55810-y22220` с provisional `star` входил в строгий star denominator |
| `urban-window-30/manifest.properties` | fixture, frames, reference, ground truth | fixture generator | fixture loader | отсутствовало | не фиксировал schema/provenance/coordinate metadata |
| `diagnostics/candidate-diagnostics.json`, `stage6-candidates/1` | кандидаты, recurrence, morphology, frame diagnostics | `Stage6CandidateDiagnosticRunner` | человек и regression tests | generated candidates имели стабильный `candidate-*` ID | JSON не был ground truth и не содержал review decision |
| новый `ground-truth.csv`, `astrophoto.ground-truth/2` | versioned superset | migration/candidate writer/review importer | единый CSV loader, метрики, review tooling | явные `annotation_source` и `review_status` | legacy loader сохранён |
| новый `ground-truth-metadata.properties` | dimensions, reference index, coordinate spaces, provenance | fixture audit/generator | fixture loader и review manifest | фиксированный список доказанных manual IDs | неизвестное происхождение не повышается до confirmed |
| generated `review-queue.csv`, `astrophoto.ground-truth-review/1` | read-only proposal + пустые decision fields | offline review generator | человек и отдельный importer | решение не применяется автоматически | duplicate/unknown/conflict/coordinate mutation отклоняются |

Исходный legacy SHA-256:
`d38485a51eb9e281c171c7ddeab0c81ab487cf9e2ed1c74a97473743a2e9b9b1`.
Резервная копия сохранена вне Git в
`app/build/reports/ground-truth-migration/urban-window-30/ground-truth-v1.csv`.

До миграции было 24 строки: 3 `star`, 2 `sensor_defect`, 19
`uncertain`. По существующему аудиту и поведению генератора доказаны 6 ранее
ручных строк: `star-01`, `star-02`, `defect-01`, `defect-02`, `uncertain-01`,
`uncertain-02`. Остальные 18 строк имеют generated ID `candidate-*`.

Старый код считал строгими 3 star и 2 defect, потому что provenance/status не
существовали. Ошибочно включалась автоматическая proposal-строка
`candidate-x55810-y22220`.

## Schema `astrophoto.ground-truth/2`

Канонический порядок полей:

```text
id,x,y,class,confidence,annotation_source,review_status,reviewed_by,reviewed_at,notes,coordinate_space,support_frames,sky_residual_px,camera_residual_px
```

Поддерживаются:

- class: `star`, `sensor_defect`, `uncertain`, безопасный `unknown`;
- annotation source: `manual`, `automatic`, `catalog`, `derived`, `unknown`;
- review status: `confirmed`, `unreviewed`, `rejected`, `needs_review`, `unknown`;
- quoted CSV с запятыми, кавычками и переводами строк;
- nullable confidence/residual/reviewer/time;
- legacy positional schema и произвольный порядок колонок новой schema;
- неизвестные enum как `unknown`, без падения loader.

Migration policy:

- только 6 доказанных manual IDs получили `manual/confirmed`;
- все 18 `candidate-*` получили `automatic/unreviewed`;
- legacy-строки без доказанного provenance получают `unknown/needs_review`;
- автоматический генератор никогда не создаёт `confirmed`.

Coordinate spaces зафиксированы в обязательном metadata-файле: decoded fixture,
reference frame `frame-008.jpg`, camera/source до sky transform и aligned output.
Координаты ручных строк и stable IDs сохранены.

## Строгие метрики

Единый eligibility-filter допускает:

- star retention/recall: `class=star`, `review_status=confirmed`, source
  `manual` или `catalog`;
- sensor-defect metrics: `class=sensor_defect`, `review_status=confirmed`, source
  `manual` или `catalog`.

Основной режим всегда исключает `uncertain`, `automatic`, `unreviewed`,
`needs_review`, `rejected`, `unknown` и `derived`. Все исключённые строки остаются
в diagnostics/review package.

Статистика `urban-window-30` после миграции:

| показатель | значение |
|---|---:|
| total rows/candidates | 24 |
| manual/confirmed rows | 6 |
| automatic/unreviewed | 18 |
| star (все proposals) | 3 |
| sensor_defect | 2 |
| uncertain | 19 |
| needs_review | 0 |
| rejected | 0 |
| strict confirmed stars | 2 |
| strict confirmed sensor defects | 2 |
| strict total denominator | 4 |

## Offline review package

Команда:

```powershell
$env:JAVA_HOME='D:\AndroidStudio\jbr'
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Generate-GroundTruthReview.ps1
```

Output: `app/build/reports/ground-truth-review/urban-window-30` (игнорируется Git).
Пакет содержит manifest, queue, общий contact sheet, native/nearest/grid crop для
каждого кандидата, reference overlay, camera recurrence, sky/aligned view,
before/after alignment, sensor-mask overlay и HTML с evidence/eligibility.
Пути, timestamps и пользовательские имена в детерминированный output не входят.

Два независимых запуска дали идентичные SHA-256 всех 131 файлов:

- `review-manifest.json`:
  `e5485019e2199af065ab0514a66373ba70762cfd9d938369c0a92cef7013b182`;
- `review-queue.csv`:
  `73480c8292c258f77b23f6eb011fe265c73403ed857f448883db3c98cd5d11af`;
- versioned `ground-truth.csv`:
  `bb3f5241fca47d29f6f6ab3dbcf7b5f4ab8fca48b5ff7ce9eb471859abeca8de`.

## Безопасный import

После ручного заполнения decision-полей:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Import-GroundTruthReview.ps1 `
  -ReviewQueue app\build\reports\ground-truth-review\urban-window-30\review-queue.csv
```

Importer:

- принимает только явно переданный queue;
- отклоняет duplicate/unknown ID, неизвестные решения, partial decision,
  конфликт с confirmed manual row и изменение `x/y`;
- требует reviewer и ISO-8601 `reviewed_at` для любого решения;
- пишет новый CSV и audit log атомарно в build output;
- запрещает совпадение input/output;
- повторный импорт одинакового решения даёт одинаковый output.

Пустая queue была проверена как `decisionCount=0`: новый output byte-identical
исходному (`bb3f5241...`), а исходный fixture CSV не перезаписывался.

> Automatic candidates are not treated as manual ground truth until explicitly reviewed and imported.

## Тесты и ограничения

Добавлены 22 focused cases для legacy/new schema, unknown enum, provenance
migration, strict eligibility, stable IDs/order, CSV escaping/nulls, duplicate,
conflict, coordinate protection, atomic/idempotent import и deterministic package.
Отдельные command tests проверяют реальные generator/importer scripts.

Проверки текущего commit-кандидата:

- combined focused fixture/automatic/manual/ground-truth suites: 107 tests,
  0 failures/errors;
- полный `testDebugUnitTest`: 973 tests, 0 failures/errors/skips;
- `assembleDebug`: successful;
- `git diff --check`: successful.

Новых ручных решений в этом изменении нет. Для 18 automatic/unreviewed строк
нужен человек; review package только показывает evidence и не подтверждает class.
Реальные пользовательские JPEG/PNG, session data, device dumps и build outputs в
Git не добавляются.
