package com.winlator.renderer.material;

/**
 * Frame-blending material used by the Frame Generation (dup + blend) pipeline.
 * Mixes the current composited frame with the previous one to synthesize
 * intermediate frames at display rate.
 */
public class FrameBlendMaterial extends ShaderMaterial {
    public final Uniforms uniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform currentTexture = new Uniform("currentTexture");
        public final Uniform previousTexture = new Uniform("previousTexture");
        public final Uniform mixAmount = new Uniform("mixAmount");
        public final Uniform resolution = new Uniform("resolution");
    }

    @Override
    protected String getVertexShader() {
        return String.join("\n",
            "attribute vec2 position;",
            "varying vec2 vUV;",

            "void main() {",
                "vUV = position;",
                "gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);",
            "}"
        );
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision mediump float;",

            "uniform sampler2D currentTexture;",
            "uniform sampler2D previousTexture;",
            "uniform float mixAmount;",
            "uniform vec2 resolution;",

            "varying vec2 vUV;",

            "void main() {",
                "vec4 cur = texture2D(currentTexture, vUV);",
                "vec4 prev = texture2D(previousTexture, vUV);",
                "gl_FragColor = mix(cur, prev, mixAmount);",
            "}"
        );
    }
}
