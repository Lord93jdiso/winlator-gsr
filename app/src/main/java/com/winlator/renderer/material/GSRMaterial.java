package com.winlator.renderer.material;

import android.opengl.GLES30;

/**
 * Qualcomm Snapdragon GSR (SGSR1) mobile upscaler material.
 *
 * Shader source: SnapdragonGameStudios/snapdragon-gsr (sgsr/v1/include/glsl/sgsr1_shader_mobile.frag)
 * Copyright (c) 2025, Qualcomm Innovation Center, Inc. - SPDX-License-Identifier: BSD-3-Clause
 *
 * Ported into Winlator GSR Edition as a single-pass upscaling + sharpening effect
 * for the Adreno GPU family (Snapdragon 680 / Adreno 610 included).
 */
public class GSRMaterial extends ScreenMaterial {
    public final GSRUniforms gsrUniforms = new GSRUniforms();

    public static class GSRUniforms {
        // vec4(1.0/inputWidth, 1.0/inputHeight, inputWidth, inputHeight)
        public final Uniform viewportInfo = new Uniform("ViewportInfo[0]");
        // vec4(scaleX, scaleY, offsetX, offsetY) - source UV transform
        public final Uniform uvTransform = new Uniform("uvTransform");
        public final Uniform screenTexture = new Uniform("ps0");
    }

    @Override
    protected String getVertexShader() {
        return String.join("\n",
            "#version 300 es",
            "precision highp float;",

            "in vec2 position;",
            "uniform highp vec4 uvTransform;",
            "layout(location=0) out highp vec4 in_TEXCOORD0;",

            "void main() {",
                "vec2 uv = position * uvTransform.xy + uvTransform.zw;",
                "in_TEXCOORD0 = vec4(uv, 0.0, 1.0);",
                "gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);",
            "}"
        );
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "#version 300 es",

            "precision mediump float;",
            "precision highp int;",

            "//#define OperationMode 1 (RGBA)",
            "#define EdgeThreshold 8.0/255.0",
            "#define EdgeSharpness 2.0",

            "uniform highp vec4 ViewportInfo[1];",
            "uniform mediump sampler2D ps0;",

            "layout(location=0) in highp vec4 in_TEXCOORD0;",
            "layout(location=0) out vec4 out_Target0;",

            "float fastLanczos2(float x) {",
                "float wA = x-4.0;",
                "float wB = x*wA-wA;",
                "wA *= wA;",
                "return wB*wA;",
            "}",

            "vec2 weightY(float dx, float dy, float c, float std) {",
                "float x = ((dx*dx)+(dy*dy))*0.55 + clamp(abs(c)*std, 0.0, 1.0);",
                "float w = fastLanczos2(x);",
                "return vec2(w, w * c);",
            "}",

            "void main() {",
                "const int mode = 1;",
                "float edgeThreshold = EdgeThreshold;",
                "float edgeSharpness = EdgeSharpness;",

                "vec4 color;",
                "color.xyzw = textureLod(ps0, in_TEXCOORD0.xy, 0.0).xyzw;",

                "highp vec2 imgCoord = ((in_TEXCOORD0.xy * ViewportInfo[0].zw) + vec2(-0.5, 0.5));",
                "highp vec2 imgCoordPixel = floor(imgCoord);",
                "highp vec2 coord = (imgCoordPixel * ViewportInfo[0].xy);",
                "vec2 pl = (imgCoord + (-imgCoordPixel));",
                "vec4 left = textureGather(ps0, coord, mode);",

                "float edgeVote = abs(left.z - left.y) + abs(color[mode] - left.y) + abs(color[mode] - left.z);",
                "if (edgeVote > edgeThreshold) {",
                    "coord.x += ViewportInfo[0].x;",

                    "vec4 right = textureGather(ps0, coord + highp vec2(ViewportInfo[0].x, 0.0), mode);",
                    "vec4 upDown;",
                    "upDown.xy = textureGather(ps0, coord + highp vec2(0.0, -ViewportInfo[0].y), mode).wz;",
                    "upDown.zw = textureGather(ps0, coord + highp vec2(0.0, ViewportInfo[0].y), mode).yx;",

                    "float mean = (left.y + left.z + right.x + right.w) * 0.25;",
                    "left = left - vec4(mean);",
                    "right = right - vec4(mean);",
                    "upDown = upDown - vec4(mean);",
                    "color.w = color[mode] - mean;",

                    "float sum = (((((abs(left.x)+abs(left.y))+abs(left.z))+abs(left.w))+(((abs(right.x)+abs(right.y))+abs(right.z))+abs(right.w)))+(((abs(upDown.x)+abs(upDown.y))+abs(upDown.z))+abs(upDown.w)));",
                    "float std = 2.181818 / sum;",

                    "vec2 aWY = weightY(pl.x, pl.y+1.0, upDown.x, std);",
                    "aWY += weightY(pl.x-1.0, pl.y+1.0, upDown.y, std);",
                    "aWY += weightY(pl.x-1.0, pl.y-2.0, upDown.z, std);",
                    "aWY += weightY(pl.x, pl.y-2.0, upDown.w, std);",
                    "aWY += weightY(pl.x+1.0, pl.y-1.0, left.x, std);",
                    "aWY += weightY(pl.x, pl.y-1.0, left.y, std);",
                    "aWY += weightY(pl.x, pl.y, left.z, std);",
                    "aWY += weightY(pl.x+1.0, pl.y, left.w, std);",
                    "aWY += weightY(pl.x-1.0, pl.y-1.0, right.x, std);",
                    "aWY += weightY(pl.x-2.0, pl.y-1.0, right.y, std);",
                    "aWY += weightY(pl.x-2.0, pl.y, right.z, std);",
                    "aWY += weightY(pl.x-1.0, pl.y, right.w, std);",

                    "float finalY = aWY.y / aWY.x;",

                    "float maxY = max(max(left.y, left.z), max(right.x, right.w));",
                    "float minY = min(min(left.y, left.z), min(right.x, right.w));",
                    "finalY = clamp(edgeSharpness * finalY, minY, maxY);",

                    "float deltaY = finalY - color.w;",

                    "deltaY = clamp(deltaY, -23.0/255.0, 23.0/255.0);",

                    "color.x = clamp((color.x + deltaY), 0.0, 1.0);",
                    "color.y = clamp((color.y + deltaY), 0.0, 1.0);",
                    "color.z = clamp((color.z + deltaY), 0.0, 1.0);",
                "}",

                "color.w = 1.0;",
                "out_Target0.xyzw = color;",
            "}"
        );
    }

    public void setUniformVec4(Uniform uniform, float x, float y, float z, float w) {
        int location = getUniformLocation(uniform);
        if (location != -1) GLES30.glUniform4f(location, x, y, z, w);
    }
}
