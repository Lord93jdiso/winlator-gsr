package com.winlator.renderer;

import android.opengl.GLES20;

import com.winlator.math.Mathf;
import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.effects.GSREffect;
import com.winlator.renderer.material.FrameBlendMaterial;
import com.winlator.renderer.material.GSRMaterial;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.xserver.ScreenInfo;

import java.util.ArrayList;

public class EffectComposer {
    private final GLRenderer renderer;
    private RenderTarget readBuffer = null;
    private RenderTarget writeBuffer = null;
    private final ArrayList<Effect> effects = new ArrayList<>();

    // ----- Snapdragon GSR (SGSR1) state -----
    private RenderTarget gsrBuffer = null;                 // guest-resolution buffer
    private final ViewTransformation gsrViewTransformation = new ViewTransformation();
    private final GSREffect gsrEffect = new GSREffect();
    private volatile boolean gsrEnabled = false;
    private short gsrScreenWidth = 0;
    private short gsrScreenHeight = 0;

    // ----- Frame Generation (dup + blend) state -----
    private RenderTarget prevFrameBuffer = null;           // previous composited frame (surface resolution)
    private final FrameBlendMaterial frameBlendMaterial = new FrameBlendMaterial();
    private final ScreenEffectPass screenEffectPass = new ScreenEffectPass();
    private volatile boolean frameGenEnabled = false;
    private volatile boolean contentDirty = true;
    private float frameAge = 0.0f;

    private static class ScreenEffectPass extends Effect {
        @Override
        public ScreenMaterial createMaterial() {
            return new ScreenMaterial();
        }
    }

    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public synchronized void addEffect(Effect effect) {
        if (!effects.contains(effect)) effects.add(effect);
        renderer.requestRender();
    }

    public synchronized void removeEffect(Effect effect) {
        effects.remove(effect);
        renderer.requestRender();
    }

