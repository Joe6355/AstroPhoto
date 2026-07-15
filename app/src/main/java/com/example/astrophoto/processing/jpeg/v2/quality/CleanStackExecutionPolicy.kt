package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision

class CleanStackExecutionPolicy {
    fun shouldExecuteStage4(cleanStackDecision: QualityGateDecision): Boolean =
        cleanStackDecision.accepted
}
