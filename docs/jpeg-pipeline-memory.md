# JPEG pipeline memory ownership (Stage 7)

## Previous lifetime and crash cause

`JpegStacker.profileStack()` previously kept several full-resolution representations in one
function scope. At 1440×1920 there are 2,764,800 pixels: an ARGB `Bitmap`/`IntArray` or a
`FloatArray` is about 10.55 MiB, while a `BooleanArray` is about 2.64 MiB (excluding VM/object
overhead).

| Allocation | Previous owner | First use | Last use | Approx. bytes | Overlapped with |
|---|---|---|---|---:|---|
| integration `Bitmap` | `profileStack` | integration tile output | composition | 10.55 MiB | coverage, reference |
| valid coverage `FloatArray` | `profileStack` | integration | validation/report | 10.55 MiB | every candidate |
| reference `ArgbPixelImage` | `profileStack` | mask refinement | final selection | 10.55 MiB | integration/clean/processed |
| stacked-sky `ArgbPixelImage` | `profileStack` | composition | Stage 4 | 10.55 MiB | reference/clean |
| clean composite | result candidate | quality gate | final selection | 10.55 MiB | reference/processed |
| processed composite | result candidate | Stage 4 | PNG selection | 10.55 MiB | reference/clean |
| selected PNG `Bitmap` | `profileStack` | after selection | PNG save/finally | 10.55 MiB | all candidates |
| effective alpha/masks | mask/composer | mask refinement | report | 10–20 MiB | candidates and Stage 4 buffers |

Stage 4 also created a new full `IntArray` for most passes before the prior pass became
unreachable. The practical old peak was roughly 95–125 MiB plus JPEG decoder, Compose,
coroutine, histogram, and Android framework allocations. This explains a process kill without a
catchable exception on devices with a constrained application heap.

## Current ownership rules

- `REFERENCE`, `CLEAN_STACK`, and (when executed) `PROCESSED` are independent raw ARGB_8888
  files with width, height, format, and row-stride metadata.
- The selected result is only a handle to one existing candidate. It is never copied.
- Integration writes tiles directly to a file-backed stacked-sky image and coverage float plane.
- Composition reads stacked sky, reference, mask, and coverage by tile and writes the clean stack
  and effective alpha plane by tile. Stage 3 linear-light blending and exact foreground behavior
  are unchanged.
- Stage 4 separates histogram/statistics passes from processing passes. Each operation reads one
  file and writes the next; the chroma pass uses a bounded halo, and star enhancement stores only
  small changed regions.
- Quality statistics scan rows with bounded histograms. Foreground and line comparisons use
  synchronized row caches; star diagnostics use a bounded sample and small patches.
- PNG is streamed row-by-row from the selected file-backed candidate. The production path creates
  no final full-resolution `Bitmap`.
- The only full-resolution heap image is the reference `IntArray` used inside the short mask
  refinement stage. The decoded reference `Bitmap` is recycled before that array is allocated.
  Therefore the maximum simultaneous full-resolution heap images is one.

The memory budget uses:

```text
usedHeap = totalMemory - freeMemory
availableHeap = maxMemory - usedHeap
frameworkReserve = max(32 MiB, maxMemory / 4)
safeWorkingBudget = min(availableHeap × 0.60, availableHeap - frameworkReserve)
```

Every tile estimate includes ARGB, float, mask, and halo buffers. Memory pressure reduces tile or
strip dimensions while preserving output dimensions. A stage records at most one pressure retry;
unsafe minimum tiles fail before a full-image allocation. Resolution and aspect ratio are never
changed. Every registered allocation boundary logs both its estimate and the currently observed
used heap; the report retains the maximum of each value.

## Temporary files and recovery

Each run owns `cache/jpeg-stage7-<32 hex chars>/` with an exact internal marker containing the
directory name and run id. Only that validated directory is removed in `finally`. Stale cleanup
considers only directories with the exact prefix, valid marker, cache parent, and minimum age; it
never traverses session or public storage.

An atomic journal in app files is created before frame analysis and updated after every major
stage. A completed run is marked atomically. If Android kills the process, the next launch combines
the unfinished journal with `getHistoricalProcessExitReasons()` and shows the last completed stage
and classified exit reason.

The complete report is first written atomically to the run cache. Public report publication is
attempted after the PNG. If MediaStore rejects JSON, the report is retained in indexed app-specific
files, included in session ZIP export, and deleted only when its paired processed result is deleted.
