# Stage 6 candidate review

- Fixture: `urban-window-30`; frames: 30; reference: 9.
- Production processing modified: **false**.
- Registration: score 0.571818, residual 0.174559 px, motion -1.420425, 1.642776 px/frame.
- Final predicted displacement: -29.828934, 34.498306 px.
- Candidate review ROI: вручную проверенная внутренняя область неба fixture; production sky mask не изменён.
- Candidates: 24; star 3, sensor_defect 2, uncertain 19.
- `uncertain` is excluded from recall and retention metrics.

> All currently annotated reference stars were retained.

Эта формулировка относится только к текущей provisional-разметке и не означает, что удержаны все видимые звёзды.

## Candidates

| ID | class | shape | x | y | camera | sky | contrast | confidence | reason |
|---|---|---|---:|---:|---:|---:|---:|---:|---|
| candidate-x21900-y05600 | uncertain | irregular | 219 | 56 | 0/30 | 4/30 | 5.75 | 0.2 | Обнаружен только на reference frame |
| candidate-x21700-y07200 | uncertain | irregular | 217 | 72 | 0/30 | 6/30 | 5 | 0.2 | Обнаружен только на reference frame |
| candidate-x22100-y08800 | uncertain | irregular | 221 | 88 | 0/30 | 2/30 | 3 | 0.2 | Обнаружен только на reference frame |
| uncertain-01 | uncertain | irregular | 622 | 152 | 27/30 | 3/30 | 10 | 0.45 | Сохранена существующая ручная классификация |
| candidate-x60700-y16800 | uncertain | irregular | 607 | 168 | 0/30 | 1/30 | 3 | 0.2 | Обнаружен только на reference frame |
| uncertain-02 | uncertain | elongated | 688.591003 | 171.548004 | 30/30 | 7/30 | 13.72575 | 0.4 | Сохранена существующая ручная классификация |
| candidate-x57900-y19400 | uncertain | irregular | 579 | 194 | 2/30 | 2/30 | 3.1495 | 0.2 | Недостаточная или противоречивая temporal evidence |
| candidate-x55810-y22220 | star | elongated | 558.101013 | 222.20343 | 3/30 | 20/30 | 15 | 0.756664 | Когерентный sky-space трек отделён от camera-space recurrence |
| candidate-x56100-y27400 | uncertain | irregular | 561 | 274 | 3/30 | 19/30 | 4 | 0.316667 | Недостаточная или противоречивая temporal evidence |
| defect-01 | sensor_defect | elongated | 538 | 343 | 30/30 | 3/30 | 9 | 0.96 | Сохранена существующая ручная классификация |
| star-01 | star | elongated | 241.184998 | 348.618011 | 6/30 | 29/30 | 85.8075 | 0.93 | Сохранена существующая ручная классификация |
| star-02 | star | compact | 417.506989 | 360.619995 | 4/30 | 30/30 | 19.5 | 0.55 | Сохранена существующая ручная классификация |
| candidate-x57000-y36200 | uncertain | irregular | 570 | 362 | 3/30 | 23/30 | 6 | 0.383333 | Недостаточная или противоречивая temporal evidence |
| defect-02 | sensor_defect | elongated | 664 | 414 | 30/30 | 5/30 | 7.98375 | 0.94 | Сохранена существующая ручная классификация |
| candidate-x42300-y41900 | uncertain | irregular | 423 | 419 | 0/30 | 2/30 | 3 | 0.2 | Недостаточная или противоречивая temporal evidence |
| candidate-x42300-y43500 | uncertain | compact | 423 | 435 | 3/30 | 30/30 | 5.5 | 0.49 | Недостаточная или противоречивая temporal evidence |
| candidate-x21500-y44700 | uncertain | irregular | 215 | 447 | 0/30 | 2/30 | 3.5 | 0.2 | Обнаружен только на reference frame |
| candidate-x61100-y48300 | uncertain | elongated | 611 | 483 | 5/30 | 18/30 | 6 | 0.3 | Недостаточная или противоречивая temporal evidence |
| candidate-x51700-y52400 | uncertain | elongated | 517 | 524 | 5/30 | 23/30 | 10 | 0.383333 | Недостаточная или противоречивая temporal evidence |
| candidate-x57600-y64000 | uncertain | irregular | 576 | 640 | 1/30 | 1/30 | 3 | 0.2 | Обнаружен только на reference frame |
| candidate-x44900-y71700 | uncertain | irregular | 449 | 717 | 0/30 | 3/30 | 3.484 | 0.2 | Обнаружен только на reference frame |
| candidate-x43800-y72700 | uncertain | irregular | 438 | 727 | 0/30 | 9/30 | 4 | 0.2 | Обнаружен только на reference frame |
| candidate-x49500-y72700 | uncertain | irregular | 495 | 727 | 2/30 | 1/30 | 3 | 0.2 | Обнаружен только на reference frame |
| candidate-x56925-y74428 | uncertain | elongated | 569.248413 | 744.280273 | 5/30 | 19/30 | 36.057 | 0.316667 | Недостаточная или противоречивая temporal evidence |

## Residual trail provenance

| candidate | provenance | length | elongation | camera | sky | leaky rejected-path contrast | filtered contrast | rejected manual contributors |
|---|---|---:|---:|---:|---:|---:|---:|---|
| candidate-x55810-y22220 | uncertain | 9.223951 | 3.7741 | 3/30 | 20/30 | 0.162375 | 0.162375 |  |
| defect-01 | camera_space_defect_smeared_by_sky_alignment | 73.740393 | 16.597723 | 30/30 | 3/30 | 0.271375 | 0.125 |  |
| star-01 | already_present_in_individual_source_frames | 5.159121 | 3.293136 | 6/30 | 29/30 | 0 | 0.01425 |  |
| defect-02 | camera_space_defect_smeared_by_sky_alignment | 75.500536 | 35.058145 | 30/30 | 5/30 | 0.217062 | 0.143 |  |
| candidate-x61100-y48300 | uncertain | 6.798693 | 8.498366 | 5/30 | 18/30 | 0.11075 | 0.0285 |  |
| candidate-x51700-y52400 | uncertain | 7.004836 | 2.385055 | 5/30 | 23/30 | 0.007125 | 0.073375 |  |
| candidate-x56925-y74428 | possible_foreground_sky_mask_boundary | 13.532974 | 5.952473 | 5/30 | 19/30 | 0 | 0.257125 |  |

## Proven

- Clean-stack rejected frames have zero integration weight.
- Manual sequence stack assigns zero weight to 8 sequence-rejected frames: 22, 23, 24, 26, 27, 28, 29, 30.
- defect-01 rejected-path contrast: 0.271375 -> 0.125 (53.938277% reduction).
- defect-02 rejected-path contrast: 0.217062 -> 0.143 (34.120357% reduction).
- Camera-stable compact defects form diagonal paths after the sky transform; they are present before display enhancement.
- The manual diagnostic uses integer translation and nearest-neighbour sampling, so its trails are not interpolation artifacts.

## Uncertain

- Low-confidence, blended and reference-only candidates remain `uncertain`.
- A full astronomical recall value still requires a larger manual catalogue.
