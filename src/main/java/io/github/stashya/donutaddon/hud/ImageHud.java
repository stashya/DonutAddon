/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package io.github.stashya.donutaddon.hud;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ImageHud extends HudElement {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum ImageSource {
        FILE("File"),
        RESOURCE("Resource");

        private final String title;

        ImageSource(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public final Setting<ImageSource> imageSource = sgGeneral.add(new EnumSetting.Builder<ImageSource>()
        .name("image-source")
        .description("Where to load the image from.")
        .defaultValue(ImageSource.FILE)
        .onChanged(s -> reloadImage())
        .build()
    );

    public final Setting<String> imagePath = sgGeneral.add(new StringSetting.Builder()
        .name("image-path")
        .description("Path to the image file.")
        .defaultValue("")
        .visible(() -> imageSource.get() == ImageSource.FILE)
        .onChanged(this::loadImage)
        .build()
    );

    public final Setting<String> resourcePath = sgGeneral.add(new StringSetting.Builder()
        .name("resource-path")
        .description("Resource path to the image.")
        .defaultValue("/assets/template/textures/icon.png")
        .visible(() -> imageSource.get() == ImageSource.RESOURCE)
        .onChanged(this::loadResource)
        .build()
    );

    public final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the image.")
        .defaultValue(1.0)
        .min(0.1)
        .max(5.0)
        .sliderRange(0.1, 3.0)
        .onChanged(s -> updateSize())
        .build()
    );

    public final Setting<Boolean> keepAspectRatio = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-aspect-ratio")
        .description("Maintain the original aspect ratio of the image.")
        .defaultValue(true)
        .onChanged(b -> updateSize())
        .build()
    );

    private final Setting<Integer> maxWidth = sgGeneral.add(new IntSetting.Builder()
        .name("max-width")
        .description("Maximum width of the image in pixels.")
        .defaultValue(1920)
        .min(50)
        .max(1920)
        .sliderRange(100, 1920)
        .visible(() -> !keepAspectRatio.get())
        .onChanged(w -> updateSize())
        .build()
    );

    private final Setting<Integer> maxHeight = sgGeneral.add(new IntSetting.Builder()
        .name("max-height")
        .description("Maximum height of the image in pixels.")
        .defaultValue(1080)
        .min(50)
        .max(1080)
        .sliderRange(100, 1080)
        .visible(() -> !keepAspectRatio.get())
        .onChanged(h -> updateSize())
        .build()
    );

    private final Setting<Integer> opacity = sgGeneral.add(new IntSetting.Builder()
        .name("opacity")
        .description("Opacity of the image.")
        .defaultValue(255)
        .min(0)
        .max(255)
        .sliderRange(0, 255)
        .build()
    );

    private NativeImageBackedTexture texture;
    private Identifier textureId;
    private int originalWidth, originalHeight;
    private int displayWidth, displayHeight;
    private boolean imageLoaded = false;
    private String errorMessage = null;

    public ImageHud(HudElementInfo<?> info) {
        super(info);
    }

    @Override
    public void tick(HudRenderer renderer) {
        if (!imageLoaded && texture == null) {
            reloadImage();
        }
        super.tick(renderer);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (isInEditor()) {
            if (!imageLoaded || texture == null) {
                // Show placeholder in editor
                String message = errorMessage != null ? errorMessage : "No image loaded";
                renderer.text(message, x, y, Color.WHITE, true);
                setSize(renderer.textWidth(message, true), renderer.textHeight(true));
                return;
            }
        } else if (!imageLoaded || texture == null) {
            return;
        }

        // Render the image using Meteor's texture renderer
        Color color = new Color(255, 255, 255, opacity.get());
        renderer.texture(textureId, x, y, displayWidth, displayHeight, color);

        setSize(displayWidth, displayHeight);
    }

    public void reloadImage() {
        if (imageSource.get() == ImageSource.FILE) {
            loadImage(imagePath.get());
        } else {
            loadResource(resourcePath.get());
        }
    }

    private void loadResource(String path) {
        if (path == null || path.isEmpty()) {
            clearImage();
            return;
        }

        try {
            // Clear old texture if exists
            clearImage();

            // Load image from resources
            InputStream stream = getClass().getResourceAsStream(path);
            if (stream == null) {
                errorMessage = "Resource not found: " + path;
                clearImage();
                return;
            }

            NativeImage nativeImage = NativeImage.read(stream);
            stream.close();

            originalWidth = nativeImage.getWidth();
            originalHeight = nativeImage.getHeight();

            // Create texture and register it
            texture = new NativeImageBackedTexture(nativeImage);
            textureId = Identifier.of("meteor-client", "image_hud_resource_" + System.currentTimeMillis());
            mc.getTextureManager().registerTexture(textureId, texture);

            imageLoaded = true;
            errorMessage = null;
            updateSize();

        } catch (Exception e) {
            errorMessage = "Failed to load resource: " + e.getMessage();
            clearImage();
            e.printStackTrace();
        }
    }

    private void loadImage(String path) {
        if (path == null || path.isEmpty()) {
            clearImage();
            return;
        }

        try {
            Path filePath = Paths.get(path);
            if (!Files.exists(filePath)) {
                errorMessage = "File not found";
                clearImage();
                return;
            }

            // Clear old texture if exists
            clearImage();

            // Load image using NativeImage
            try (FileInputStream stream = new FileInputStream(filePath.toFile())) {
                NativeImage nativeImage = NativeImage.read(stream);

                originalWidth = nativeImage.getWidth();
                originalHeight = nativeImage.getHeight();

                // Create texture and register it
                texture = new NativeImageBackedTexture(nativeImage);
                textureId = Identifier.of("meteor-client", "image_hud_" + System.currentTimeMillis());
                mc.getTextureManager().registerTexture(textureId, texture);

                imageLoaded = true;
                errorMessage = null;
                updateSize();
            }

        } catch (Exception e) {
            errorMessage = "Failed to load: " + e.getMessage();
            clearImage();
            e.printStackTrace();
        }
    }

    private void clearImage() {
        if (texture != null && textureId != null) {
            mc.getTextureManager().destroyTexture(textureId);
            texture = null;
            textureId = null;
        }
        imageLoaded = false;
        displayWidth = 100;
        displayHeight = 20;
        setSize(displayWidth, displayHeight);
    }

    private void updateSize() {
        if (!imageLoaded || originalWidth == 0 || originalHeight == 0) {
            return;
        }

        double scaleFactor = scale.get();

        if (keepAspectRatio.get()) {
            // Calculate size with aspect ratio
            displayWidth = (int) (originalWidth * scaleFactor);
            displayHeight = (int) (originalHeight * scaleFactor);

            // Constrain to HD max (1920x1080)
            if (displayWidth > 1920) {
                double ratio = 1920.0 / displayWidth;
                displayWidth = 1920;
                displayHeight = (int) (displayHeight * ratio);
            }
            if (displayHeight > 1080) {
                double ratio = 1080.0 / displayHeight;
                displayHeight = 1080;
                displayWidth = (int) (displayWidth * ratio);
            }
        } else {
            // Use max width/height settings
            displayWidth = Math.max(1, Math.min((int) (originalWidth * scaleFactor), maxWidth.get()));
            displayHeight = Math.max(1, Math.min((int) (originalHeight * scaleFactor), maxHeight.get()));
        }

        setSize(displayWidth, displayHeight);
    }

    @Override
    public void onFontChanged() {
        // Not needed for images
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        tag.putBoolean("imageLoaded", imageLoaded);
        if (errorMessage != null) {
            tag.putString("errorMessage", errorMessage);
        }
        return tag;
    }

    @Override
    public ImageHud fromTag(NbtCompound tag) {
        super.fromTag(tag);

        // Re-load image after loading from tag
        reloadImage();

        return this;
    }
    public void configureAsWatermark(String resourcePath, int opacity, double scale) {
        this.imageSource.set(ImageSource.RESOURCE);
        this.resourcePath.set(resourcePath);
        this.opacity.set(opacity);
        this.scale.set(scale);
        this.keepAspectRatio.set(true);
        reloadImage();
    }
}
