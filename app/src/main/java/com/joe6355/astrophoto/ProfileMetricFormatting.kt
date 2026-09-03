package com.joe6355.astrophoto

import java.util.Locale

internal fun formatMetric(value: Float): String =
    if (value.isFinite()) "%.4f".format(Locale.US, value) else "n/a"
