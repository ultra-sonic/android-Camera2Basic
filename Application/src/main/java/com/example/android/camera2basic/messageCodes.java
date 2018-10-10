package com.example.android.camera2basic;

/**
 * Created by omarkowski on 10/10/18.
 */

public enum messageCodes {
    CLEAN_EXIT(-1,""),

    ANDROID_START_CAPTURE( 2, ""),
    ANDROID_FINISHED_WRITING_IMAGES(44,""),

    LIGHTSTAGE_POLARIZER_STARTS_MOVING(3,""),
    LIGHTSTAGE_SHOT_MODE_SINGLE_SHOT(10,"single shot"),
    LIGHTSTAGE_SHOT_MODE_NO_POLARIZER(11,"without polarizer only"),
    LIGHTSTAGE_SHOT_MODE_CROSS_POLARIZED_ONLY(12,"cross-polarized only"),
    LIGHTSTAGE_SHOT_MODE_FULL_BLOWN(13,"full-blown shoot"),
    LIGHTSTAGE_SHOT_MODE_DO_NOTHING(14,"do nothing - just tell the camera to shoot");

    private final int code;
    private final String description;

    messageCodes(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public int getCode() {
        return code;
    }

    @Override
    public String toString() {
        return code + ": " + description;
    }
}
