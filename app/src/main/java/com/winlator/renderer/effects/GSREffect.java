package com.winlator.renderer.effects;

import com.winlator.renderer.material.GSRMaterial;
import com.winlator.renderer.material.ScreenMaterial;

/**
 * Qualcomm Snapdragon GSR (SGSR1) upscaling + sharpening effect.
 */
public class GSREffect extends Effect {
    private float renderScale = 1.0f;

    @Override
    public ScreenMaterial createMaterial() {
        return new GSRMaterial();
    }

    public GSRMaterial getGSRMaterial() {
        return (GSRMaterial)getMaterial();
    }

    public float getRenderScale() {
        return renderScale;
    }

    public void setRenderScale(float renderScale) {
        this.renderScale = renderScale;
    }
}
