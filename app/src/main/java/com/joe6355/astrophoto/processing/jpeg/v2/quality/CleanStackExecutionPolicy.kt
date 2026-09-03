package com.joe6355.astrophoto.processing.jpeg.v2.quality

import com.joe6355.astrophoto.processing.jpeg.v2.model.QualityGateDecision

class CleanStackExecutionPolicy {
    fun shouldExecuteStage4(cleanStackDecision: QualityGateDecision): Boolean =
        cleanStackDecision.accepted
}