    public synchronized <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) return (T)effect;
        }
        return null;
    }

    public synchronized boolean hasEffects() {
        return !effects.isEmpty();
    }

    // ====================== Snapdragon GSR ======================

    public synchronized void setGSREnabled(boolean enabled) {
        if (this.gsrEnabled == enabled) return;
        this.gsrEnabled = enabled;
        renderer.requestRender();
    }

    public synchronized boolean isGSREnabled() {
        return gsrEnabled;
    }

    public synchronized void setGSRRenderScale(float scale) {
        gsrEffect.setRenderScale(scale);
    }

    public synchronized float getGSRRenderScale() {
        return gsrEffect.getRenderScale();
    }

    // ====================== Frame Generation ======================

    public synchronized void setFrameGenEnabled(boolean enabled) {
        if (this.frameGenEnabled == enabled) return;
        this.frameGenEnabled = enabled;
        contentDirty = true;
        frameAge = 0.0f;
        renderer.setContinuousRendering(enabled);
        renderer.requestRender();
    }

    public synchronized boolean isFrameGenEnabled() {
        return frameGenEnabled;
    }

    /**
     * Called from the XServer request thread while it holds the XServer lock.
     * MUST NOT block: no monitor acquisition here (render() on the GL thread
     * waits for that same XServer lock). Volatile write only.
     */
    public void markContentDirty() {
        renderer.contentUpdateCount.incrementAndGet();
        contentDirty = true;
        if (frameGenEnabled) renderer.requestRender();
    }

    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
    }

    private void renderEffect(Effect effect, Texture inputTexture) {
        ScreenMaterial material = effect.getMaterial();
        material.use();
        renderer.quadVertices.bind(material.programId);

        material.setUniformVec2(material.uniforms.resolution, renderer.surfaceWidth, renderer.surfaceHeight);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture.getTextureId());
        material.setUniformInt(material.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void renderGSREffect(Texture inputTexture) {
        GSRMaterial material = gsrEffect.getGSRMaterial();
        material.use();
        renderer.quadVertices.bind(material.programId);

        float srcWidth = Math.max(gsrScreenWidth, 1);
        float srcHeight = Math.max(gsrScreenHeight, 1);
        int surfaceWidth = renderer.surfaceWidth;
        int surfaceHeight = renderer.surfaceHeight;

        // ViewTransformation maps the X screen into the display surface (letterboxing included).
        ViewTransformation vt = gsrViewTransformation;
        float uvScaleX = surfaceWidth / (float)Math.max(vt.viewWidth, 1);
        float uvScaleY = surfaceHeight / (float)Math.max(vt.viewHeight, 1);
        float uvOffsetX = vt.viewOffsetX / srcWidth;
        float uvOffsetY = vt.viewOffsetY / srcHeight;

        material.setUniformVec4(material.gsrUniforms.viewportInfo, 1.0f / srcWidth, 1.0f / srcHeight, srcWidth, srcHeight);
        material.setUniformVec4(material.gsrUniforms.uvTransform, uvScaleX, uvScaleY, uvOffsetX, uvOffsetY);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture.getTextureId());
        material.setUniformInt(material.gsrUniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void initBuffers() {
        if (readBuffer == null) readBuffer = new RenderTarget();
        if (writeBuffer == null) writeBuffer = new RenderTarget();
        readBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        writeBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);

        if (gsrEnabled) {
            ScreenInfo screenInfo = renderer.getXServer().screenInfo;
            if (gsrBuffer == null) gsrBuffer = new RenderTarget();
            gsrBuffer.allocateFramebuffer(screenInfo.width, screenInfo.height);
            gsrScreenWidth = screenInfo.width;
            gsrScreenHeight = screenInfo.height;
            gsrViewTransformation.update(gsrScreenWidth, gsrScreenHeight, gsrScreenWidth, gsrScreenHeight);
        }

        if (frameGenEnabled) {
            if (prevFrameBuffer == null) prevFrameBuffer = new RenderTarget();
            prevFrameBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        }
    }

    /**
     * Draws the texture "input" full-screen into "target" (or the default framebuffer when target == null).
     */
    private void blitTexture(Texture input, RenderTarget target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target != null ? target.getFramebuffer() : 0);
        GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
        renderer.viewportNeedsUpdate = true;
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        renderEffect(screenEffectPass, input);
    }

    /**
     * Copies the current composited frame (readBuffer) into the previous-frame buffer.
     */
    private void capturePreviousFrame() {
        blitTexture(readBuffer, prevFrameBuffer);
    }

    /**
     * Presents a blended frame (current + previous) to the display - the heart of
     * the Frame Generation (dup + blend) algorithm.
     */
    private void presentBlendedFrame(float mixAmount) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
        renderer.viewportNeedsUpdate = true;
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        FrameBlendMaterial material = frameBlendMaterial;
        material.use();
        renderer.quadVertices.bind(material.programId);

        material.setUniformVec2(material.uniforms.resolution, renderer.surfaceWidth, renderer.surfaceHeight);
        material.setUniformFloat(material.uniforms.mixAmount, Mathf.clamp(mixAmount, 0.0f, 0.75f));

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readBuffer.getTextureId());
        material.setUniformInt(material.uniforms.currentTexture, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevFrameBuffer.getTextureId());
        material.setUniformInt(material.uniforms.previousTexture, 1);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /**
     * Runs on the GL thread only. MUST NOT be synchronized: it internally waits
     * for the XServer lock (renderWindows), and the XServer thread reaches
     * markContentDirty() while holding that lock. A synchronized render() +
     * synchronized markContentDirty() is an AB-BA deadlock (hard freeze as soon
     * as GSR/FrameGen routes frames through the composer). All internal state
     * below is touched only by the GL thread; cross-thread flags are volatile.
     */
    public void render() {
        if (renderer.surfaceWidth <= 0 || renderer.surfaceHeight <= 0) return;
        initBuffers();

        boolean useGSR = gsrEnabled;

        // -------- Source pass: composite X content --------
        if (useGSR) {
            // Pass 1: draw the X content 1:1 into the guest-resolution buffer.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, gsrBuffer.getFramebuffer());
            GLES20.glViewport(0, 0, gsrScreenWidth, gsrScreenHeight);
            renderer.viewportNeedsUpdate = true;
            renderer.drawFrame(gsrViewTransformation);

            // Pass 2: Snapdragon GSR - upscale + sharpen into the first effect buffer.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, writeBuffer.getFramebuffer());
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            renderer.viewportNeedsUpdate = true;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            try {
                renderGSREffect(gsrBuffer);
            }
            catch (RuntimeException e) {
                // Shader failed to compile/run on this GPU: fall back to the standard pipeline.
                gsrEnabled = false;
            }
            swapBuffers();
        }
        else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hasEffects() || frameGenEnabled ? readBuffer.getFramebuffer() : 0);
            renderer.drawFrame();
        }

        // -------- Effect chain --------
        int lastNonGSRIndex = -1;
        for (int i = 0; i < effects.size(); i++) {
            if (!(effects.get(i) instanceof GSREffect)) lastNonGSRIndex = i;
        }

        Texture input = readBuffer;
        boolean first = true;

        for (int i = 0; i < effects.size(); i++) {
            Effect effect = effects.get(i);
            if (effect instanceof GSREffect) continue; // already applied in the GSR pass

            boolean isLast = i == lastNonGSRIndex;
            boolean renderToScreen = isLast && !frameGenEnabled;

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderToScreen ? 0 : writeBuffer.getFramebuffer());
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            renderer.viewportNeedsUpdate = true;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            renderEffect(effect, input);
            swapBuffers();
            input = readBuffer;
            first = false;
        }

        // -------- Frame Generation presentation --------
        if (frameGenEnabled) {
            if (contentDirty) {
                capturePreviousFrame();
                contentDirty = false;
                frameAge = 0.0f;
                blitTexture(readBuffer, null);
            }
            else {
                frameAge += 1.0f;
                presentBlendedFrame(frameAge / (frameAge + 1.0f));
            }
        }
        else if (useGSR && first) {
            // GSR was the only effect: present the upscaled image.
            blitTexture(readBuffer, null);
        }
    }
}
