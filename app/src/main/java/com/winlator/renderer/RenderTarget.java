package com.winlator.renderer;

import android.opengl.GLES20;

public class RenderTarget extends Texture {
    private int framebuffer;
    private int width;
    private int height;

    public RenderTarget() {
        super(null);
    }

    public RenderTarget(int format) {
        super(null);
        setFormat(format);
    }

    private void generateFramebuffer() {
        int[] framebuffers = new int[1];
        GLES20.glGenFramebuffers(1, framebuffers, 0);
        framebuffer = framebuffers[0];
    }

    public void allocateFramebuffer(int width, int height) {
        if (framebuffer != 0 && this.width == width && this.height == height) return;
        release();

        this.width = width;
        this.height = height;
        generateFramebuffer();
        generateTextureId();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, null);
        setTextureParameters();
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textureId, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    public int getFramebuffer() {
        return framebuffer;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void release() {
        if (framebuffer != 0) {
            int[] framebuffers = new int[]{framebuffer};
            GLES20.glDeleteFramebuffers(framebuffers.length, framebuffers, 0);
            framebuffer = 0;
        }
        destroy();
    }
}
