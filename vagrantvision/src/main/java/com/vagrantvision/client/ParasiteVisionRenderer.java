package com.vagrantvision.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL13;

/** Small GLSL 1.20 post-process pass. */
public final class ParasiteVisionRenderer {
    private int program;
    private int sceneTexture;
    private int textureWidth = -1;
    private int textureHeight = -1;
    private long startNanos;

    public void render(Minecraft mc) {
        if (!ensureResources(mc)) return;
        final int width = mc.displayWidth;
        final int height = mc.displayHeight;
        if (width <= 0 || height <= 0) return;
        ensureTextureSize(width, height);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        mc.getFramebuffer().bindFramebuffer(false);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTexture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0, 1.0, 0.0, 1.0, -1.0, 1.0);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL20.glUseProgram(program);
        int sampler = GL20.glGetUniformLocation(program, "scene");
        int resolution = GL20.glGetUniformLocation(program, "resolution");
        int time = GL20.glGetUniformLocation(program, "time");
        if (sampler >= 0) GL20.glUniform1i(sampler, 0);
        if (resolution >= 0) GL20.glUniform2f(resolution, (float) width, (float) height);
        if (time >= 0) {
            if (startNanos == 0L) startNanos = System.nanoTime();
            GL20.glUniform1f(time, (System.nanoTime() - startNanos) / 1_000_000_000.0F);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTexture);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0F, 0F); GL11.glVertex2f(0F, 0F);
        GL11.glTexCoord2f(1F, 0F); GL11.glVertex2f(1F, 0F);
        GL11.glTexCoord2f(1F, 1F); GL11.glVertex2f(1F, 1F);
        GL11.glTexCoord2f(0F, 1F); GL11.glVertex2f(0F, 1F);
        GL11.glEnd();
        GL20.glUseProgram(oldProgram);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(oldMatrixMode);
        GL11.glPopAttrib();
    }

    private boolean ensureResources(Minecraft mc) {
        if (program != 0 && sceneTexture != 0) return true;
        try {
            String vertex = readResource(mc, "shaders/parasite.vert");
            String fragment = readResource(mc, "shaders/parasite.frag");
            int vs = compile(GL20.GL_VERTEX_SHADER, vertex);
            int fs = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            if (vs == 0 || fs == 0) return false;
            int linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vs);
            GL20.glAttachShader(linked, fs);
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.err.println("[VagrantVision] Shader link failed: " + GL20.glGetProgramInfoLog(linked, 4096));
                GL20.glDeleteProgram(linked);
                return false;
            }
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            program = linked;
            sceneTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
            return true;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[VagrantVision] Could not initialize shader: " + ex.getMessage());
            return false;
        }
    }

    private void ensureTextureSize(int width, int height) {
        if (width == textureWidth && height == textureHeight) return;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        textureWidth = width;
        textureHeight = height;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.err.println("[VagrantVision] Shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static String readResource(Minecraft mc, String path) throws IOException {
        ResourceLocation location = new ResourceLocation("vagrantvision", path);
        try (InputStream stream = mc.getResourceManager().getResource(location).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
            return result.toString();
        }
    }
}
