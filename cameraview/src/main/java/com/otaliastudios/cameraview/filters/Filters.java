package com.otaliastudios.cameraview.filters;

import androidx.annotation.NonNull;

/**
 * Contains commonly used {@link Filter}s.
 *
 * You can use {@link #newInstance()} to create a new instance and
 * pass it to {@link com.otaliastudios.cameraview.CameraView#setFilter(Filter)}.
 */
public enum Filters {
    NONE(NoFilter.class),
    AUTO_FIX(AutoFixFilter.class),
    BLACK_AND_WHITE(BlackAndWhiteFilter.class),
    BRIGHTNESS(BrightnessFilter.class),
    CONTRAST(ContrastFilter.class),
    CROSS_PROCESS(CrossProcessFilter.class),
    DOCUMENTARY(DocumentaryFilter.class),
    DUOTONE(DuotoneFilter.class),
    FILL_LIGHT(FillLightFilter.class),
    GAMMA(GammaFilter.class),
    GRAIN(GrainFilter.class),
    GRAYSCALE(GrayscaleFilter.class),
    HUE(HueFilter.class),
    INVERT_COLORS(InvertColorsFilter.class),
    LOMOISH(LomoishFilter.class),
    POSTERIZE(PosterizeFilter.class),
    SATURATION(SaturationFilter.class),
    SEPIA(SepiaFilter.class),
    SHARPNESS(SharpnessFilter.class),
    TEMPERATURE(TemperatureFilter.class),
    TINT(TintFilter.class),
    VIGNETTE(VignetteFilter.class);

    private Class<? extends Filter> filterClass;

    Filters(@NonNull Class<? extends Filter> filterClass) {
        this.filterClass = filterClass;
    }

    /**
     * Returns a new instance of the given filter.
     * @return a new instance
     */
    @NonNull
    public Filter newInstance() {
        try {
            return filterClass.newInstance();
        } catch (IllegalAccessException e) {
            return new NoFilter();
        } catch (InstantiationException e) {
            return new NoFilter();
        }
    }
}
